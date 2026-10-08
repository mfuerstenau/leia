//! Scanning the terms of a field, and paging through its terms and their postings. Mirrors
//! `io.github.mfuerstenau.leia.protocol.terms`.

use serde::{Deserialize, Serialize};

use super::document::{BytesValue, TermPosition};
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

/// `getTerms`: a page of the terms of a field, in term order, from a given term on (ADR 0007).
/// A field without postings has none. Fails with `NO_INDEX_OPEN`, `FIELD_NOT_FOUND`, or
/// `INVALID_PARAMS` for a `limit` out of range or a `from` that is not valid hex.
pub enum GetTerms {}

impl RpcMethod for GetTerms {
    const NAME: &'static str = "getTerms";
    type Params = GetTermsParams;
    type Result = GetTermsResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetTermsParams {
    pub field: String,
    /// The first term of the page, or else the term after it; `None` for the first term of the
    /// field. A `next` from an earlier page continues where that page ended.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub from: Option<BytesValue>,
    /// The most terms the page has, from 1 to [`MAX_TERMS_LIMIT`].
    pub limit: i32,
}

/// The largest `limit` of `getTerms` the server accepts.
pub const MAX_TERMS_LIMIT: i32 = 1000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetTermsResult {
    /// The terms of the page; empty if the field has no postings or no term from `from` on.
    pub terms: Vec<TermEntry>,
    /// The term after the page, the `from` of the next page; `None` after the last term.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next: Option<BytesValue>,
}

/// One term of a field.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TermEntry {
    /// The term as text; if `binary`, its bytes in lowercase hex instead.
    pub term: String,
    /// Whether the term's bytes are not valid UTF-8, so `term` holds them as hex.
    pub binary: bool,
    /// Documents that contain the term, deleted ones included.
    pub doc_freq: i32,
    /// Occurrences of the term in all documents; equal to `doc_freq` if frequencies are not
    /// indexed.
    pub total_term_freq: i64,
}

/// `getPostings`: a page of the postings of a term: the documents that contain it, from a given
/// document id on, deleted documents included (ADR 0007). Fails with `NO_INDEX_OPEN`,
/// `FIELD_NOT_FOUND`, `TERM_NOT_FOUND`, or `INVALID_PARAMS` for a `limit` out of range, a
/// negative `from_doc` or a term that is not valid hex.
pub enum GetPostings {}

impl RpcMethod for GetPostings {
    const NAME: &'static str = "getPostings";
    type Params = GetPostingsParams;
    type Result = GetPostingsResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetPostingsParams {
    pub field: String,
    pub term: BytesValue,
    /// The first document id of the page, or else the one after it; 0 for the first posting. A
    /// `next_doc` from an earlier page continues where that page ended.
    pub from_doc: i32,
    /// The most postings the page has, from 1 to [`MAX_POSTINGS_LIMIT`].
    pub limit: i32,
}

/// The largest `limit` of `getPostings` the server accepts.
pub const MAX_POSTINGS_LIMIT: i32 = 1000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetPostingsResult {
    /// Documents that contain the term, deleted ones included.
    pub doc_freq: i32,
    /// Occurrences of the term in all documents; equal to `doc_freq` if frequencies are not
    /// indexed.
    pub total_term_freq: i64,
    /// The postings of the page, in document id order.
    pub postings: Vec<Posting>,
    /// The document id after the page, the `from_doc` of the next page; `None` after the last
    /// posting.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_doc: Option<i32>,
}

/// One document that contains a term.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Posting {
    pub doc_id: i32,
    pub deleted: bool,
    /// How often the term occurs in the document; `None` if frequencies are not indexed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub freq: Option<i32>,
}

/// `getPositions`: where a term occurs in a document, from the postings, with offsets and
/// payloads as far as they were indexed (ADR 0007). Fails with `NO_INDEX_OPEN`, `DOC_NOT_FOUND`,
/// `FIELD_NOT_FOUND`, `TERM_NOT_FOUND` if the document does not contain the term, or
/// `INVALID_PARAMS` for a term that is not valid hex.
pub enum GetPositions {}

impl RpcMethod for GetPositions {
    const NAME: &'static str = "getPositions";
    type Params = GetPositionsParams;
    type Result = GetPositionsResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetPositionsParams {
    /// The document id, from 0 to maxDoc - 1.
    pub doc_id: i32,
    pub field: String,
    pub term: BytesValue,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetPositionsResult {
    /// One entry per occurrence of the term in the document, in order; empty if positions are
    /// not indexed.
    pub positions: Vec<TermPosition>,
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

    #[test]
    fn first_page_has_no_from_and_last_page_no_next() {
        let params = GetTermsParams {
            field: "body".to_owned(),
            from: None,
            limit: 100,
        };
        assert_eq!(
            serde_json::to_string(&params).unwrap(),
            r#"{"field":"body","limit":100}"#
        );
        let last = GetPostingsResult {
            doc_freq: 1,
            total_term_freq: 1,
            postings: vec![Posting {
                doc_id: 4,
                deleted: false,
                freq: None,
            }],
            next_doc: None,
        };
        let json = r#"{"docFreq":1,"totalTermFreq":1,"postings":[{"docId":4,"deleted":false}]}"#;
        assert_eq!(serde_json::to_string(&last).unwrap(), json);
        assert_eq!(
            serde_json::from_str::<GetPostingsResult>(json).unwrap(),
            last
        );
    }
}

/// `startExportTerms`: starts a job that writes the terms of a field of the open index to a file,
/// one `term<delimiter>docFreq` per line (ADR 0022). Fails with `NO_INDEX_OPEN`, `NO_READER`,
/// `FIELD_NOT_FOUND`, `INVALID_PARAMS` for a path that isn't absolute, or `OUTPUT_NOT_WRITABLE`
/// for a file in an index directory, in a directory that doesn't exist, or that exists without
/// `overwrite`.
pub enum StartExportTerms {}

impl RpcMethod for StartExportTerms {
    const NAME: &'static str = "startExportTerms";
    type Params = StartExportTermsParams;
    type Result = JobStarted;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartExportTermsParams {
    /// The field whose terms to write.
    pub field: String,
    /// The absolute path of the file to write; it must lie outside every index directory.
    pub path: String,
    /// What separates a term from its document frequency; `None` means [`TermDelimiter::Tab`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub delimiter: Option<TermDelimiter>,
    /// Whether to replace a file that exists; `None` means false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub overwrite: Option<bool>,
}

/// What separates a term from its document frequency in an export.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TermDelimiter {
    Comma,
    Space,
    #[default]
    Tab,
}

/// `getExportTerms`: the progress of an export of terms, and its outcome once it is done. The
/// outcome is handed out once: an export that failed answers with its error
/// (`OUTPUT_NOT_WRITABLE` if writing failed), and afterwards the job is gone (`JOB_NOT_FOUND`).
pub enum GetExportTerms {}

impl RpcMethod for GetExportTerms {
    const NAME: &'static str = "getExportTerms";
    type Params = JobParams;
    type Result = GetExportTermsResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetExportTermsResult {
    /// The terms written so far.
    pub terms_written: i64,
    /// The outcome; `None` while the export is running.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<ExportTermsResult>,
}

/// The outcome of an export of the terms of a field.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExportTermsResult {
    /// The file written.
    pub path: String,
    /// The terms written, one per line.
    pub terms: i64,
    /// The size of the file.
    pub bytes: i64,
}
