//! Opening an index and its statistics. Mirrors `io.github.mfuerstenau.leia.protocol.index`.

use std::collections::BTreeMap;

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

/// Statistics of the open index. Nothing here iterates over terms or documents, so the number of
/// terms is not included.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetIndexStatsResult {
    /// The absolute, normalized index directory.
    pub path: String,
    /// The class of the Lucene `Directory`, such as `MMapDirectory`.
    pub directory_impl: String,
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
    /// Every field in the index, ordered by field number.
    pub fields: Vec<IndexField>,
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
}
