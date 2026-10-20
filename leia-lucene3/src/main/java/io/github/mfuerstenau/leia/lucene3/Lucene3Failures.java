package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.IndexReadException.Reason;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import java.io.EOFException;
import java.util.Optional;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexNotFoundException;

/**
 * Why 3.6 can't read an index, in the reasons leia-core tells apart (ADR 0070): a copy for its Lucene of
 * what the other runtimes do (ADR 0050).
 */
final class Lucene3Failures {

    private Lucene3Failures() {}

    /**
     * The reason of {@code e}, with Lucene's message, if 3.6 tells one: a format it can't read, or a
     * damaged index. Empty for any other failure, which leia reports as unreadable.
     */
    static Optional<IndexReadException> classify(Throwable e) {
        // 3.6 has no codecs to miss: they came with 4.0.
        Optional<Reason> reason = switch (e) {
            // There is no commit at all; leia looks for one before it asks.
            case IndexNotFoundException _ -> Optional.empty();
            case IndexFormatTooOldException _, IndexFormatTooNewException _ -> Optional.of(Reason.UNSUPPORTED_FORMAT);
            case CorruptIndexException _, EOFException _ -> Optional.of(Reason.CORRUPT);
            // A file that the commit refers to but that is missing.
            default -> Failures.missingFile(e).map(_ -> Reason.CORRUPT);
        };
        return reason.map(r -> new IndexReadException(r, Failures.message(e), e));
    }
}
