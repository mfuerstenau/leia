package io.github.mfuerstenau.leia.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The stop words of fields, as Lucene's {@code QueryAutoStopWordAnalyzer} finds them (ADR 0039):
 * the terms in more than {@code maxDocFreq} documents.
 *
 * @param maxDocFreq the threshold, as given or computed from a fraction of the documents
 * @param words each scanned field, sorted by name, with its words in term order
 */
public record AutoStopWords(int maxDocFreq, Map<String, List<String>> words) {

    public AutoStopWords {
        words = Collections.unmodifiableMap(new TreeMap<>(words));
        words.values().forEach(Objects::requireNonNull);
    }

    /** When a term is a stop word: in more documents than a number, or than a fraction of them. */
    public sealed interface Threshold {

        /** In more than {@code maxDocFreq} documents, at least 0. */
        record MaxDocFreq(int maxDocFreq) implements Threshold {}

        /**
         * In more than {@code maxPercentDocs} of the live documents, from 0 to 1: Lucene's {@code
         * (int) (numDocs * maxPercentDocs)}.
         */
        record MaxPercentDocs(double maxPercentDocs) implements Threshold {}
    }

    /** A field with more stop words than the scan was allowed to find. */
    public static final class TooManyWordsException extends Exception {

        private static final long serialVersionUID = 1L;

        private final String field;

        TooManyWordsException(String field, int maxWords) {
            super("field '" + field + "' has more than " + maxWords + " stop words; raise the threshold");
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
