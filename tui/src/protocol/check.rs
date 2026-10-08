//! Checking the indexes of the open path with Lucene's `CheckIndex` (ADR 0022). Mirrors
//! `io.github.mfuerstenau.leia.protocol.check`.

use serde::{Deserialize, Serialize};

use super::job::JobStarted;
use super::method::RpcMethod;

/// `startCheckIndex`: starts a job that checks every index of the open path with Lucene's
/// `CheckIndex`, with or without a reader: the opened indexes, then the skipped ones. Nothing is
/// written. Fails with `NO_INDEX_OPEN`, or `INVALID_PARAMS` for a level out of range.
pub enum StartCheckIndex {}

impl RpcMethod for StartCheckIndex {
    const NAME: &'static str = "startCheckIndex";
    type Params = StartCheckIndexParams;
    type Result = JobStarted;
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartCheckIndexParams {
    /// How thoroughly to check, from [`MIN_LEVEL`] to [`MAX_LEVEL`]: 1 the checksums of every
    /// file, 2 also the integrity of every part of each segment, 3 also the slow checks; `None`
    /// means [`DEFAULT_LEVEL`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub level: Option<i32>,
}

pub const MIN_LEVEL: i32 = 1;
pub const MAX_LEVEL: i32 = 3;
/// Lucene's default: the checksums.
pub const DEFAULT_LEVEL: i32 = MIN_LEVEL;

/// `getCheckIndex`: the output of a check from a given line on, and its outcome once it is done.
/// The outcome is handed out once, with the last lines; afterwards the job is gone
/// (`JOB_NOT_FOUND`). Fails with `INVALID_PARAMS` for a negative `from_line`.
pub enum GetCheckIndex {}

impl RpcMethod for GetCheckIndex {
    const NAME: &'static str = "getCheckIndex";
    type Params = GetCheckIndexParams;
    type Result = GetCheckIndexResult;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetCheckIndexParams {
    /// The job, as returned by `startCheckIndex`.
    pub job_id: i64,
    /// The first line of the output to return: the number of lines the caller has.
    pub from_line: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetCheckIndexResult {
    /// The lines of output so far.
    pub line_count: i32,
    /// The lines from `from_line` on; all of them once the outcome is there.
    pub lines: Vec<String>,
    /// The outcome; `None` while the check is running.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<CheckIndexResult>,
}

/// The outcome of a check of every index of the open path.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CheckIndexResult {
    /// Each index checked: the opened ones in order, then the skipped ones.
    pub indexes: Vec<IndexCheck>,
    /// How long the whole check took.
    pub took_millis: i64,
}

/// The outcome of checking one index directory: its latest commit.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct IndexCheck {
    /// The index directory.
    pub path: String,
    /// Whether it was skipped when the path was opened (ADR 0021).
    pub skipped: bool,
    /// Whether no problem was found.
    pub clean: bool,
    /// Why it could not be checked at all, such as no `segments_N`; then the other fields say
    /// nothing.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// The `segments_N` file of the latest commit, if it could be read.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub segments_file: Option<String>,
    /// Whether the latest commit, or an older one, could not be read.
    pub missing_segments: bool,
    /// Whether the index was written by a newer Lucene than the server's.
    pub tool_out_of_date: bool,
    /// The segments of the latest commit.
    pub segment_count: i32,
    /// The segments that failed a test.
    pub bad_segments: i32,
    /// The documents in the bad segments.
    pub lost_docs: i32,
    /// Whether the commit's counter for segment names is above every segment's name.
    pub valid_counter: bool,
    /// Each segment of the latest commit, in commit order.
    pub segments: Vec<SegmentCheck>,
}

/// The outcome of checking one segment.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SegmentCheck {
    /// The segment's name, such as `_0`.
    pub name: String,
    /// Its documents, deleted ones included.
    pub max_doc: i32,
    /// Its codec, if its `.si` file could be read.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub codec: Option<String>,
    /// Whether it is a compound file.
    pub compound: bool,
    /// The files it consists of.
    pub files: i32,
    /// The size of those files.
    pub size_bytes: i64,
    /// Whether it has deleted documents.
    pub has_deletions: bool,
    /// Whether a reader could open it.
    pub open_reader_passed: bool,
    /// The test it failed and why, with the causes; `None` if it passed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}
