package io.github.mfuerstenau.leia.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Every error code the server can send, serialized as its number. The JSON-RPC codes come first,
 * then leia's own codes from {@code -32000} downward. Mirrored by {@code ErrorCode} in {@code
 * tui/src/protocol/error.rs} and checked against {@code protocol/error-codes.json}.
 */
public enum ErrorCode {
    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603),

    /** The path does not exist. */
    INDEX_NOT_FOUND(-32000),
    /** The path is not a directory, or the directory holds no Lucene index. */
    NOT_AN_INDEX(-32001),
    /** The index was written in a format this Lucene version cannot read. */
    UNSUPPORTED_INDEX_FORMAT(-32002),
    /** The index is damaged: a checksum mismatch, a truncated or missing file. */
    CORRUPT_INDEX(-32003),
    /** The method needs an open index, and none is open. */
    NO_INDEX_OPEN(-32004),
    /** The index can't be read: no permission, or another I/O error. */
    INDEX_NOT_READABLE(-32005),
    /** The open index has no field of that name. */
    FIELD_NOT_FOUND(-32006),
    /** No such job: the id is unknown, the job was cancelled, or its outcome was already fetched. */
    JOB_NOT_FOUND(-32007),
    /** The open index has no document with that id: it is negative, or not below maxDoc. */
    DOC_NOT_FOUND(-32008);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    @JsonValue
    public int code() {
        return code;
    }

    @JsonCreator
    public static ErrorCode of(int code) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        throw new IllegalArgumentException("Unknown error code " + code);
    }
}
