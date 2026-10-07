//! Searching the open index and explaining a hit (ADR 0010 to ADR 0014). Mirrors
//! `io.github.mfuerstenau.leia.protocol.search`.

use serde::{Deserialize, Serialize};

use super::analysis::Analyzer;
use super::document::{BytesValue, StoredValue};
use super::job::{JobParams, JobStarted};
use super::method::RpcMethod;

/// `startSearch`: starts a job that searches the open index and returns one page of hits with
/// their stored fields (ADR 0010 to ADR 0014). Fails with `NO_INDEX_OPEN`, `INVALID_QUERY` if the
/// expression doesn't parse, `INVALID_ANALYZER` if the query's analyzer can't be built,
/// `FIELD_NOT_FOUND` for an unknown default field, term field, point field, More-Like-This field,
/// sort field or field to send, `DOC_NOT_FOUND` for a More-Like-This document outside 0 to
/// maxDoc - 1, or `INVALID_PARAMS` for a page out of range, a query, analyzer or similarity without
/// exactly one alternative, a parser option, More-Like-This setting or BM25 parameter out of range,
/// a point type that doesn't fit its field, a More-Like-This field that isn't indexed, or a sort
/// field without doc values of the sort type.
pub enum StartSearch {}

impl RpcMethod for StartSearch {
    const NAME: &'static str = "startSearch";
    type Params = StartSearchParams;
    type Result = JobStarted;
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartSearchParams {
    pub query: SearchQuery,
    /// What scores the hits.
    pub similarity: Similarity,
    /// The order of the hits, one key after the other; empty for the best score first.
    pub sort: Vec<SortKey>,
    /// How many of the first hits to skip; `offset + limit` is at most [`MAX_WINDOW`].
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

/// A query, as one of several alternatives: exactly one is present (ADR 0010, ADR 0011, ADR 0013).
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SearchQuery {
    /// An expression for Lucene's classic query parser.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub classic: Option<ClassicQuery>,
    /// An expression for Lucene's standard query parser, which knows point fields.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub standard: Option<StandardQuery>,
    /// A query for exactly one term.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub term: Option<TermQuery>,
    /// Documents like one document of the index.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub more_like_this: Option<MoreLikeThisQuery>,
}

/// Documents like one document of the index, by the terms that Lucene's `MoreLikeThis` picks from
/// it (ADR 0013). A field's terms come from the document's term vector, or else from its stored
/// strings, analyzed with `analyzer` (ADR 0014).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MoreLikeThisQuery {
    /// The source document, from 0 to maxDoc - 1; it may be deleted.
    pub doc_id: i32,
    /// The indexed fields to take terms from, at least one and none twice.
    pub fields: Vec<String>,
    /// Terms that occur fewer times in the document are left out (Lucene: 2).
    pub min_term_freq: i32,
    /// Terms that occur in fewer documents are left out (Lucene: 5).
    pub min_doc_freq: i32,
    /// Terms that occur in more documents are left out; `None` for no limit.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_doc_freq: Option<i32>,
    /// How many of the best terms the query has, from 1 to [`MAX_QUERY_TERMS`] (Lucene: 25).
    pub max_query_terms: i32,
    /// Whether each term is boosted by its score, relative to the weakest term's.
    pub boost: bool,
    /// Analyzes the stored strings of fields without a term vector.
    pub analyzer: Analyzer,
}

/// The largest `max_query_terms` the server accepts: Lucene's maximum clause count.
pub const MAX_QUERY_TERMS: i32 = 1024;

/// What scores the hits, as one of several alternatives: exactly one is present (ADR 0012).
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Similarity {
    /// Lucene's default, `BM25Similarity`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bm25: Option<Bm25Similarity>,
    /// TF-IDF, `ClassicSimilarity`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub classic: Option<ClassicSimilarity>,
    /// `BooleanSimilarity`: a matching term scores its boost.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub boolean: Option<BooleanSimilarity>,
}

/// `BM25Similarity` with its parameters: Java `float`s, which the server reads from the shortest
/// decimal of an `f64` as it would from an `f32`'s.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Bm25Similarity {
    /// How quickly a term's frequency saturates, finite and 0 or more (Lucene: 1.2).
    pub k1: f64,
    /// How much the field length normalizes, from 0 to 1 (Lucene: 0.75).
    pub b: f64,
}

/// `ClassicSimilarity`, TF-IDF; it has no parameters.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ClassicSimilarity {}

/// `BooleanSimilarity`: a matching term scores its boost; it has no parameters.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BooleanSimilarity {}

/// One level of the order of the hits (ADR 0012).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SortKey {
    /// A field with doc values other than `BINARY`.
    pub field: String,
    #[serde(rename = "type")]
    pub sort_type: SortType,
    /// Whether the largest value comes first.
    pub reverse: bool,
}

/// How a field's doc values are read to sort by them: numeric doc values as `Int`, `Long`,
/// `Float` or `Double`, sorted and sorted set doc values as `String`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SortType {
    Int,
    Long,
    Float,
    Double,
    String,
}

/// An expression for Lucene's classic `QueryParser` (ADR 0010, ADR 0011, ADR 0014).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClassicQuery {
    /// The query in the classic syntax, such as `title:fox AND body:qu*`.
    pub expression: String,
    /// The field of terms that name none.
    pub default_field: String,
    /// How clauses without an operator are combined.
    pub default_operator: QueryOperator,
    pub options: ParserOptions,
    /// The edit distance of fuzzy terms that give none, 0 to [`MAX_FUZZY_EDITS`].
    pub fuzzy_max_edits: i32,
    /// Whether whitespace splits the text before it is analyzed.
    pub split_on_whitespace: bool,
    /// Whether text that analyzes to several terms becomes a phrase; needs
    /// `split_on_whitespace`.
    pub auto_generate_phrase_queries: bool,
    /// Analyzes the text of the expression.
    pub analyzer: Analyzer,
}

/// The largest `fuzzy_max_edits` the server accepts, Lucene's largest fuzzy edit distance.
pub const MAX_FUZZY_EDITS: i32 = 2;

/// An expression for Lucene's standard (flexible) `StandardQueryParser` (ADR 0011, ADR 0014).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StandardQuery {
    /// The query in the standard syntax, such as `price:[10 TO 20] AND fox`.
    pub expression: String,
    /// The field of terms that name none.
    pub default_field: String,
    /// How clauses without an operator are combined.
    pub default_operator: QueryOperator,
    pub options: ParserOptions,
    /// The point fields whose ranges and values become point queries, each with the type of its
    /// values.
    pub points: Vec<PointField>,
    /// Analyzes the text of the expression.
    pub analyzer: Analyzer,
}

/// The options that the classic and the standard query parser share (ADR 0011).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ParserOptions {
    /// Whether a term may start with `*` or `?`.
    pub allow_leading_wildcard: bool,
    /// The slop of phrases that give none, 0 or more.
    pub phrase_slop: i32,
    /// How many leading characters of a fuzzy term must match exactly, 0 or more.
    pub fuzzy_prefix_length: i32,
    /// Whether phrases keep the gaps that the analyzer leaves.
    pub enable_position_increments: bool,
}

impl Default for ParserOptions {
    /// Lucene's defaults.
    fn default() -> Self {
        Self {
            allow_leading_wildcard: false,
            phrase_slop: 0,
            fuzzy_prefix_length: 0,
            enable_position_increments: true,
        }
    }
}

/// A point field for the standard query parser, with the type of its values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PointField {
    /// A field with one-dimensional points as wide as `type`.
    pub field: String,
    #[serde(rename = "type")]
    pub point_type: PointType,
}

/// The type of a point field's values, which the index doesn't record.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PointType {
    Int,
    Long,
    Float,
    Double,
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
    /// The hits of the page, best first or in the order of the sort.
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
    /// The document's score for the query: a Java `float`, which an `f64` holds exactly. Sorted
    /// hits have their scores too.
    pub score: f64,
    /// The values the hit was sorted by, one per sort key; empty without a sort.
    pub sort_values: Vec<SortValue>,
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

/// The value a hit was sorted by in one sort key (ADR 0012). At most one is present; neither is
/// for a document without a value in a `String` sort.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SortValue {
    /// The number of a numeric sort, as Java writes it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub number: Option<String>,
    /// The term of a `String` sort, as text or hex.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub term: Option<BytesValue>,
}

/// `startExplain`: starts a job that explains how a document scores for a query with a
/// similarity, or why it doesn't match (ADR 0010, ADR 0012). Fails as `startSearch` does, and with `DOC_NOT_FOUND` for an id
/// outside 0 to maxDoc - 1.
pub enum StartExplain {}

impl RpcMethod for StartExplain {
    const NAME: &'static str = "startExplain";
    type Params = StartExplainParams;
    type Result = JobStarted;
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartExplainParams {
    pub query: SearchQuery,
    /// What scores the document: the search's, for the explanation to match its score.
    pub similarity: Similarity,
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
        let json = r#"{"docId":3,"score":2.8514276,"sortValues":[],"fields":[]}"#;
        let hit: Hit = serde_json::from_str(json).unwrap();
        assert_eq!(serde_json::to_string(&hit).unwrap(), json);
    }

    #[test]
    fn similarities_have_only_their_alternative() {
        let bm25 = Similarity {
            bm25: Some(Bm25Similarity { k1: 1.2, b: 0.75 }),
            ..Similarity::default()
        };
        assert_eq!(
            serde_json::to_string(&bm25).unwrap(),
            r#"{"bm25":{"k1":1.2,"b":0.75}}"#
        );
        let boolean = Similarity {
            boolean: Some(BooleanSimilarity {}),
            ..Similarity::default()
        };
        assert_eq!(
            serde_json::to_string(&boolean).unwrap(),
            r#"{"boolean":{}}"#
        );
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
