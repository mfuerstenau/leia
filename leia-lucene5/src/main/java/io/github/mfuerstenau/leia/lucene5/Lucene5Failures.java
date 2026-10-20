package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.IndexReadException.Reason;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import java.io.EOFException;
import java.util.Optional;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.util.Version;

/**
 * Why 5.5 can't read an index, in the reasons leia-core tells apart (ADR 0070): a copy for its Lucene of
 * what the other runtimes do (ADR 0050).
 */
final class Lucene5Failures {

    private Lucene5Failures() {}

    /**
     * The reason of {@code e}, with Lucene's message, if 5.5 tells one: a codec or format that isn't
     * loaded, a format it can't read, or a damaged index. Empty for any other failure, which leia
     * reports as unreadable.
     */
    static Optional<IndexReadException> classify(Throwable e) {
        Optional<IndexReadException> codec = Failures.missingCodec(e, Version.LATEST.toString());
        if (codec.isPresent()) {
            return codec;
        }
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
