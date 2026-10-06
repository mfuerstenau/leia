//! Reading one document (ADR 0006). Mirrors `io.github.mfuerstenau.leia.protocol.document`.

use serde::{Deserialize, Serialize};

use super::index::DocValuesType;
use super::method::RpcMethod;

/// `getDocument`: what a document of the open index holds in every field: norm, stored values,
/// doc values, and whether it has a term vector. A deleted document is read too. Stored values
/// longer than [`MAX_VALUE_LENGTH`] bytes are cut (ADR 0008). Fails with `NO_INDEX_OPEN`, or
/// `DOC_NOT_FOUND` for an id outside 0 to maxDoc - 1.
pub enum GetDocument {}

impl RpcMethod for GetDocument {
    const NAME: &'static str = "getDocument";
    type Params = GetDocumentParams;
    type Result = GetDocumentResult;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetDocumentParams {
    /// The document id, from 0 to maxDoc - 1; a deleted document can be read too.
    pub doc_id: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetDocumentResult {
    pub doc_id: i32,
    /// Whether the document is deleted; its data is still there until a merge.
    pub deleted: bool,
    /// Every field of the index, ordered by field number as in `getIndexStats`, with what this
    /// document has in it.
    pub fields: Vec<DocumentField>,
}

/// What a document has in one field. How the field is indexed is in `getIndexStats`; whether
/// the document has postings in the field is not reported.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DocumentField {
    pub name: String,
    /// The document's norm; `None` if the field has no norms or the document has none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub norm: Option<i64>,
    /// The stored values, in the order they were added.
    pub stored: Vec<StoredValue>,
    /// The document's doc values; `None` if it has none in this field.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub doc_values: Option<DocValues>,
    /// Whether the document has a term vector for the field (`getTermVector`).
    pub has_term_vector: bool,
}

/// A stored value.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StoredValue {
    /// The type it was stored as.
    #[serde(rename = "type")]
    pub kind: StoredType,
    /// `String`: the text; `Binary`: the bytes as text if they are valid UTF-8, otherwise in
    /// lowercase hex; numbers: in decimal as Java's `toString` writes them, so `NaN`, `Infinity`
    /// and `1.0E10` can occur.
    pub value: String,
    /// Whether `value` holds the bytes of a `Binary` value as hex.
    pub binary: bool,
    /// Present if `value` is cut (ADR 0008): the length of the whole value in bytes, UTF-8 for
    /// text. `value` then holds its first [`MAX_VALUE_LENGTH`] bytes or fewer, cut between two
    /// characters, and `getStoredValue` fetches the whole value.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub full_length: Option<i64>,
}

/// The longest stored value, in bytes, that `getDocument` sends whole (ADR 0008).
pub const MAX_VALUE_LENGTH: usize = 64 * 1024;

/// `getStoredValue`: one stored value of a document, whole, however long it is (ADR 0008). Fails
/// with `NO_INDEX_OPEN`, `DOC_NOT_FOUND`, `FIELD_NOT_FOUND`, or `VALUE_NOT_FOUND` if the document
/// has no stored value with that index in the field.
pub enum GetStoredValue {}

impl RpcMethod for GetStoredValue {
    const NAME: &'static str = "getStoredValue";
    type Params = GetStoredValueParams;
    type Result = GetStoredValueResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetStoredValueParams {
    /// The document id, from 0 to maxDoc - 1.
    pub doc_id: i32,
    pub field: String,
    /// Which of the document's stored values in the field, from 0, in the order `getDocument`
    /// lists them.
    pub index: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetStoredValueResult {
    /// The whole stored value, never cut.
    pub value: StoredValue,
}

/// The type a value was stored as.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StoredType {
    String,
    Binary,
    Int,
    Long,
    Float,
    Double,
}

/// A document's doc values in one field.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DocValues {
    /// The doc values type, never `None`.
    #[serde(rename = "type")]
    pub kind: DocValuesType,
    /// The raw values of a `Numeric` or `SortedNumeric` field, in ascending order for the
    /// latter; empty for the other types. What they encode (a long, the bits of a double, a
    /// sortable double, …) is not recorded in the index.
    pub numbers: Vec<i64>,
    /// The values of a `Binary`, `Sorted` or `SortedSet` field, in ascending order for the
    /// latter; empty for the other types.
    pub values: Vec<BytesValue>,
}

/// A byte string.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BytesValue {
    /// The bytes as text; if `binary`, in lowercase hex instead.
    pub value: String,
    /// Whether the bytes are not valid UTF-8, so `value` holds them as hex.
    pub binary: bool,
}

/// `getTermVector`: the term vector of a document for a field, with positions, offsets and
/// payloads as far as they were stored. Empty if the document has none for the field. Fails
/// with `NO_INDEX_OPEN`, `DOC_NOT_FOUND` or `FIELD_NOT_FOUND`.
pub enum GetTermVector {}

impl RpcMethod for GetTermVector {
    const NAME: &'static str = "getTermVector";
    type Params = GetTermVectorParams;
    type Result = GetTermVectorResult;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetTermVectorParams {
    /// The document id, from 0 to maxDoc - 1.
    pub doc_id: i32,
    pub field: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GetTermVectorResult {
    /// The terms in term order; empty if the document has no term vector for the field.
    pub terms: Vec<TermVectorTerm>,
}

/// One term of a term vector.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TermVectorTerm {
    /// The term as text; if `binary`, its bytes in lowercase hex instead.
    pub term: String,
    /// Whether the term's bytes are not valid UTF-8, so `term` holds them as hex.
    pub binary: bool,
    /// How often the term occurs in the field of the document.
    pub freq: i32,
    /// One entry per occurrence, in order; empty if the term vector has neither positions nor
    /// offsets.
    pub positions: Vec<TermPosition>,
}

/// One occurrence of a term in a document, from a term vector or from the postings.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TermPosition {
    /// `None` if positions are not indexed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub position: Option<i32>,
    /// `None` if offsets are not indexed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start_offset: Option<i32>,
    /// `None` if offsets are not indexed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end_offset: Option<i32>,
    /// The payload in lowercase hex; `None` if there is none.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub payload: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn field_without_norm_or_doc_values_omits_them() {
        let field = DocumentField {
            name: "id".to_owned(),
            norm: None,
            stored: vec![StoredValue {
                kind: StoredType::String,
                value: "1".to_owned(),
                binary: false,
                full_length: None,
            }],
            doc_values: None,
            has_term_vector: false,
        };
        let json = r#"{"name":"id","stored":[{"type":"STRING","value":"1","binary":false}],"hasTermVector":false}"#;
        assert_eq!(serde_json::to_string(&field).unwrap(), json);
        assert_eq!(serde_json::from_str::<DocumentField>(json).unwrap(), field);
    }

    #[test]
    fn cut_value_has_its_full_length() {
        let json = r#"{"type":"STRING","value":"ab","binary":false,"fullLength":70000}"#;
        let value: StoredValue = serde_json::from_str(json).unwrap();
        assert_eq!(value.full_length, Some(70000));
        assert_eq!(serde_json::to_string(&value).unwrap(), json);
    }

    #[test]
    fn rejects_unknown_stored_type() {
        assert!(
            serde_json::from_str::<StoredValue>(r#"{"type":"SHORT","value":"1","binary":false}"#)
                .is_err()
        );
    }
}
