//! Command-line interface, as specified by the TUI CLI contract in `AGENTS.md`.

use std::path::PathBuf;

use clap::Parser;

#[derive(Debug, Parser)]
#[command(version, about)]
pub struct Cli {
    /// The JVM that runs leia-server.jar: a `java` executable or a JDK/JRE directory.
    /// Defaults to `$JAVA_HOME/bin/java`, then `java` on `PATH`.
    #[arg(short, long, value_name = "PATH")]
    pub java: Option<PathBuf>,

    /// Lucene index directory to open.
    pub index_path: Option<PathBuf>,
}

#[cfg(test)]
mod tests {
    use clap::CommandFactory;

    use super::*;

    #[test]
    fn cli_definition_is_valid() {
        Cli::command().debug_assert();
    }

    #[test]
    fn parses_java_and_index_path() {
        let cli = Cli::try_parse_from(["leia", "-j", "/opt/jdk", "/data/index"]).unwrap();
        assert_eq!(cli.java, Some(PathBuf::from("/opt/jdk")));
        assert_eq!(cli.index_path, Some(PathBuf::from("/data/index")));
    }

    #[test]
    fn all_arguments_are_optional() {
        let cli = Cli::try_parse_from(["leia"]).unwrap();
        assert_eq!(cli.java, None);
        assert_eq!(cli.index_path, None);
    }
}
