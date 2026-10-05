//! Finds the JVM that runs `leia-server.jar`, as specified by the TUI CLI contract in
//! `AGENTS.md`: `--java`, then `$JAVA_HOME/bin/java`, then `java` on `PATH`.

use std::ffi::OsStr;
use std::fmt;
use std::path::{Path, PathBuf};

use anyhow::{Result, bail};

/// The `java` executable to run, and where it came from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Java {
    pub executable: PathBuf,
    pub source: JavaSource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JavaSource {
    /// The `--java` option.
    Option,
    /// `$JAVA_HOME/bin/java`.
    JavaHome,
    /// `java` on `PATH`.
    Path,
}

impl fmt::Display for Java {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let source = match self.source {
            JavaSource::Option => "from --java",
            JavaSource::JavaHome => "from JAVA_HOME",
            JavaSource::Path => "on PATH",
        };
        write!(f, "{} ({source})", self.executable.display())
    }
}

/// Chooses the JVM.
///
/// - `option` (`--java`) is a `java` executable or a JDK/JRE directory.
/// - `java_home` (`$JAVA_HOME`) is used if it contains `bin/java`; otherwise `java` is looked up
///   on `PATH` when the server is started.
pub fn find_java(option: Option<&Path>, java_home: Option<&OsStr>) -> Result<Java> {
    if let Some(path) = option {
        let executable = if path.is_dir() {
            let executable = java_in(path);
            if !executable.is_file() {
                bail!(
                    "--java {}: the directory has no {}; pass a JDK/JRE directory or a java executable",
                    path.display(),
                    Path::new("bin").join(java_file_name()).display()
                );
            }
            executable
        } else {
            path.to_path_buf()
        };
        return Ok(Java {
            executable,
            source: JavaSource::Option,
        });
    }

    if let Some(home) = java_home.filter(|home| !home.is_empty()) {
        let executable = java_in(Path::new(home));
        if executable.is_file() {
            return Ok(Java {
                executable,
                source: JavaSource::JavaHome,
            });
        }
    }

    Ok(Java {
        executable: PathBuf::from(java_file_name()),
        source: JavaSource::Path,
    })
}

fn java_in(home: &Path) -> PathBuf {
    home.join("bin").join(java_file_name())
}

fn java_file_name() -> String {
    format!("java{}", std::env::consts::EXE_SUFFIX)
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;
    use crate::test_support::TempDir;

    /// A fake JDK: a directory with an empty `bin/java`.
    fn fake_jdk(dir: &TempDir, name: &str) -> PathBuf {
        let home = dir.path().join(name);
        fs::create_dir_all(home.join("bin")).unwrap();
        fs::write(java_in(&home), "").unwrap();
        home
    }

    #[test]
    fn option_with_a_jdk_directory_uses_its_bin_java() {
        let dir = TempDir::new();
        let home = fake_jdk(&dir, "jdk");

        let java = find_java(Some(&home), None).unwrap();

        assert_eq!(java.executable, java_in(&home));
        assert_eq!(java.source, JavaSource::Option);
    }

    #[test]
    fn option_with_an_executable_is_used_as_is() {
        let java = find_java(Some(Path::new("/opt/jdk/bin/java")), Some(OsStr::new("/x"))).unwrap();

        assert_eq!(java.executable, PathBuf::from("/opt/jdk/bin/java"));
        assert_eq!(java.source, JavaSource::Option);
    }

    #[test]
    fn option_with_a_directory_without_java_fails() {
        let dir = TempDir::new();

        let error = find_java(Some(dir.path()), None).unwrap_err();

        assert!(error.to_string().contains("has no"), "{error}");
    }

    #[test]
    fn option_wins_over_java_home() {
        let dir = TempDir::new();
        let option = fake_jdk(&dir, "option");
        let home = fake_jdk(&dir, "home");

        let java = find_java(Some(&option), Some(home.as_os_str())).unwrap();

        assert_eq!(java.executable, java_in(&option));
    }

    #[test]
    fn java_home_is_used_without_option() {
        let dir = TempDir::new();
        let home = fake_jdk(&dir, "home");

        let java = find_java(None, Some(home.as_os_str())).unwrap();

        assert_eq!(java.executable, java_in(&home));
        assert_eq!(java.source, JavaSource::JavaHome);
    }

    #[test]
    fn java_home_without_java_falls_back_to_path() {
        let dir = TempDir::new();

        let java = find_java(None, Some(dir.path().as_os_str())).unwrap();

        assert_eq!(java.source, JavaSource::Path);
    }

    #[test]
    fn path_is_the_last_resort() {
        for java_home in [None, Some(OsStr::new(""))] {
            let java = find_java(None, java_home).unwrap();

            assert_eq!(java.executable, PathBuf::from(java_file_name()));
            assert_eq!(java.source, JavaSource::Path);
        }
    }
}
