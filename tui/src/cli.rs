//! Command-line interface, as specified by the TUI CLI contract in `AGENTS.md`.

use std::path::PathBuf;

use clap::builder::{PossibleValuesParser, TypedValueParser};
use clap::{ArgAction, Parser};

use crate::protocol::index::DirectoryImpl;

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

    /// The Lucene `Directory` to read indexes through: `fs` lets Lucene choose (`MMapDirectory`
    /// on 64-bit), `mmap` is `MMapDirectory`, `nio` is `NIOFSDirectory`. Ctrl+D in the open
    /// prompt changes it.
    #[arg(
        long,
        value_name = "IMPL",
        default_value = "fs",
        value_parser = PossibleValuesParser::new(["fs", "mmap", "nio"]).map(directory_impl),
    )]
    pub directory: DirectoryImpl,

    /// Lucene index directory to open.
    pub index_path: Option<PathBuf>,
}

fn directory_impl(name: String) -> DirectoryImpl {
    match name.as_str() {
        "mmap" => DirectoryImpl::Mmap,
        "nio" => DirectoryImpl::Nio,
        _ => DirectoryImpl::Fs,
    }
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
        assert_eq!(cli.directory, DirectoryImpl::Fs);
        assert_eq!(cli.index_path, None);
    }

    #[test]
    fn parses_the_directory() {
        for (name, directory) in [
            ("fs", DirectoryImpl::Fs),
            ("mmap", DirectoryImpl::Mmap),
            ("nio", DirectoryImpl::Nio),
        ] {
            let cli = Cli::try_parse_from(["leia", "--directory", name]).unwrap();
            assert_eq!(cli.directory, directory);
        }
        assert!(Cli::try_parse_from(["leia", "--directory", "ram"]).is_err());
    }

    #[test]
    fn jars_can_be_repeated() {
        let cli = Cli::try_parse_from(["leia", "--jar", "icu", "--jar", "my.jar", "/data/index"])
            .unwrap();
        assert_eq!(cli.jars, [PathBuf::from("icu"), PathBuf::from("my.jar")]);
        assert_eq!(cli.index_path, Some(PathBuf::from("/data/index")));
    }
}
