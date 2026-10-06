//! Long-running operations as polled server jobs (ADR 0005). Mirrors
//! `io.github.mfuerstenau.leia.protocol.job`.

use serde::{Deserialize, Serialize};

use super::method::RpcMethod;

/// Result of every `start<Op>` method: the job runs on in the server.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct JobStarted {
    /// The job, for `get<Op>` and `cancelJob`.
    pub job_id: i64,
}

/// Params of every `get<Op>` method and of `cancelJob`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct JobParams {
    /// The job, as returned by `start<Op>`.
    pub job_id: i64,
}

/// `cancelJob`: stops a job of any kind and forgets it. An unknown or finished job is not an
/// error; the result says whether a running job was stopped.
pub enum CancelJob {}

impl RpcMethod for CancelJob {
    const NAME: &'static str = "cancelJob";
    type Params = JobParams;
    type Result = CancelJobResult;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CancelJobResult {
    /// Whether a running job was stopped; false if the job had already finished or is unknown.
    pub cancelled: bool,
}
