package io.github.mfuerstenau.leia.core;

import java.io.Serial;

/** Opening an index failed for an expected {@link Reason}, described by the message. */
public final class IndexOpenException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The path does not exist. */
        NOT_FOUND,
        /** The path is not a directory, or the directory holds no Lucene index. */
        NOT_AN_INDEX,
        /** The index was written by a Lucene version this one can't read. */
        UNSUPPORTED_FORMAT,
        /** The index is damaged: a checksum mismatch, a truncated or missing file. */
        CORRUPT,
        /** The index can't be read: no permission, or another I/O error. */
        NOT_READABLE
    }

    private final Reason reason;

    IndexOpenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    IndexOpenException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
