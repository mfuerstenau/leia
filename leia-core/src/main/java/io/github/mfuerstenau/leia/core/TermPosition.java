package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.util.BytesRef;
import org.jspecify.annotations.Nullable;

/**
 * One occurrence of a term in a document, from a term vector or from the postings.
 *
 * @param position the position; null if none were indexed
 * @param startOffset the start offset; null if none were indexed
 * @param endOffset the end offset; null if none were indexed
 * @param payload the payload; null if there is none
 */
public record TermPosition(
        @Nullable Integer position,
        @Nullable Integer startOffset,
        @Nullable Integer endOffset,
        @Nullable BytesRef payload) {

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
                    hasPositions ? Integer.valueOf(position) : null,
                    hasOffsets ? Integer.valueOf(postings.startOffset()) : null,
                    hasOffsets ? Integer.valueOf(postings.endOffset()) : null,
                    // Lucene doesn't tell an empty payload from none.
                    payload != null && payload.length > 0 ? BytesRef.deepCopyOf(payload) : null));
        }
        return List.copyOf(positions);
    }
}
