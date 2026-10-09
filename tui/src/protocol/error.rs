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
    /// The path is not a directory, or the directory holds no Lucene index.
    NotAnIndex,
    /// The index was written in a format this Lucene version cannot read.
    UnsupportedIndexFormat,
    /// The index is damaged: a checksum mismatch, a truncated or missing file.
    CorruptIndex,
    /// The method needs an open index, and none is open.
    NoIndexOpen,
    /// The index can't be read: no permission, or another I/O error.
    IndexNotReadable,
    /// The open index has no field of that name.
    FieldNotFound,
    /// No such job: the id is unknown, the job was cancelled, or its outcome was already fetched.
    JobNotFound,
    /// The open index has no document with that id: it is negative, or not below maxDoc.
    DocNotFound,
    /// The field has no such term, or the document does not contain it.
    TermNotFound,
    /// The document has no stored value with that index in the field.
    ValueNotFound,
    /// The index directory has no commit of that generation.
    CommitNotFound,
    /// The query can't be run: the expression doesn't parse, or it has too many clauses once
    /// rewritten.
    InvalidQuery,
    /// The analyzer can't be built: an unknown component, an unknown or bad parameter, a config
    /// directory that is not a directory, or a resource file that can't be read or parsed.
    InvalidAnalyzer,
    /// The index names a codec or format that isn't loaded, and that a jar could add (ADR 0018).
    MissingCodec,
    /// The path was opened without a reader (ADR 0021): only its commits can be read.
    NoReader,
    /// A file can't be written (ADR 0022): it lies in an index directory, its directory doesn't
    /// exist, it exists, or writing it failed.
    OutputNotWritable,
    /// Jars can't be added (ADR 0024): a path is neither a jar nor a directory with jars, or their
    /// factories, codecs or formats can't be loaded, such as for a missing dependency.
    InvalidJar,
}

impl ErrorCode {
    pub const ALL: [ErrorCode; 23] = [
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
        ErrorCode::IndexNotReadable,
        ErrorCode::FieldNotFound,
        ErrorCode::JobNotFound,
        ErrorCode::DocNotFound,
        ErrorCode::TermNotFound,
        ErrorCode::ValueNotFound,
        ErrorCode::CommitNotFound,
        ErrorCode::InvalidQuery,
        ErrorCode::InvalidAnalyzer,
        ErrorCode::MissingCodec,
        ErrorCode::NoReader,
        ErrorCode::OutputNotWritable,
        ErrorCode::InvalidJar,
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
            ErrorCode::IndexNotReadable => -32005,
            ErrorCode::FieldNotFound => -32006,
            ErrorCode::JobNotFound => -32007,
            ErrorCode::DocNotFound => -32008,
            ErrorCode::TermNotFound => -32009,
            ErrorCode::ValueNotFound => -32010,
            ErrorCode::CommitNotFound => -32011,
            ErrorCode::InvalidQuery => -32012,
            ErrorCode::InvalidAnalyzer => -32013,
            ErrorCode::MissingCodec => -32014,
            ErrorCode::NoReader => -32015,
            ErrorCode::OutputNotWritable => -32016,
            ErrorCode::InvalidJar => -32017,
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
            ErrorCode::IndexNotReadable => "INDEX_NOT_READABLE",
            ErrorCode::FieldNotFound => "FIELD_NOT_FOUND",
            ErrorCode::JobNotFound => "JOB_NOT_FOUND",
            ErrorCode::DocNotFound => "DOC_NOT_FOUND",
            ErrorCode::TermNotFound => "TERM_NOT_FOUND",
            ErrorCode::ValueNotFound => "VALUE_NOT_FOUND",
            ErrorCode::CommitNotFound => "COMMIT_NOT_FOUND",
            ErrorCode::InvalidQuery => "INVALID_QUERY",
            ErrorCode::InvalidAnalyzer => "INVALID_ANALYZER",
            ErrorCode::MissingCodec => "MISSING_CODEC",
            ErrorCode::NoReader => "NO_READER",
            ErrorCode::OutputNotWritable => "OUTPUT_NOT_WRITABLE",
            ErrorCode::InvalidJar => "INVALID_JAR",
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
