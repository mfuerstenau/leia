package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Finds the passages of a query's matches in stored text with the runtime's {@code
 * UnifiedHighlighter}, as leia-core's {@code Highlights} does on Lucene 10 (ADR 0042, ADR 0063).
 * leia turns the passages into what it shows. A field's text values are joined with the separator
 * character {@code '\0'} into its content, and each passage stays inside one value.
 *
 * <p>A field with term vectors but without offsets in them is analyzed again instead, and says so by
 * its offset source.
 */
public interface Highlight {

    /**
     * What the matches of {@code query} are in {@code fields} of each of {@code docIds}. A deleted
     * document is highlighted too.
     *
     * @param query a query from {@link Search#parse} or {@link Search#term}, highlighted as parsed, not
     *     rewritten: the highlighter rewrites multi-term queries itself
     * @param maxPassages per field, the most passages per document
     * @param progress receives how many fields of documents were highlighted so far, before each field
     *     of each document; it may throw to stop the highlight
     * @throws Search.UnsupportedAnalyzerException if the runtime can't build the analyzer of {@code
     *     options}
     */
    Highlighted highlight(
            Search.ParsedQuery query,
            Options options,
            List<String> fields,
            int[] docIds,
            int[] maxPassages,
            LongConsumer progress)
            throws IOException, Search.UnsupportedAnalyzerException;

    /**
     * How to highlight.
     *
     * @param requireFieldMatch whether a field is highlighted only with the query's terms in that field
     * @param analyzer analyzes a value where the offsets of its terms come from analysis
     * @param wholeValues whether each value is one passage; otherwise passages are sentences
     * @param maxLength how many characters of a field's content are read
     */
    record Options(boolean requireFieldMatch, Search.AnalyzerSpec analyzer, boolean wholeValues, int maxLength) {}

    /** Where the offsets of the matches in a field come from, as Lucene's {@code OffsetSource}. */
    enum OffsetSource {
        POSTINGS,
        TERM_VECTORS,
        POSTINGS_WITH_TERM_VECTORS,
        ANALYSIS,
        /** None: the query has no terms in the field. */
        NONE
    }

    /**
     * What a highlight found.
     *
     * @param sources per field, where its offsets come from; a field may be missing, as {@link
     *     OffsetSource#NONE}
     * @param found per field, what was found in each document, in the order of the document ids;
     *     empty without a match
     */
    record Highlighted(Map<String, OffsetSource> sources, Map<String, List<Optional<FieldText>>> found) {}

    /**
     * What was found in one field of one document.
     *
     * @param content the field's text values, joined by the separator
     * @param passages the passages with matches, best first
     */
    record FieldText(OffsetSource source, String content, List<Passage> passages) {}

    /**
     * A passage, in UTF-16 positions into the content. The matches may overlap and reach past the
     * passage.
     */
    record Passage(int start, int end, float score, int[] matchStarts, int[] matchEnds) {}
}
