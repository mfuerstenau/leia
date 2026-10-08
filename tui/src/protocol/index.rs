//! Opening an index and its statistics. Mirrors `io.github.mfuerstenau.leia.protocol.index`.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use super::method::RpcMethod;

/// `openIndex`: opens the latest commit of an index, or of every index below a directory (ADR
/// 0021), read-only, and makes it the open index. It may open without a reader, for indexes that a
/// reader can't open. An index that was open before is closed once the new one has opened; if
/// opening fails, it stays open.
pub enum OpenIndex {}

impl RpcMethod for OpenIndex {
    const NAME: &'static str = "openIndex";
    type Params = OpenIndexParams;
    type Result = OpenIndexResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OpenIndexParams {
    /// The index directory, or a directory with indexes below it (ADR 0021). A relative path is
    /// resolved against the server's working directory, which is the TUI's.
    pub path: String,
    /// The `Directory` to read it through (ADR 0020); `None` means [`DirectoryImpl::Fs`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub directory_impl: Option<DirectoryImpl>,
    /// How to open it (ADR 0021); `None` means [`OpenMode::Reader`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mode: Option<OpenMode>,
}

/// The Lucene `Directory` an index is read through (ADR 0020). Each opens without locking.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DirectoryImpl {
    /// `FSDirectory.open`: Lucene's choice for the platform, `MMapDirectory` on 64-bit.
    #[default]
    Fs,
    /// `MMapDirectory`.
    Mmap,
    /// `NIOFSDirectory`.
    Nio,
}

/// How `openIndex` opens the indexes of a path (ADR 0021).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum OpenMode {
    /// With a reader: everything can be read.
    #[default]
    Reader,
    /// Only the `Directory`, for indexes that a reader can't open: only the commits can be read.
    DirectoryOnly,
}

/// What identifies the opened indexes (ADR 0021). The full statistics come from `getIndexStats`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OpenIndexResult {
    /// The absolute, normalized path that was opened.
    pub path: String,
    /// How it was opened.
    pub mode: OpenMode,
    /// Live documents in all indexes; `None` without a reader.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub num_docs: Option<i32>,
    /// Documents in all indexes, including deleted ones; `None` without a reader.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_doc: Option<i32>,
    /// The index directories that were opened, in order; at least one.
    pub indexes: Vec<IndexEntry>,
    /// The index directories that were found but left out.
    pub skipped: Vec<SkippedIndex>,
}

/// One index directory of an opened path (ADR 0021).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct IndexEntry {
    /// The absolute, normalized index directory.
    pub path: String,
    /// What its reader opened; `None` if the path was opened without a reader.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reader: Option<ReaderInfo>,
}

/// What the reader of an index directory opened.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReaderInfo {
    /// The document id of its first document in the opened path; 0 for the first index.
    pub doc_base: i32,
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

/// An index directory that was found below the opened path but left out (ADR 0021): it failed to
/// open, or its fields conflict with those of the indexes before it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SkippedIndex {
    /// The absolute, normalized index directory.
    pub path: String,
    /// Why it was left out.
    pub message: String,
}

/// `reopenIndex`: opens the latest commit of each open index again, if it differs from the open
/// one (ADR 0019, ADR 0021). The readers that didn't change are shared. If one changed, the new
/// commits become the open index, and the jobs of the old one are stopped; if not, nothing
/// happens. Fails with `NO_INDEX_OPEN`, `NO_READER`, or with the errors of `openIndex`; the open
/// index then stays open.
pub enum ReopenIndex {}

impl RpcMethod for ReopenIndex {
    const NAME: &'static str = "reopenIndex";
    type Params = ReopenIndexParams;
    type Result = ReopenIndexResult;
}

/// Params of `reopenIndex`: none, it reopens the open index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReopenIndexParams {}

/// Whether an index changed, and what identifies the indexes now, as in [`OpenIndexResult`]. The
/// full statistics come from `getIndexStats`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReopenIndexResult {
    /// Whether the latest commit of an index differs from the one that was open; the indexes are
    /// then replaced. If not, nothing happened.
    pub changed: bool,
    /// The absolute, normalized path that was opened.
    pub path: String,
    /// How it was opened, always [`OpenMode::Reader`].
    pub mode: OpenMode,
    /// Live documents in all indexes.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub num_docs: Option<i32>,
    /// Documents in all indexes, including deleted ones.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_doc: Option<i32>,
    /// The index directories that are open, in order; at least one.
    pub indexes: Vec<IndexEntry>,
    /// The index directories that were left out when the path was opened.
    pub skipped: Vec<SkippedIndex>,
}

/// `closeIndex`: closes the open index, if any. Closing when no index is open is not an error;
/// the result then has no path.
pub enum CloseIndex {}

impl RpcMethod for CloseIndex {
    const NAME: &'static str = "closeIndex";
    type Params = CloseIndexParams;
    type Result = CloseIndexResult;
}

/// Params of `closeIndex`: none, it closes the open index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CloseIndexParams {}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CloseIndexResult {
    /// The directory of the index that was closed; `None` if no index was open.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
}

/// `getIndexStats`: statistics of the open index that are cheap to compute: the commit and every
/// field, without iterating over terms. Fails with `NO_INDEX_OPEN` if no index is open.
pub enum GetIndexStats {}

impl RpcMethod for GetIndexStats {
    const NAME: &'static str = "getIndexStats";
    type Params = GetIndexStatsParams;
    type Result = GetIndexStatsResult;
}

/// Params of `getIndexStats`: none, it describes the open index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetIndexStatsParams {}

/// Statistics of the open indexes (ADR 0021). Nothing here iterates over terms or documents, so
/// the number of terms is not included. The counts and fields cover all indexes; what belongs to a
/// commit is per index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetIndexStatsResult {
    /// The absolute, normalized path that was opened.
    pub path: String,
    /// The class of the Lucene `Directory`, such as `MMapDirectory`.
    pub directory_impl: String,
    /// Segments in all opened commits.
    pub segment_count: i32,
    /// Live documents.
    pub num_docs: i32,
    /// Documents including deleted ones.
    pub max_doc: i32,
    /// Each index directory with its opened commit, in order; at least one.
    pub indexes: Vec<IndexCommitStats>,
    /// Every field, merged over the indexes, ordered by field number.
    pub fields: Vec<IndexField>,
}

/// An index directory of the open path, and its opened commit (ADR 0021).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct IndexCommitStats {
    /// The absolute, normalized index directory.
    pub path: String,
    /// The document id of its first document in the opened path; 0 for the first index.
    pub doc_base: i32,
    /// The `segments_N` file of the opened commit.
    pub segments_file: String,
    /// The generation of that commit, the `N` in `segments_N`.
    pub generation: i64,
    /// The version of that commit, which changes with every change to the index.
    pub version: i64,
    /// Segments in the commit; an index with one segment is fully merged.
    pub segment_count: i32,
    /// Live documents.
    pub num_docs: i32,
    /// Documents including deleted ones.
    pub max_doc: i32,
    /// The major Lucene version that created the index.
    pub created_version_major: i32,
    /// The Lucene version that wrote the commit, such as `10.5.2`.
    pub commit_lucene_version: String,
    /// The oldest Lucene version that wrote one of its segments; `None` if the index has no
    /// segments.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub min_segment_lucene_version: Option<String>,
    /// The user data of the commit.
    pub user_data: BTreeMap<String, String>,
}

/// One field of an index: how it is indexed, and its postings statistics.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct IndexField {
    pub name: String,
    pub number: i32,
    /// What the postings hold; `None` if the field is not indexed.
    pub index_options: IndexOptions,
    /// Whether norms are stored.
    pub has_norms: bool,
    /// Whether any position has a payload.
    pub has_payloads: bool,
    /// Whether term vectors are stored.
    pub has_term_vectors: bool,
    /// The doc values type; `None` if there are none.
    pub doc_values_type: DocValuesType,
    /// Dimensions of the point values; 0 if there are none.
    pub point_dimension_count: i32,
    /// Bytes per point dimension; 0 if there are none.
    pub point_num_bytes: i32,
    /// Dimensions of the KNN vectors; 0 if there are none.
    pub vector_dimension: i32,
    /// Postings statistics; `None` if no segment has postings for the field.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub terms: Option<TermStats>,
}

/// Postings statistics of a field, summed over all segments. They include deleted documents.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TermStats {
    /// Documents that have at least one term in the field.
    pub doc_count: i32,
    /// The sum of the document frequencies of all terms.
    pub sum_doc_freq: i64,
    /// The sum of the term frequencies of all terms; equal to `sum_doc_freq` if frequencies are
    /// not indexed.
    pub sum_total_term_freq: i64,
}

/// What the postings of a field hold, as Lucene's `IndexOptions`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum IndexOptions {
    None,
    Docs,
    DocsAndFreqs,
    DocsAndFreqsAndPositions,
    DocsAndFreqsAndPositionsAndOffsets,
    /// Like `DocsAndFreqs`, but the frequencies are custom values such as scores, not term counts.
    DocsAndCustomFreqs,
}

/// The doc values type of a field, as Lucene's `DocValuesType`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DocValuesType {
    None,
    Numeric,
    Binary,
    Sorted,
    SortedNumeric,
    SortedSet,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enums_use_lucenes_names() {
        assert_eq!(
            serde_json::to_string(&IndexOptions::DocsAndFreqsAndPositionsAndOffsets).unwrap(),
            r#""DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS""#
        );
        assert_eq!(
            serde_json::to_string(&DocValuesType::SortedNumeric).unwrap(),
            r#""SORTED_NUMERIC""#
        );
    }

    #[test]
    fn rejects_unknown_or_numeric_enum_value() {
        assert!(serde_json::from_str::<IndexOptions>(r#""POSITIONS""#).is_err());
        assert!(serde_json::from_str::<IndexOptions>("1").is_err());
    }

    #[test]
    fn close_result_without_index_is_empty() {
        let none = CloseIndexResult { path: None };
        assert_eq!(serde_json::to_string(&none).unwrap(), "{}");
        assert_eq!(
            serde_json::from_str::<CloseIndexResult>("{}").unwrap(),
            none
        );
    }
}
