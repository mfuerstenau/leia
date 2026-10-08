//! The commits of the index directory and their segments (ADR 0009). Mirrors
//! `io.github.mfuerstenau.leia.protocol.commits`.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use super::method::RpcMethod;

/// `getCommits`: the commits of the open index's directory, newest first, read from the
/// directory on every call. Commits that can't be read are left out. Fails with
/// `NO_INDEX_OPEN`.
pub enum GetCommits {}

impl RpcMethod for GetCommits {
    const NAME: &'static str = "getCommits";
    type Params = GetCommitsParams;
    type Result = GetCommitsResult;
}

/// Params of `getCommits`: it lists the commits of an index directory of the open path.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetCommitsParams {
    /// The position of the index directory in `openIndex`' `indexes` (ADR 0021); `None` means 0.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub index: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetCommitsResult {
    /// The readable commits, newest first.
    pub commits: Vec<Commit>,
    /// The `segments_N` files that could not be read, and why (ADR 0021).
    pub unreadable: Vec<UnreadableCommit>,
}

/// A `segments_N` file of the index directory that could not be read (ADR 0021).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UnreadableCommit {
    /// Its name.
    pub segments_file: String,
    /// Why it could not be read.
    pub message: String,
}

/// A commit of the index directory, as `segments_N` describes it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Commit {
    /// The `N` in `segments_N`.
    pub generation: i64,
    /// The `segments_N` file.
    pub segments_file: String,
    /// Whether this is the commit the open index was opened on.
    pub current: bool,
    /// The version of the commit, which changes with every change to the index.
    pub version: i64,
    /// Segments in the commit.
    pub segment_count: i32,
    /// Documents in all segments, deleted ones included.
    pub max_doc: i32,
    /// Deleted documents in all segments, soft deletes not included.
    pub del_count: i32,
    /// The Lucene version that wrote the commit, such as `10.5.2`.
    pub lucene_version: String,
    /// The user data of the commit.
    pub user_data: BTreeMap<String, String>,
}

/// `getCommit`: one commit of the open index's directory whole: its segments with their details,
/// and its files. Fails with `NO_INDEX_OPEN`, `COMMIT_NOT_FOUND` if the directory has no
/// `segments_N` of that generation, `MISSING_CODEC` if it names a codec that isn't loaded, or
/// `UNSUPPORTED_INDEX_FORMAT`, `CORRUPT_INDEX` or `INDEX_NOT_READABLE` if the commit can't be
/// read.
pub enum GetCommit {}

impl RpcMethod for GetCommit {
    const NAME: &'static str = "getCommit";
    type Params = GetCommitParams;
    type Result = GetCommitResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetCommitParams {
    /// The generation of the commit, the `N` in `segments_N`.
    pub generation: i64,
    /// The position of the index directory in `openIndex`' `indexes` (ADR 0021); `None` means 0.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub index: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetCommitResult {
    /// The commit, as `getCommits` lists it.
    pub commit: Commit,
    /// The segments in commit order, which is the order of their document ids.
    pub segments: Vec<Segment>,
    /// Every file of the commit, `segments_N` included, sorted by name.
    pub files: Vec<CommitFile>,
}

/// A segment of a commit.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Segment {
    /// The segment name, such as `_0`.
    pub name: String,
    /// The segment's unique id in hex, as Lucene prints it.
    pub id: String,
    /// Documents in the segment, deleted ones included.
    pub max_doc: i32,
    /// The document id of the segment's first document in this commit.
    pub doc_base: i32,
    /// Deleted documents.
    pub del_count: i32,
    /// Soft-deleted documents.
    pub soft_del_count: i32,
    /// The generation of the live docs file; `None` if there were no deletions.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub del_gen: Option<i64>,
    /// The generation of the field infos update; `None` if there is none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub field_infos_gen: Option<i64>,
    /// The generation of the doc values updates; `None` if there are none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub doc_values_gen: Option<i64>,
    /// The Lucene version that wrote the segment.
    pub lucene_version: String,
    /// The oldest Lucene version that wrote a part of it; `None` if unknown.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub min_lucene_version: Option<String>,
    /// The codec name, such as `Lucene104`.
    pub codec: String,
    /// The formats of the codec.
    pub formats: CodecFormats,
    /// Whether the segment is in a compound file.
    pub compound: bool,
    /// Whether the segment has document blocks (parent and child documents).
    pub has_blocks: bool,
    /// The sort of the segment's documents; `None` if it is not sorted.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub index_sort: Option<String>,
    /// The size of the segment's files; `None` if one of them can't be stat'ed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub size_in_bytes: Option<i64>,
    /// How the segment came to be: source, Lucene version, OS, Java, ...
    pub diagnostics: BTreeMap<String, String>,
    /// The codec's attributes.
    pub attributes: BTreeMap<String, String>,
}

/// The formats of a segment's codec: the name of a named format (postings, doc values, KNN
/// vectors), otherwise the simple class name.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CodecFormats {
    pub postings: String,
    pub doc_values: String,
    pub stored_fields: String,
    pub term_vectors: String,
    pub norms: String,
    pub points: String,
    pub knn_vectors: String,
    pub field_infos: String,
    pub segment_info: String,
    pub live_docs: String,
    pub compound: String,
}

/// A file of a commit.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CommitFile {
    /// The file name.
    pub name: String,
    /// The segment it belongs to; `None` for `segments_N`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub segment: Option<String>,
    /// The size in bytes; `None` if the file can't be stat'ed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub size: Option<i64>,
}
