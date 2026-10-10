//! Method descriptors, mirroring `RpcMethod` and `Methods` in `leia-protocol`.

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::analysis::{Analyze, GetAnalysisComponents};
use super::check::{GetCheckIndex, StartCheckIndex};
use super::commits::{GetCommit, GetCommits};
use super::document::{
    GetDocument, GetReconstruct, GetStoredValue, GetTermVector, StartReconstruct,
};
use super::filter::{GetFilter, ReleaseFilter, StartFilter, StepFilter};
use super::index::{CloseIndex, GetIndexStats, OpenIndex, ReopenIndex};
use super::jars::{AddJars, GetJars};
use super::job::CancelJob;
use super::search::{
    GetExplain, GetSearch, GetStoredScan, StartExplain, StartSearch, StartStoredScan,
};
use super::terms::{
    GetExportTerms, GetFieldTermScan, GetPositions, GetPostings, GetTerms, StartExportTerms,
    StartFieldTermScan,
};
use super::version::Version;

/// One JSON-RPC method: its name and the DTOs of its params and result.
pub trait RpcMethod {
    const NAME: &'static str;
    type Params: Serialize + DeserializeOwned;
    type Result: Serialize + DeserializeOwned;
}

/// Names of all methods. Each one needs fixtures in `protocol/fixtures/`.
pub const METHOD_NAMES: &[&str] = &[
    Version::NAME,
    OpenIndex::NAME,
    ReopenIndex::NAME,
    GetIndexStats::NAME,
    CloseIndex::NAME,
    StartFieldTermScan::NAME,
    GetFieldTermScan::NAME,
    CancelJob::NAME,
    GetDocument::NAME,
    GetStoredValue::NAME,
    GetTermVector::NAME,
    StartReconstruct::NAME,
    GetReconstruct::NAME,
    GetTerms::NAME,
    GetPostings::NAME,
    GetPositions::NAME,
    GetCommits::NAME,
    GetCommit::NAME,
    StartSearch::NAME,
    GetSearch::NAME,
    StartExplain::NAME,
    GetExplain::NAME,
    StartStoredScan::NAME,
    GetStoredScan::NAME,
    StartFilter::NAME,
    GetFilter::NAME,
    StepFilter::NAME,
    ReleaseFilter::NAME,
    GetAnalysisComponents::NAME,
    Analyze::NAME,
    StartCheckIndex::NAME,
    GetCheckIndex::NAME,
    StartExportTerms::NAME,
    GetExportTerms::NAME,
    AddJars::NAME,
    GetJars::NAME,
];
