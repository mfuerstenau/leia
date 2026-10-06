//! The `leia` binary: parses the command line, starts the server and runs the terminal UI.

use std::env;
use std::io::{self, Write};
use std::path::Path;

use anyhow::{Context, Result};
use clap::Parser;
use leia::app::{Action, App};
use leia::build_info::{LEIA, java_line, server_line};
use leia::cli::Cli;
use leia::client::CallError;
use leia::java::find_java;
use leia::protocol::index::{
    CloseIndex, CloseIndexParams, GetIndexStats, GetIndexStatsParams, OpenIndex, OpenIndexParams,
};
use leia::protocol::version::{Version, VersionParams, VersionResult};
use leia::server::{SERVER_JAR_ENV, SHUTDOWN_TIMEOUT, Server, find_server_jar};
use ratatui::DefaultTerminal;
use ratatui::crossterm::event::{self, Event, KeyEventKind};

fn main() -> Result<()> {
    let cli = Cli::parse();
    if cli.version {
        return print_version(&cli);
    }
    let index_path = cli.index_path.as_deref().map(utf8_path).transpose()?;

    // Start the server before taking over the terminal, so that startup errors are printed
    // normally.
    let (mut server, version) = start_server(&cli)?;
    let mut app = App::new(version, env::home_dir());

    // ratatui::run restores the terminal on exit and on panic; dropping the server on a panic
    // shuts it down too.
    let result = ratatui::run(|terminal| run(terminal, &mut server, &mut app, index_path));
    let closed = if app.has_index() {
        server
            .call::<CloseIndex>(CloseIndexParams {})
            .map(drop)
            .context("cannot close the index")
    } else {
        Ok(())
    };
    let stopped = server
        .shutdown(SHUTDOWN_TIMEOUT)
        .context("cannot stop leia-server");
    result?;
    closed?;
    stopped?;
    Ok(())
}

/// The protocol carries paths as strings (JSON has no other way), so they must be UTF-8.
fn utf8_path(path: &Path) -> Result<String> {
    path.to_str()
        .map(str::to_owned)
        .with_context(|| format!("the index path is not valid UTF-8: {}", path.display()))
}

/// `--version`: leia's own line comes first, so it is printed even if the server can't start.
fn print_version(cli: &Cli) -> Result<()> {
    let mut out = io::stdout().lock();
    writeln!(out, "{}", LEIA.line())?;
    out.flush()?;
    let (mut server, version) = start_server(cli)?;
    writeln!(out, "{}", server_line(&version))?;
    writeln!(out, "{}", java_line(&version.java))?;
    server
        .shutdown(SHUTDOWN_TIMEOUT)
        .context("cannot stop leia-server")?;
    Ok(())
}

/// Finds the JVM and the jar, starts the server and checks that it answers `version`.
fn start_server(cli: &Cli) -> Result<(Server, VersionResult)> {
    let java = find_java(cli.java.as_deref(), env::var_os("JAVA_HOME").as_deref())?;
    let jar = find_server_jar(env::var_os(SERVER_JAR_ENV).as_deref())?;
    let mut server = Server::spawn(&java.executable, &jar)
        .with_context(|| format!("no usable JVM: {java}; choose one with --java or JAVA_HOME"))?;
    let version = server.call::<Version>(VersionParams {}).with_context(|| {
        format!("leia-server did not start with {java}; it needs Java 25 or newer (see --java)")
    })?;
    Ok((server, version))
}

fn run(
    terminal: &mut DefaultTerminal,
    server: &mut Server,
    app: &mut App,
    index_path: Option<String>,
) -> Result<()> {
    if let Some(path) = index_path {
        app.opening(&path);
        terminal.draw(|frame| app.draw(frame))?;
        open_index(server, app, &path)?;
    }
    loop {
        terminal.draw(|frame| app.draw(frame))?;
        // Windows reports both press and release; only handle presses.
        let Event::Key(key) = event::read()? else {
            continue;
        };
        if key.kind != KeyEventKind::Press {
            continue;
        }
        match app.handle_key(key) {
            None => {}
            Some(Action::Quit) => return Ok(()),
            Some(Action::Open(path)) => {
                // Shows "Opening…" while the server works.
                terminal.draw(|frame| app.draw(frame))?;
                open_index(server, app, &path)?;
            }
            Some(Action::Close) => {
                server.call::<CloseIndex>(CloseIndexParams {})?;
                app.closed();
            }
        }
    }
}

/// Opens the index and fetches its statistics for the overview. The server's errors are shown
/// in the UI; a broken connection to the server ends leia.
fn open_index(server: &mut Server, app: &mut App, path: &str) -> Result<()> {
    let opened = server.call::<OpenIndex>(OpenIndexParams {
        path: path.to_owned(),
    });
    if let Err(error) = answered(opened)? {
        app.open_failed(error);
        return Ok(());
    }
    match answered(server.call::<GetIndexStats>(GetIndexStatsParams {}))? {
        Ok(stats) => app.opened(stats),
        Err(error) => {
            // Opened, but the overview can't show it: close it rather than keep it open unseen.
            server.call::<CloseIndex>(CloseIndexParams {})?;
            app.closed();
            app.open_failed(format!("cannot read the index statistics: {error}"));
        }
    }
    Ok(())
}

/// Splits the outcome of a call: an error the server answered with becomes a message for the UI,
/// a transport error is returned as an error.
fn answered<T>(result: Result<T, CallError>) -> Result<Result<T, String>> {
    match result {
        Ok(value) => Ok(Ok(value)),
        Err(error @ CallError::Rpc(_)) => Ok(Err(error.to_string())),
        Err(CallError::Transport(error)) => Err(error),
    }
}
