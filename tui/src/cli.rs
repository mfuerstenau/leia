//! Command-line interface, as specified by the TUI CLI contract in `AGENTS.md`.

use std::path::PathBuf;

use clap::{ArgAction, Parser};

// clap's own version flag is replaced, because `--version` also reports the Java version, which
// only the server knows.
#[derive(Debug, Parser)]
#[command(about, disable_version_flag = true)]
pub struct Cli {
    /// Print the version and build of leia and leia-server, and the Java version in use.
    #[arg(short = 'V', long, action = ArgAction::SetTrue)]
    pub version: bool,

    /// The JVM that runs leia-server.jar: a `java` executable or a JDK/JRE directory.
    /// Defaults to `$JAVA_HOME/bin/java`, then `java` on `PATH`.
    #[arg(short, long, value_name = "PATH")]
    pub java: Option<PathBuf>,

    /// A jar with analysis components (char filters, tokenizers, token filters, analyzers),
    /// codecs or formats of its own, such as one of Lucene's analysis modules, or a directory of
    /// such jars. Can be repeated.
    #[arg(long = "jar", value_name = "PATH")]
    pub jars: Vec<PathBuf>,

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
        assert!(!cli.version);
    }

    #[test]
    fn version_flag_is_parsed_not_handled_by_clap() {
        for flag in ["--version", "-V"] {
            let cli = Cli::try_parse_from(["leia", flag, "-j", "/opt/jdk"]).unwrap();
            assert!(cli.version);
            assert_eq!(cli.java, Some(PathBuf::from("/opt/jdk")));
        }
    }

    #[test]
    fn all_arguments_are_optional() {
        let cli = Cli::try_parse_from(["leia"]).unwrap();
        assert!(!cli.version);
        assert_eq!(cli.java, None);
        assert!(cli.jars.is_empty());
        assert_eq!(cli.index_path, None);
    }

    #[test]
    fn jars_can_be_repeated() {
        let cli = Cli::try_parse_from(["leia", "--jar", "icu", "--jar", "my.jar", "/data/index"])
            .unwrap();
        assert_eq!(cli.jars, [PathBuf::from("icu"), PathBuf::from("my.jar")]);
        assert_eq!(cli.index_path, Some(PathBuf::from("/data/index")));
    }
}
