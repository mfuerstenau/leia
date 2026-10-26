package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.server.job.ScanProgress;
import io.github.mfuerstenau.leia.server.terms.TermExport;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    /** How many terms are scanned between two progress reports. */
    public static final int PROGRESS_INTERVAL = 1024;

    /**
     * Finds the stop words of {@code fields}, scanned in the order of their names: the terms in more
     * than {@code maxDocFreq} documents, by the document frequency each source gives (ADR 0039, ADR
     * 0061). Every runtime scans through this.
     *
     * <p>{@code progress} gets the number of terms scanned every {@value #PROGRESS_INTERVAL} terms and
     * at the end; it can stop the scan by throwing a {@link java.util.concurrent.CancellationException}.
     *
     * @throws TooManyWordsException if a field has more than {@code maxWords} words
     * @throws IOException if reading the terms fails
     */
    public static AutoStopWords find(
            Map<String, TermExport.TermSource> fields, int maxDocFreq, int maxWords, ScanProgress progress)
            throws IOException, TooManyWordsException {
        TreeMap<String, List<String>> words = new TreeMap<>();
        long[] terms = {0};
        for (Map.Entry<String, TermExport.TermSource> entry : new TreeMap<>(fields).entrySet()) {
            String field = entry.getKey();
            List<String> fieldWords = new ArrayList<>();
            try {
                entry.getValue().forEachTerm((bytes, offset, length, docFreq, _) -> {
                    terms[0]++;
                    if (docFreq > maxDocFreq) {
                        if (fieldWords.size() == maxWords) {
                            throw new TooManyWords(new TooManyWordsException(field, maxWords));
                        }
                        fieldWords.add(new String(bytes, offset, length, StandardCharsets.UTF_8));
                    }
                    if (terms[0] % PROGRESS_INTERVAL == 0) {
                        progress.scanned(terms[0]);
                    }
                });
            } catch (TooManyWords e) {
                throw e.cause;
            }
            words.put(field, fieldWords);
        }
        progress.scanned(terms[0]);
        return new AutoStopWords(maxDocFreq, words);
    }

    /** Carries a {@link TooManyWordsException} through a {@link TermExport.TermSink}. */
    private static final class TooManyWords extends IOException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient TooManyWordsException cause;

        TooManyWords(TooManyWordsException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    /** When a term is a stop word: in more documents than a number, or than a fraction of them. */
    public sealed interface Threshold {

        /**
         * The highest document frequency that is no stop word, for an index of {@code numDocs} live
         * documents.
         *
         * @throws IllegalArgumentException if the number is negative or the fraction not from 0 to 1
         */
        default int maxDocFreq(int numDocs) {
            return switch (this) {
                case MaxDocFreq given -> {
                    if (given.maxDocFreq() < 0) {
                        throw new IllegalArgumentException("maxDocFreq must not be negative: " + given.maxDocFreq());
                    }
                    yield given.maxDocFreq();
                }
                case MaxPercentDocs percent -> {
                    double fraction = percent.maxPercentDocs();
                    if (!(fraction >= 0 && fraction <= 1)) {
                        throw new IllegalArgumentException("maxPercentDocs must be from 0 to 1: " + fraction);
                    }
                    yield (int) (numDocs * fraction);
                }
            };
        }

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
