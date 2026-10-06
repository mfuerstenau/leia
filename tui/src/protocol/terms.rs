//! Scanning the terms of a field. Mirrors `io.github.mfuerstenau.leia.protocol.terms`.

use serde::{Deserialize, Serialize};

use super::job::{JobParams, JobStarted};
use super::method::RpcMethod;

/// `startFieldTermScan`: starts a job that scans the terms of a field of the open index: counts
/// them and finds the `top_n` with the highest document frequency (ADR 0005). Fails with
/// `NO_INDEX_OPEN`, `FIELD_NOT_FOUND`, or `INVALID_PARAMS` for a `top_n` out of range.
pub enum StartFieldTermScan {}

impl RpcMethod for StartFieldTermScan {
    const NAME: &'static str = "startFieldTermScan";
    type Params = StartFieldTermScanParams;
    type Result = JobStarted;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartFieldTermScanParams {
    /// The field to scan.
    pub field: String,
    /// How many of the terms with the highest document frequency to return, from 0 to
    /// [`MAX_TOP_N`]; 0 only counts the terms.
    pub top_n: i32,
}

/// The largest `top_n` the server accepts.
pub const MAX_TOP_N: i32 = 1000;

/// `getFieldTermScan`: the progress of a field term scan, and its outcome once it is done. The
/// outcome is handed out once: a scan that failed answers with its error, and afterwards the job
/// is gone (`JOB_NOT_FOUND`).
pub enum GetFieldTermScan {}

impl RpcMethod for GetFieldTermScan {
    const NAME: &'static str = "getFieldTermScan";
    type Params = JobParams;
    type Result = GetFieldTermScanResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetFieldTermScanResult {
    /// The terms scanned so far.
    pub terms_scanned: i64,
    /// The outcome; `None` while the scan is running.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub terms: Option<FieldTerms>,
}

/// The terms of one field, from a full scan of its postings. Like the postings statistics, the
/// counts include deleted documents.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FieldTerms {
    pub field: String,
    /// The number of distinct terms in the field.
    pub unique_terms: i64,
    /// The terms with the highest document frequency, highest first; ties in term order.
    pub top_terms: Vec<TopTerm>,
}

/// One of the top terms of a field.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TopTerm {
    /// The term as text; if `binary`, its bytes in lowercase hex instead.
    pub term: String,
    /// Whether the term's bytes are not valid UTF-8, so `term` holds them as hex.
    pub binary: bool,
    /// Documents that contain the term.
    pub doc_freq: i32,
    /// Occurrences of the term in all documents; equal to `doc_freq` if frequencies are not
    /// indexed.
    pub total_term_freq: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn running_scan_has_no_terms() {
        let running = GetFieldTermScanResult {
            terms_scanned: 2048,
            terms: None,
        };
        assert_eq!(
            serde_json::to_string(&running).unwrap(),
            r#"{"termsScanned":2048}"#
        );
        assert_eq!(
            serde_json::from_str::<GetFieldTermScanResult>(r#"{"termsScanned":2048}"#).unwrap(),
            running
        );
    }

    #[test]
    fn top_n_is_camel_case() {
        let params = StartFieldTermScanParams {
            field: "body".to_owned(),
            top_n: 50,
        };
        assert_eq!(
            serde_json::to_string(&params).unwrap(),
            r#"{"field":"body","topN":50}"#
        );
    }
}
