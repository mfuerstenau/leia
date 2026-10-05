//! Error codes, mirroring `ErrorCode` in `leia-protocol` and `protocol/error-codes.json`.

use serde::{Deserialize, Serialize};

/// Every error code the server can send, serialized as its number. The JSON-RPC codes come
/// first, then leia's own codes from `-32000` downward.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "i32", into = "i32")]
pub enum ErrorCode {
    ParseError,
    InvalidRequest,
    MethodNotFound,
    InvalidParams,
    InternalError,
    /// The path does not exist.
    IndexNotFound,
    /// The path exists but holds no Lucene index.
    NotAnIndex,
    /// The index was written in a format this Lucene version cannot read.
    UnsupportedIndexFormat,
    /// The index is damaged.
    CorruptIndex,
    /// The method needs an open index, and none is open.
    NoIndexOpen,
}

impl ErrorCode {
    pub const ALL: [ErrorCode; 10] = [
        ErrorCode::ParseError,
        ErrorCode::InvalidRequest,
        ErrorCode::MethodNotFound,
        ErrorCode::InvalidParams,
        ErrorCode::InternalError,
        ErrorCode::IndexNotFound,
        ErrorCode::NotAnIndex,
        ErrorCode::UnsupportedIndexFormat,
        ErrorCode::CorruptIndex,
        ErrorCode::NoIndexOpen,
    ];

    pub const fn code(self) -> i32 {
        match self {
            ErrorCode::ParseError => -32700,
            ErrorCode::InvalidRequest => -32600,
            ErrorCode::MethodNotFound => -32601,
            ErrorCode::InvalidParams => -32602,
            ErrorCode::InternalError => -32603,
            ErrorCode::IndexNotFound => -32000,
            ErrorCode::NotAnIndex => -32001,
            ErrorCode::UnsupportedIndexFormat => -32002,
            ErrorCode::CorruptIndex => -32003,
            ErrorCode::NoIndexOpen => -32004,
        }
    }

    /// The name used in `protocol/error-codes.json` and by the Java enum.
    pub const fn name(self) -> &'static str {
        match self {
            ErrorCode::ParseError => "PARSE_ERROR",
            ErrorCode::InvalidRequest => "INVALID_REQUEST",
            ErrorCode::MethodNotFound => "METHOD_NOT_FOUND",
            ErrorCode::InvalidParams => "INVALID_PARAMS",
            ErrorCode::InternalError => "INTERNAL_ERROR",
            ErrorCode::IndexNotFound => "INDEX_NOT_FOUND",
            ErrorCode::NotAnIndex => "NOT_AN_INDEX",
            ErrorCode::UnsupportedIndexFormat => "UNSUPPORTED_INDEX_FORMAT",
            ErrorCode::CorruptIndex => "CORRUPT_INDEX",
            ErrorCode::NoIndexOpen => "NO_INDEX_OPEN",
        }
    }
}

impl TryFrom<i32> for ErrorCode {
    type Error = String;

    fn try_from(code: i32) -> Result<Self, Self::Error> {
        ErrorCode::ALL
            .into_iter()
            .find(|error_code| error_code.code() == code)
            .ok_or_else(|| format!("unknown error code {code}"))
    }
}

impl From<ErrorCode> for i32 {
    fn from(error_code: ErrorCode) -> Self {
        error_code.code()
    }
}
