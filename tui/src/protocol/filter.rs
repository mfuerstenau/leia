//! Browsing the documents through the matches of a query (ADR 0031). Mirrors
//! `io.github.mfuerstenau.leia.protocol.filter`.

use serde::{Deserialize, Serialize};

use super::job::{JobParams, JobStarted};
use super::method::RpcMethod;
use super::search::SearchQuery;

/// `startFilter`: starts a job that finds every document of the open index that matches a query,
/// without scoring, to browse the documents through. Fails as `startSearch` does for the query.
pub enum StartFilter {}

impl RpcMethod for StartFilter {
    const NAME: &'static str = "startFilter";
    type Params = StartFilterParams;
    type Result = JobStarted;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartFilterParams {
    /// The query, as for `startSearch`.
    pub query: SearchQuery,
    /// Whether deleted documents match too.
    pub include_deleted: bool,
}

/// `getFilter`: the progress of building a filter, and the filter once it is built. The server
/// keeps the filter for `stepFilter`, in place of the one it kept before; the job is gone as for
/// every job (`JOB_NOT_FOUND`).
pub enum GetFilter {}

impl RpcMethod for GetFilter {
    const NAME: &'static str = "getFilter";
    type Params = JobParams;
    type Result = GetFilterResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetFilterResult {
    /// The documents in the segments searched so far.
    pub docs_searched: i64,
    /// The filter; `None` while the matches are being found.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<FilterResult>,
}

/// A filter that the server built and keeps, for `stepFilter` and `releaseFilter`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FilterResult {
    /// The filter; the server keeps one, so a newer filter replaces it.
    pub filter_id: i64,
    /// How many documents match.
    pub match_count: i32,
    /// The query as Lucene writes it.
    pub query: String,
    /// The query after rewriting, as Lucene writes it.
    pub rewritten_query: String,
    /// How long finding the matches took.
    pub took_millis: i64,
}

/// `stepFilter`: the first document of a filter from a document id on, or the last one up to it,
/// and how many matches come before it. Fails with `FILTER_NOT_FOUND` if the server doesn't keep
/// the filter: it was released or replaced, or its index was closed or reopened.
pub enum StepFilter {}

impl RpcMethod for StepFilter {
    const NAME: &'static str = "stepFilter";
    type Params = StepFilterParams;
    type Result = StepFilterResult;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StepFilterParams {
    /// The filter, as `getFilter` returned it.
    pub filter_id: i64,
    /// The document id to look from; it counts as a match itself. Any number is taken.
    pub from_doc: i32,
    /// Whether to look for the first match from `from_doc` on, or the last one up to it.
    pub forward: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StepFilterResult {
    /// The matching document found; `None` if there is none in that direction.
    #[serde(rename = "match", skip_serializing_if = "Option::is_none")]
    pub found: Option<FilterMatch>,
}

/// A document that matches a filter.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FilterMatch {
    pub doc_id: i32,
    /// How many matching documents come before it.
    pub index: i32,
}

/// `releaseFilter`: forgets a filter and frees its memory. An unknown filter is not an error; the
/// result says whether the server kept it.
pub enum ReleaseFilter {}

impl RpcMethod for ReleaseFilter {
    const NAME: &'static str = "releaseFilter";
    type Params = ReleaseFilterParams;
    type Result = ReleaseFilterResult;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReleaseFilterParams {
    /// The filter, as `getFilter` returned it.
    pub filter_id: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReleaseFilterResult {
    /// Whether the server kept the filter until now.
    pub released: bool,
}
