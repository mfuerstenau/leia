//! Helpers shared by the unit tests.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::{env, fs, process};

use crate::protocol::filter::{FilterPattern, StartFilterParams};

/// The params of a filter for the pattern `red`.
pub fn filter_params(include_deleted: bool) -> StartFilterParams {
    StartFilterParams {
        query: None,
        pattern: Some(FilterPattern {
            pattern: "red".to_owned(),
            case_insensitive: false,
            fields: None,
        }),
        include_deleted,
    }
}

/// A fresh directory under the system temp directory, removed on drop.
pub struct TempDir(PathBuf);

impl TempDir {
    pub fn new() -> Self {
        static COUNTER: AtomicU32 = AtomicU32::new(0);
        let n = COUNTER.fetch_add(1, Ordering::Relaxed);
        let path = env::temp_dir().join(format!("leia-test-{}-{n}", process::id()));
        fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
