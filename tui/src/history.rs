//! The recently opened index paths (ADR 0020): newest first, at most [`MAX_PATHS`], kept in a file
//! of the platform's state directory, one path per line.

use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::{fs, io, process};

/// How many paths are kept.
pub const MAX_PATHS: usize = 10;

pub struct History {
    paths: Vec<String>,
    /// Where the paths are kept; `None` keeps them in memory only.
    file: Option<PathBuf>,
}

impl History {
    /// A history that is not kept in a file.
    pub fn in_memory() -> Self {
        Self {
            paths: Vec::new(),
            file: None,
        }
    }

    /// Reads the history from `file`. A missing or unreadable file is an empty history.
    pub fn load(file: PathBuf) -> Self {
        let paths = fs::read_to_string(&file)
            .map(|text| {
                let mut paths: Vec<String> = Vec::new();
                for line in text.lines().filter(|line| !line.is_empty()) {
                    if paths.len() < MAX_PATHS && !paths.iter().any(|path| path == line) {
                        paths.push(line.to_owned());
                    }
                }
                paths
            })
            .unwrap_or_default();
        Self {
            paths,
            file: Some(file),
        }
    }

    /// Newest first.
    pub fn paths(&self) -> &[String] {
        &self.paths
    }

    /// Puts `path` first and writes the file. A path with a line break can't be kept in it and is
    /// left out. A failed write is ignored: the history is a convenience, and the TUI has nowhere
    /// to report it.
    pub fn add(&mut self, path: &str) {
        if path.is_empty() || path.contains(['\n', '\r']) {
            return;
        }
        self.paths.retain(|kept| kept != path);
        self.paths.insert(0, path.to_owned());
        self.paths.truncate(MAX_PATHS);
        if let Some(file) = &self.file {
            let _ = write(file, &self.paths);
        }
    }
}

/// Writes the whole file through a temporary one, so that a reader never sees half of it.
fn write(file: &Path, paths: &[String]) -> io::Result<()> {
    if let Some(dir) = file.parent() {
        fs::create_dir_all(dir)?;
    }
    let mut text = paths.join("\n");
    text.push('\n');
    let mut temporary = file.as_os_str().to_owned();
    temporary.push(format!(".{}.tmp", process::id()));
    fs::write(&temporary, text)?;
    fs::rename(&temporary, file).inspect_err(|_| {
        let _ = fs::remove_file(&temporary);
    })
}

/// The history file of the platform: `%LOCALAPPDATA%\leia\history` on Windows; elsewhere
/// `$XDG_STATE_HOME/leia/history`, or `~/.local/state/leia/history` without it. `None` if the
/// platform's variables don't say where.
pub fn default_file(home: Option<&Path>) -> Option<PathBuf> {
    if cfg!(windows) {
        local_app_data_file(std::env::var_os("LOCALAPPDATA").as_deref())
    } else {
        xdg_state_file(std::env::var_os("XDG_STATE_HOME").as_deref(), home)
    }
}

fn local_app_data_file(local_app_data: Option<&OsStr>) -> Option<PathBuf> {
    let dir = Path::new(local_app_data?);
    dir.is_absolute().then(|| dir.join("leia").join("history"))
}

/// The XDG Base Directory spec says that a relative `XDG_STATE_HOME` is to be ignored.
fn xdg_state_file(xdg_state_home: Option<&OsStr>, home: Option<&Path>) -> Option<PathBuf> {
    let state = xdg_state_home
        .map(Path::new)
        .filter(|dir| dir.is_absolute())
        .map(Path::to_path_buf)
        .or_else(|| home.map(|home| home.join(".local").join("state")))?;
    Some(state.join("leia").join("history"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::TempDir;

    #[test]
    fn adds_the_newest_first_without_duplicates() {
        let mut history = History::in_memory();
        history.add("/a");
        history.add("/b");
        history.add("/a");

        assert_eq!(history.paths(), ["/a", "/b"]);
    }

    #[test]
    fn keeps_at_most_ten_paths() {
        let mut history = History::in_memory();
        for i in 0..12 {
            history.add(&format!("/{i}"));
        }

        assert_eq!(history.paths().len(), MAX_PATHS);
        assert_eq!(history.paths()[0], "/11");
        assert_eq!(history.paths()[9], "/2");
    }

    #[test]
    fn leaves_out_paths_with_line_breaks() {
        let mut history = History::in_memory();
        history.add("/a\nb");
        history.add("");

        assert!(history.paths().is_empty());
    }

    #[test]
    fn writes_the_file_and_reads_it_back() {
        let dir = TempDir::new();
        let file = dir.path().join("state").join("leia").join("history");
        let mut history = History::load(file.clone());
        assert!(history.paths().is_empty());

        history.add("/a");
        history.add("C:\\b c");

        assert_eq!(fs::read_to_string(&file).unwrap(), "C:\\b c\n/a\n");
        assert_eq!(History::load(file).paths(), ["C:\\b c", "/a"]);
        assert_eq!(
            fs::read_dir(dir.path().join("state").join("leia"))
                .unwrap()
                .count(),
            1
        );
    }

    #[test]
    fn reads_a_file_that_was_edited_by_hand() {
        let dir = TempDir::new();
        let file = dir.path().join("history");
        let lines: Vec<String> = ["/a", "", "/b", "/a"]
            .into_iter()
            .map(str::to_owned)
            .chain((0..20).map(|i| format!("/{i}")))
            .collect();
        fs::write(&file, lines.join("\r\n")).unwrap();

        let history = History::load(file);

        assert_eq!(history.paths()[..3], ["/a", "/b", "/0"]);
        assert_eq!(history.paths().len(), MAX_PATHS);
    }

    #[test]
    fn a_failed_write_keeps_the_paths() {
        let dir = TempDir::new();
        // A file where the directory should be.
        fs::write(dir.path().join("leia"), "").unwrap();
        let mut history = History::load(dir.path().join("leia").join("history"));

        history.add("/a");

        assert_eq!(history.paths(), ["/a"]);
    }

    #[cfg(not(windows))]
    #[test]
    fn uses_xdg_state_home_or_the_home_directory() {
        let home = Path::new("/home/me");
        assert_eq!(
            xdg_state_file(Some(OsStr::new("/state")), Some(home)),
            Some(PathBuf::from("/state/leia/history"))
        );
        assert_eq!(
            xdg_state_file(None, Some(home)),
            Some(PathBuf::from("/home/me/.local/state/leia/history"))
        );
        assert_eq!(
            xdg_state_file(Some(OsStr::new("relative")), Some(home)),
            Some(PathBuf::from("/home/me/.local/state/leia/history")),
            "a relative XDG_STATE_HOME is ignored"
        );
        assert_eq!(xdg_state_file(None, None), None);
    }

    #[cfg(windows)]
    #[test]
    fn uses_local_app_data_on_windows() {
        assert_eq!(
            local_app_data_file(Some(OsStr::new("C:\\Users\\me\\AppData\\Local"))),
            Some(PathBuf::from(
                "C:\\Users\\me\\AppData\\Local\\leia\\history"
            ))
        );
        assert_eq!(local_app_data_file(None), None);
    }
}
