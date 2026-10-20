package io.github.mfuerstenau.leia.server.search;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the passages of a query's matches in the stored text of documents, as Lucene's {@code
 * UnifiedHighlighter} finds them (ADR 0042, ADR 0063), with a runtime's Lucene ({@code
 * RuntimeHighlights}). {@link Highlights} turns what it finds into passages and matches with UTF-8
 * offsets.
 *
 * <p>A field's text values are joined with the separator character {@code '\0'} into its content.
 */
public interface TextHighlighter {

    /** How a field's content is broken into passages, and how much of it is read. */
    enum Mode {
        /** Sentences, in the first {@link Highlights#MAX_HIT_CHARS} characters. */
        PASSAGES,
        /** Whole values, in the first {@link Highlights#MAX_DOCUMENT_CHARS} characters. */
        VALUES
    }

    /**
     * What the matches are in {@code fields} of each of {@code docIds}.
     *
     * @param maxPassages per field, the most passages per document
     */
    Highlighted highlight(List<String> fields, int[] docIds, int[] maxPassages, Mode mode) throws IOException;

    /**
     * What a highlight found.
     *
     * @param sources per field, where its offsets come from; a field may be missing, as {@link
     *     OffsetSource#NONE}
     * @param found per field, what was found in each document, in the order of the document ids;
     *     empty without a match
     */
    record Highlighted(Map<String, OffsetSource> sources, Map<String, List<Optional<FoundText>>> found) {}

    /**
     * What was found in one field of one document.
     *
     * @param source where the offsets came from
     * @param content the field's text values, joined by the separator
     * @param passages the passages with matches, best first
     */
    record FoundText(OffsetSource source, String content, List<FoundPassage> passages) {}

    /**
     * A passage as the highlighter found it, in UTF-16 positions into the content. The matches may
     * overlap and reach past the passage.
     */
    record FoundPassage(int start, int end, float score, int[] matchStarts, int[] matchEnds) {}
}
