//! Searching the open index and explaining a hit (ADR 0010). Mirrors
//! `io.github.mfuerstenau.leia.protocol.search`.

use serde::{Deserialize, Serialize};

use super::document::{BytesValue, StoredValue};
use super::job::{JobParams, JobStarted};
use super::method::RpcMethod;

/// `startSearch`: starts a job that searches the open index and returns one page of hits with
/// their stored fields (ADR 0010). Fails with `NO_INDEX_OPEN`, `INVALID_QUERY` if the expression
/// doesn't parse, `FIELD_NOT_FOUND` for an unknown default field, term field or field to send, or
/// `INVALID_PARAMS` for a page out of range or a query without exactly one alternative.
pub enum StartSearch {}

impl RpcMethod for StartSearch {
    const NAME: &'static str = "startSearch";
    type Params = StartSearchParams;
    type Result = JobStarted;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartSearchParams {
    pub query: SearchQuery,
    /// How many of the best hits to skip; `offset + limit` is at most [`MAX_WINDOW`].
    pub offset: i32,
    /// The most hits the page has, from 1 to [`MAX_SEARCH_LIMIT`].
    pub limit: i32,
    /// Whether to count every hit; otherwise counting stops after 1000 hits.
    pub exact_total_hits: bool,
    /// The stored fields to send per hit; `None` for all of them.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fields: Option<Vec<String>>,
}

/// The largest `limit` of `startSearch` the server accepts.
pub const MAX_SEARCH_LIMIT: i32 = 1000;
/// The largest `offset + limit` of `startSearch` the server accepts.
pub const MAX_WINDOW: i32 = 10_000;

/// A query, as one of several alternatives: exactly one is present (ADR 0010).
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SearchQuery {
    /// An expression for Lucene's classic query parser.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub classic: Option<ClassicQuery>,
    /// A query for exactly one term.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub term: Option<TermQuery>,
}

/// An expression for Lucene's classic `QueryParser`, analyzed with `StandardAnalyzer`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClassicQuery {
    /// The query in the classic syntax, such as `title:fox AND body:qu*`.
    pub expression: String,
    /// The field of terms that name none.
    pub default_field: String,
    /// How clauses without an operator are combined.
    pub default_operator: QueryOperator,
}

/// How a query parser combines clauses without an operator, as Lucene's `QueryParser.Operator`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QueryOperator {
    Or,
    And,
}

/// A query for exactly one term, which is neither analyzed nor split.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TermQuery {
    pub field: String,
    pub term: BytesValue,
}

/// `getSearch`: the progress of a search, and its outcome once it is done. The outcome is handed
/// out once: a search that failed answers with its error (`INVALID_QUERY` for too many clauses),
/// and afterwards the job is gone (`JOB_NOT_FOUND`).
pub enum GetSearch {}

impl RpcMethod for GetSearch {
    const NAME: &'static str = "getSearch";
    type Params = JobParams;
    type Result = GetSearchResult;
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetSearchResult {
    /// The documents in the segments searched so far.
    pub docs_searched: i64,
    /// The outcome; `None` while the search is running.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub results: Option<SearchResults>,
}

/// One page of the hits of a search (ADR 0010).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SearchResults {
    /// The query as Lucene writes it.
    pub query: String,
    /// The query after rewriting, as Lucene writes it.
    pub rewritten_query: String,
    /// How many documents match.
    pub total_hits: i64,
    /// Whether `total_hits` is exact or a lower bound.
    pub total_hits_relation: TotalHitsRelation,
    /// The hits of the page, best first.
    pub hits: Vec<Hit>,
    /// How long the search took, in milliseconds.
    pub took_millis: i64,
}

/// The longest stored value, in bytes, that a hit has whole; longer text and bytes are cut.
pub const MAX_HIT_VALUE_LENGTH: usize = 256;

/// How a total hit count relates to the real one, as Lucene's `TotalHits.Relation`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TotalHitsRelation {
    /// The count is exact.
    EqualTo,
    /// Counting stopped early: at least this many documents match.
    GreaterThanOrEqualTo,
}

/// A matching document.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Hit {
    pub doc_id: i32,
    /// The document's score for the query: a Java `float`, which an `f64` holds exactly.
    pub score: f64,
    /// The document's stored values in the fields asked for, in the order of each field's first
    /// value; values longer than [`MAX_HIT_VALUE_LENGTH`] bytes are cut.
    pub fields: Vec<HitField>,
}

/// The stored values of a hit in one field.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HitField {
    pub name: String,
    /// The values, in the order they were added.
    pub values: Vec<StoredValue>,
}

/// `startExplain`: starts a job that explains how a document scores for a query, or why it
/// doesn't match (ADR 0010). Fails as `startSearch` does, and with `DOC_NOT_FOUND` for an id
/// outside 0 to maxDoc - 1.
pub enum StartExplain {}

impl RpcMethod for StartExplain {
    const NAME: &'static str = "startExplain";
    type Params = StartExplainParams;
    type Result = JobStarted;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartExplainParams {
    pub query: SearchQuery,
    /// The document to explain, from 0 to maxDoc - 1.
    pub doc_id: i32,
}

/// `getExplain`: the outcome of an explanation once it is done, handed out once as for
/// `getSearch`.
pub enum GetExplain {}

impl RpcMethod for GetExplain {
    const NAME: &'static str = "getExplain";
    type Params = JobParams;
    type Result = GetExplainResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetExplainResult {
    /// The outcome; `None` while the explanation is running.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub explanation: Option<Explanation>,
}

/// How a document scores for a query, or why it doesn't match: Lucene's `Explanation` tree.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Explanation {
    /// Whether the document matches this part of the query.
    #[serde(rename = "match")]
    pub matches: bool,
    /// The value of this part, such as a score or a document count, as Java's `toString` writes
    /// the number.
    pub value: String,
    /// What the value is.
    pub description: String,
    /// The parts the value is computed from.
    pub details: Vec<Explanation>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_query_has_only_its_alternative() {
        let query = SearchQuery {
            term: Some(TermQuery {
                field: "id".to_owned(),
                term: BytesValue {
                    value: "a:b".to_owned(),
                    binary: false,
                },
            }),
            ..SearchQuery::default()
        };
        assert_eq!(
            serde_json::to_string(&query).unwrap(),
            r#"{"term":{"field":"id","term":{"value":"a:b","binary":false}}}"#
        );
    }

    #[test]
    fn scores_keep_the_digits_of_a_float() {
        let json = r#"{"docId":3,"score":2.8514276,"fields":[]}"#;
        let hit: Hit = serde_json::from_str(json).unwrap();
        assert_eq!(serde_json::to_string(&hit).unwrap(), json);
    }

    #[test]
    fn running_jobs_have_no_outcome() {
        assert_eq!(
            serde_json::from_str::<GetSearchResult>(r#"{"docsSearched":200}"#).unwrap(),
            GetSearchResult {
                docs_searched: 200,
                results: None,
            }
        );
        assert_eq!(
            serde_json::from_str::<GetExplainResult>("{}").unwrap(),
            GetExplainResult { explanation: None }
        );
    }
}
