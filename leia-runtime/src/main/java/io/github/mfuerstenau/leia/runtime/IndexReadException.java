package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.io.Serial;

/**
 * Reading an index failed for a {@link Reason} that the runtime's Lucene tells apart (ADR 0070), so
 * that leia names it as for any other index. A plain {@link IOException} means {@link
 * Reason#NOT_READABLE}.
 */
public final class IndexReadException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why an index can't be read. */
    public enum Reason {
        /** The index is damaged: a checksum mismatch, a truncated or missing file. */
        CORRUPT,
        /** The index was written in a format the runtime's Lucene can't read. */
        UNSUPPORTED_FORMAT,
        /** The index names a codec or format that isn't loaded, and that a jar could add (ADR 0018). */
        MISSING_CODEC,
        /** The index can't be read: no permission, or another I/O error. */
        NOT_READABLE
    }

    private final Reason reason;

    public IndexReadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
