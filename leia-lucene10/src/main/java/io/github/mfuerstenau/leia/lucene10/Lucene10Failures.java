package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.core.MissingCodecException;
import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.IndexReadException.Reason;
import java.io.EOFException;
import java.io.IOException;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;

/** Why Lucene 10 can't read an index, as leia-core tells it (ADR 0070). */
final class Lucene10Failures {

    private Lucene10Failures() {}

    /**
     * A failure of leia-core to open an index, in its words. An index that isn't there or has no
     * commit leia finds before it asks the runtime, so those are {@link Reason#NOT_READABLE}.
     */
    static IndexReadException opening(IndexOpenException e) {
        Reason reason = switch (e.reason()) {
            case UNSUPPORTED_FORMAT -> Reason.UNSUPPORTED_FORMAT;
            case CORRUPT -> Reason.CORRUPT;
            case MISSING_CODEC -> Reason.MISSING_CODEC;
            case NOT_FOUND, NOT_AN_INDEX, NOT_READABLE -> Reason.NOT_READABLE;
        };
        return new IndexReadException(reason, e.getMessage(), e);
    }

    /** A failure to read a commit, with Lucene's message, classified as leia-core's {@code getCommit} does. */
    static IOException reading(IOException e) {
        Reason reason = switch (e) {
            case MissingCodecException missing when missing.isLucenes() -> Reason.UNSUPPORTED_FORMAT;
            case MissingCodecException _ -> Reason.MISSING_CODEC;
            case IndexFormatTooOldException _, IndexFormatTooNewException _ -> Reason.UNSUPPORTED_FORMAT;
            case CorruptIndexException _, EOFException _ -> Reason.CORRUPT;
            default -> Reason.NOT_READABLE;
        };
        return reason == Reason.NOT_READABLE ? e : new IndexReadException(reason, e.getMessage(), e);
    }
}
