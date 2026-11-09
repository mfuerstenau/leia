package io.github.mfuerstenau.leia.core;

import java.io.Serial;
import java.util.Objects;

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
        NOT_READABLE,
        /** The index names a codec or format that isn't loaded, and that a jar could add (ADR 0018). */
        MISSING_CODEC
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

    /** The message, which every constructor takes. */
    @Override
    public String getMessage() {
        return Objects.requireNonNullElse(super.getMessage(), getClass().getSimpleName());
    }

    public Reason reason() {
        return reason;
    }
}
