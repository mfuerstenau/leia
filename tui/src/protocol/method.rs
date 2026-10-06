//! Method descriptors, mirroring `RpcMethod` and `Methods` in `leia-protocol`.

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::document::{GetDocument, GetTermVector};
use super::index::{CloseIndex, GetIndexStats, OpenIndex};
use super::job::CancelJob;
use super::terms::{GetFieldTermScan, GetPositions, GetPostings, GetTerms, StartFieldTermScan};
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
    GetIndexStats::NAME,
    CloseIndex::NAME,
    StartFieldTermScan::NAME,
    GetFieldTermScan::NAME,
    CancelJob::NAME,
    GetDocument::NAME,
    GetTermVector::NAME,
    GetTerms::NAME,
    GetPostings::NAME,
    GetPositions::NAME,
];
