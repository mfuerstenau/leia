package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.util.BytesRef;

/**
 * One occurrence of a term in a document, from a term vector or from the postings.
 *
 * @param position the position; empty if none were indexed
 * @param startOffset the start offset; empty if none were indexed
 * @param endOffset the end offset; empty if none were indexed
 * @param payload the payload; empty if there is none
 */
public record TermPosition(
        OptionalInt position, OptionalInt startOffset, OptionalInt endOffset, Optional<BytesRef> payload) {

    /**
     * Reads the {@code freq} occurrences of the current document of {@code postings}, which must be
     * positioned on it and have been asked for positions, offsets and payloads.
     */
    static List<TermPosition> read(PostingsEnum postings, int freq, boolean hasPositions, boolean hasOffsets)
            throws IOException {
        List<TermPosition> positions = new ArrayList<>(freq);
        for (int i = 0; i < freq; i++) {
            int position = postings.nextPosition();
            BytesRef payload = postings.getPayload();
            positions.add(new TermPosition(
                    hasPositions ? OptionalInt.of(position) : OptionalInt.empty(),
                    hasOffsets ? OptionalInt.of(postings.startOffset()) : OptionalInt.empty(),
                    hasOffsets ? OptionalInt.of(postings.endOffset()) : OptionalInt.empty(),
                    // Lucene doesn't tell an empty payload from none.
                    payload != null && payload.length > 0
                            ? Optional.of(BytesRef.deepCopyOf(payload))
                            : Optional.empty()));
        }
        return List.copyOf(positions);
    }
}
