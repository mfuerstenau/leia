//! The `leia-server.jar` child process: finding the jar, starting it, capturing its stderr, and
//! shutting it down.

use std::collections::VecDeque;
use std::ffi::{OsStr, OsString};
use std::fs;
use std::io::{self, BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStderr, ChildStdin, ChildStdout, Command, ExitStatus, Stdio};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow, bail};

use crate::client::{CallError, RpcClient};
use crate::protocol::RpcMethod;

/// File name of the server jar, looked up next to the `leia` executable.
pub const SERVER_JAR: &str = "leia-server.jar";

/// Environment variable that overrides the jar location, for development (`cargo run`).
pub const SERVER_JAR_ENV: &str = "LEIA_SERVER_JAR";

/// How long the server gets to exit after its stdin is closed before it is killed.
pub const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(2);

/// How many lines of the server's stderr are kept.
const STDERR_LINES: usize = 1000;

/// How many of them an error message shows.
const STDERR_LINES_IN_ERRORS: usize = 20;

/// Finds `leia-server.jar`: `$LEIA_SERVER_JAR` if set, otherwise next to the executable (with
/// symlinks resolved, so a link to `leia` in `~/bin` still finds the jar).
pub fn find_server_jar(override_path: Option<&OsStr>) -> Result<PathBuf> {
    if let Some(path) = override_path.filter(|path| !path.is_empty()) {
        let path = PathBuf::from(path);
        if !path.is_file() {
            bail!("{SERVER_JAR_ENV}={}: no such file", path.display());
        }
        return Ok(path);
    }
    let executable = std::env::current_exe().context("cannot locate the leia executable")?;
    let executable = fs::canonicalize(&executable).unwrap_or(executable);
    let jar = executable.with_file_name(SERVER_JAR);
    if !jar.is_file() {
        bail!(
            "{SERVER_JAR} not found next to the leia executable (looked for {}); \
             set {SERVER_JAR_ENV} to use a jar elsewhere",
            jar.display()
        );
    }
    Ok(jar)
}

/// Checks that each analysis jar or directory exists; the server checks the rest (ADR 0016).
pub fn check_analysis_jars(paths: &[PathBuf]) -> Result<()> {
    for path in paths {
        if !path.exists() {
            bail!(
                "--analysis-jar {}: no such jar or directory",
                path.display()
            );
        }
    }
    Ok(())
}

/// The server's arguments after `java`.
fn server_args(jar: &Path, analysis_jars: &[PathBuf]) -> Vec<OsString> {
    let mut args = vec![OsString::from("-jar"), jar.into()];
    for path in analysis_jars {
        args.push("--analysis-jar".into());
        args.push(path.into());
    }
    args
}

type ChildClient = RpcClient<ChildStdin, BufReader<ChildStdout>>;

/// A running server. Dropping it shuts the server down.
pub struct Server {
    child: Child,
    /// `None` once the server is shut down; dropping it closes the server's stdin.
    client: Option<ChildClient>,
    stderr: StderrLog,
}

impl Server {
    /// Starts `<java> -jar <jar>`, with an `--analysis-jar` argument for each of `analysis_jars`
    /// (ADR 0016).
    pub fn spawn(java: &Path, jar: &Path, analysis_jars: &[PathBuf]) -> Result<Self> {
        let mut command = Command::new(java);
        command.args(server_args(jar, analysis_jars));
        Self::from_command(command).with_context(|| format!("cannot start {}", java.display()))
    }

    /// Starts `command` as the server, with all three standard streams piped.
    fn from_command(mut command: Command) -> io::Result<Self> {
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            // Captured, not inherited: the server's logs must not draw over the TUI.
            .stderr(Stdio::piped())
            .spawn()?;
        let stdin = child.stdin.take().expect("stdin is piped");
        let stdout = child.stdout.take().expect("stdout is piped");
        let stderr = StderrLog::capture(child.stderr.take().expect("stderr is piped"))?;
        Ok(Self {
            child,
            client: Some(RpcClient::new(stdin, BufReader::new(stdout))),
            stderr,
        })
    }

    /// Calls `M` and waits for its response. If the server is gone, the error includes its exit
    /// status and the end of its stderr.
    pub fn call<M: RpcMethod>(&mut self, params: M::Params) -> Result<M::Result, CallError> {
        let client = self
            .client
            .as_mut()
            .ok_or_else(|| CallError::Transport(anyhow!("the server has been shut down")))?;
        client.call::<M>(params).map_err(|error| match error {
            CallError::Transport(error) => CallError::Transport(self.explain(error)),
            rpc => rpc,
        })
    }

    /// The captured stderr lines of the server, oldest first.
    pub fn stderr_lines(&self) -> Vec<String> {
        self.stderr.lines()
    }

    /// Closes the server's stdin, which makes it exit, and kills it if it is still running
    /// after `timeout`.
    pub fn shutdown(&mut self, timeout: Duration) -> io::Result<ExitStatus> {
        self.client = None;
        if let Some(status) = wait_timeout(&mut self.child, timeout)? {
            return Ok(status);
        }
        self.child.kill()?;
        self.child.wait()
    }

    /// Adds the exit status and the end of stderr to a transport error, if the server has
    /// exited. Waits briefly, since a broken pipe is often seen before the process is gone.
    fn explain(&mut self, error: anyhow::Error) -> anyhow::Error {
        let Ok(Some(status)) = wait_timeout(&mut self.child, Duration::from_millis(500)) else {
            return error;
        };
        self.stderr.wait_closed(Duration::from_millis(500));
        let lines = self.stderr.lines();
        let tail = &lines[lines.len().saturating_sub(STDERR_LINES_IN_ERRORS)..];
        if tail.is_empty() {
            error.context(format!(
                "the server exited ({status}) without output on stderr"
            ))
        } else {
            error.context(format!(
                "the server exited ({status}); its stderr ends with:\n  {}",
                tail.join("\n  ")
            ))
        }
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.shutdown(SHUTDOWN_TIMEOUT);
    }
}

/// Waits up to `timeout` for `child` to exit.
fn wait_timeout(child: &mut Child, timeout: Duration) -> io::Result<Option<ExitStatus>> {
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(Some(status));
        }
        if Instant::now() >= deadline {
            return Ok(None);
        }
        thread::sleep(Duration::from_millis(10));
    }
}

/// The last [`STDERR_LINES`] lines of the server's stderr, read by a background thread.
struct StderrLog {
    shared: Arc<(Mutex<StderrState>, Condvar)>,
}

#[derive(Default)]
struct StderrState {
    lines: VecDeque<String>,
    closed: bool,
}

impl StderrLog {
    fn capture(stderr: ChildStderr) -> io::Result<Self> {
        let shared = Arc::new((Mutex::new(StderrState::default()), Condvar::new()));
        let writer = Arc::clone(&shared);
        thread::Builder::new()
            .name("leia-server-stderr".to_owned())
            .spawn(move || {
                let (state, closed) = &*writer;
                // Stops at EOF (the server exited) and on invalid input or read errors.
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    let mut state = lock(state);
                    if state.lines.len() == STDERR_LINES {
                        state.lines.pop_front();
                    }
                    state.lines.push_back(line);
                }
                lock(state).closed = true;
                closed.notify_all();
            })?;
        Ok(Self { shared })
    }

    fn lines(&self) -> Vec<String> {
        lock(&self.shared.0).lines.iter().cloned().collect()
    }

    /// Waits up to `timeout` until the server's stderr has been read to the end.
    fn wait_closed(&self, timeout: Duration) {
        let (state, closed) = &*self.shared;
        let _ = closed.wait_timeout_while(lock(state), timeout, |state| !state.closed);
    }
}

/// Locks `mutex`, ignoring poisoning: the state stays consistent line by line.
fn lock(mutex: &Mutex<StderrState>) -> MutexGuard<'_, StderrState> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;
    use crate::protocol::version::{Version, VersionParams};
    use crate::test_support::TempDir;

    #[test]
    fn override_must_point_to_a_file() {
        let dir = TempDir::new();
        let jar = dir.path().join(SERVER_JAR);

        assert!(find_server_jar(Some(jar.as_os_str())).is_err());
        fs::write(&jar, "").unwrap();
        assert_eq!(find_server_jar(Some(jar.as_os_str())).unwrap(), jar);
    }

    #[test]
    fn without_override_looks_next_to_the_executable() {
        // The test binary has no leia-server.jar next to it.
        let error = find_server_jar(None).unwrap_err();

        assert!(error.to_string().contains(SERVER_JAR_ENV), "{error}");
    }

    #[test]
    fn analysis_jars_follow_the_jar() {
        let args = server_args(
            Path::new("leia-server.jar"),
            &[PathBuf::from("icu"), PathBuf::from("my.jar")],
        );

        assert_eq!(
            args,
            [
                "-jar",
                "leia-server.jar",
                "--analysis-jar",
                "icu",
                "--analysis-jar",
                "my.jar"
            ]
        );
        assert_eq!(server_args(Path::new("s.jar"), &[]), ["-jar", "s.jar"]);
    }

    #[test]
    fn analysis_jars_must_exist() {
        let dir = TempDir::new();
        let jar = dir.path().join("my.jar");
        fs::write(&jar, "").unwrap();

        assert!(check_analysis_jars(&[dir.path().to_path_buf(), jar.clone()]).is_ok());
        let error = check_analysis_jars(&[jar, dir.path().join("missing.jar")]).unwrap_err();
        assert!(
            error
                .to_string()
                .ends_with("missing.jar: no such jar or directory"),
            "{error}"
        );
    }

    /// A fake server: a shell script in place of `java -jar leia-server.jar`.
    #[cfg(unix)]
    fn fake_server(script: &str) -> Server {
        let mut command = Command::new("sh");
        command.arg("-c").arg(script);
        Server::from_command(command).unwrap()
    }

    #[cfg(unix)]
    #[test]
    fn talks_over_stdin_and_stdout() {
        let mut server = fake_server(
            r#"read request; echo '{"jsonrpc":"2.0","id":1,"result":{"name":"fake","version":"1","commit":"c","commitTime":"t","java":{"version":"25","vendor":"v","home":"/h"}}}'; cat >/dev/null"#,
        );

        let result = server.call::<Version>(VersionParams {}).unwrap();

        assert_eq!(result.name, "fake");
        assert!(server.shutdown(SHUTDOWN_TIMEOUT).unwrap().success());
    }

    #[cfg(unix)]
    #[test]
    fn explains_a_dead_server_with_its_stderr() {
        let mut server = fake_server(
            "echo 'Error: LinkageError occurred while loading main class' >&2; \
             echo 'UnsupportedClassVersionError: class file version 69.0' >&2; exit 1",
        );

        let error = server.call::<Version>(VersionParams {}).unwrap_err();
        // The whole chain, as anyhow prints it.
        let error = format!("{:#}", anyhow::Error::from(error));

        assert!(error.contains("exited"), "{error}");
        assert!(error.contains("UnsupportedClassVersionError"), "{error}");
        assert!(
            error.contains("closed the connection") || error.contains("cannot send"),
            "{error}"
        );
    }

    #[cfg(unix)]
    #[test]
    fn keeps_stderr_out_of_the_terminal() {
        let mut server = fake_server("echo log line >&2; cat >/dev/null");

        server.shutdown(SHUTDOWN_TIMEOUT).unwrap();
        server.stderr.wait_closed(SHUTDOWN_TIMEOUT);

        assert_eq!(server.stderr_lines(), ["log line"]);
    }

    #[cfg(unix)]
    #[test]
    fn shutdown_closes_stdin_and_the_server_exits() {
        let mut server = fake_server("cat >/dev/null; exit 3");

        let status = server.shutdown(SHUTDOWN_TIMEOUT).unwrap();

        assert_eq!(status.code(), Some(3));
    }

    #[cfg(unix)]
    #[test]
    fn shutdown_kills_a_server_that_ignores_end_of_input() {
        // `exec` so that the kill hits sleep itself, not a shell waiting for it.
        let mut server = fake_server("exec sleep 30");
        let started = Instant::now();

        let status = server.shutdown(Duration::from_millis(100)).unwrap();

        assert!(!status.success());
        assert!(started.elapsed() < Duration::from_secs(10));
    }

    #[cfg(unix)]
    #[test]
    fn calls_after_shutdown_fail() {
        let mut server = fake_server("cat >/dev/null");
        server.shutdown(SHUTDOWN_TIMEOUT).unwrap();

        let error = server.call::<Version>(VersionParams {}).unwrap_err();

        assert!(error.to_string().contains("shut down"), "{error}");
    }
}
