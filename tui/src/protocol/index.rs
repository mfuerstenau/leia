//! Opening an index. Mirrors `io.github.mfuerstenau.leia.protocol.index`.

use serde::{Deserialize, Serialize};

use super::method::RpcMethod;

/// `openIndex`: opens the latest commit of an index, read-only, and makes it the open index. An
/// index that was open before is closed once the new one has opened; if opening fails, it stays
/// open.
pub enum OpenIndex {}

impl RpcMethod for OpenIndex {
    const NAME: &'static str = "openIndex";
    type Params = OpenIndexParams;
    type Result = OpenIndexResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OpenIndexParams {
    /// The index directory. A relative path is resolved against the server's working directory,
    /// which is the TUI's.
    pub path: String,
}

/// What identifies the opened index. The full statistics come from `getIndexStats`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OpenIndexResult {
    /// The absolute, normalized index directory.
    pub path: String,
    /// The `segments_N` file of the opened commit (the latest one).
    pub segments_file: String,
    /// Live documents.
    pub num_docs: i32,
    /// Documents including deleted ones.
    pub max_doc: i32,
    /// The major Lucene version that created the index.
    pub created_version_major: i32,
    /// The oldest Lucene version that wrote one of its segments, such as `9.12.3`; `None` if the
    /// index has no segments.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub min_segment_lucene_version: Option<String>,
}
