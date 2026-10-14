package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.DocumentHighlight.FieldMatches;
import io.github.mfuerstenau.leia.core.DocumentHighlight.ValueHighlight;
import io.github.mfuerstenau.leia.core.IndexDocument.StoredValue;
import io.github.mfuerstenau.leia.core.SearchResults.FieldHighlight;
import io.github.mfuerstenau.leia.core.SearchResults.Passage;
import io.github.mfuerstenau.leia.core.StoredScan.Span;
import io.github.mfuerstenau.leia.core.StoredScan.Utf8Offsets;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.FieldHighlighter;
import org.apache.lucene.search.uhighlight.FieldOffsetStrategy;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.apache.lucene.search.uhighlight.SplittingBreakIterator;
import org.apache.lucene.search.uhighlight.UHComponents;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.search.uhighlight.WholeBreakIterator;

/**
 * Highlights the matches of a query in stored text with Lucene's {@link UnifiedHighlighter} (ADR
 * 0042): the best passages of the hits of a search, or every match in the fields of a document.
 *
 * <p>The highlighter joins a field's text values with one separator character, and keeps each
 * passage inside one value. A passage's value is found by counting the separators before it, and
 * its positions become UTF-8 byte offsets into that value, as in ADR 0027. Only {@code STRING}
 * values are highlighted, so the n-th text value is mapped back to its index among all the field's
 * stored values.
 *
 * <p>A field with term vectors but without offsets in them can't give the highlighter offsets. It
 * is then analyzed again instead, and says so by its offset source.
 */
final class Highlights {

    /** The most characters of a passage's text. */
    static final int MAX_PASSAGE_CHARS = 300;

    /** How many characters before its first match a cut passage starts. */
    static final int PASSAGE_BEFORE = 60;

    /** How many characters of a field the passages of a hit are found in. */
    static final int MAX_HIT_CHARS = 100_000;

    /** How many characters of a field the matches of one document are found in. */
    static final int MAX_DOCUMENT_CHARS = 1 << 20;

    /** The highlighter's separator between the values of a field. */
    private static final char SEPARATOR = 0;

    private Highlights() {}

    /**
     * The best passages of each hit, per field, in the order of each hit's {@code stored} fields.
     *
     * @param searcher the searcher that found the hits
     * @param docIds the hits' document ids
     * @param stored per hit, its stored values by field: the fields to highlight
     */
    static List<List<FieldHighlight>> passages(
            IndexSearcher searcher,
            Query query,
            HighlightSpec spec,
            int[] docIds,
            List<Map<String, List<StoredValue>>> stored)
            throws IOException {
        Set<String> fields = new LinkedHashSet<>();
        for (Map<String, List<StoredValue>> values : stored) {
            values.forEach((field, fieldValues) -> {
                if (!textIndexes(fieldValues).isEmpty()) {
                    fields.add(field);
                }
            });
        }
        List<List<FieldHighlight>> highlights = new ArrayList<>(docIds.length);
        if (fields.isEmpty()) {
            for (int i = 0; i < docIds.length; i++) {
                highlights.add(List.of());
            }
            return List.copyOf(highlights);
        }
        Highlighter highlighter = new Highlighter(
                builder(searcher, spec, MAX_HIT_CHARS, () -> BreakIterator.getSentenceInstance(Locale.ROOT)));
        Map<String, Object[]> byField =
                highlighter.highlight(fields.toArray(String[]::new), query, docIds, spec.maxPassages());
        for (int i = 0; i < docIds.length; i++) {
            List<FieldHighlight> hit = new ArrayList<>();
            for (Map.Entry<String, List<StoredValue>> entry : stored.get(i).entrySet()) {
                Object[] perDoc = byField.get(entry.getKey());
                if (perDoc != null && perDoc[i] instanceof Raw raw) {
                    List<Passage> passages = passages(raw, textIndexes(entry.getValue()));
                    if (!passages.isEmpty()) {
                        hit.add(new FieldHighlight(entry.getKey(), raw.source(), passages));
                    }
                }
            }
            highlights.add(List.copyOf(hit));
        }
        return List.copyOf(highlights);
    }

    /**
     * Every match in the text values of each field of {@code stored} of a document, up to {@code
     * maxSpans} per value. Fields without a text value are left out.
     *
     * @param stored the document's stored values by field, in stored order: the fields to highlight
     */
    static DocumentHighlight document(
            IndexSearcher searcher,
            Query query,
            HighlightSpec spec,
            int docId,
            Map<String, List<StoredValue>> stored,
            int maxSpans)
            throws IOException {
        List<String> fields = new ArrayList<>();
        List<List<Integer>> textIndexes = new ArrayList<>();
        stored.forEach((field, values) -> {
            List<Integer> indexes = textIndexes(values);
            if (!indexes.isEmpty()) {
                fields.add(field);
                textIndexes.add(indexes);
            }
        });
        if (fields.isEmpty()) {
            return new DocumentHighlight(List.of());
        }
        Highlighter highlighter = new Highlighter(builder(searcher, spec, MAX_DOCUMENT_CHARS, WholeBreakIterator::new));
        // One passage per value: the whole value.
        int[] maxPassages = textIndexes.stream().mapToInt(List::size).toArray();
        Map<String, Object[]> byField =
                highlighter.highlight(fields.toArray(String[]::new), query, new int[] {docId}, maxPassages);
        List<FieldMatches> matches = new ArrayList<>(fields.size());
        for (int f = 0; f < fields.size(); f++) {
            String field = fields.get(f);
            List<Integer> indexes = textIndexes.get(f);
            List<StoredValue> values = stored.get(field);
            long chars = indexes.size() - 1;
            for (int index : indexes) {
                chars += ((StoredValue.Text) values.get(index)).value().length();
            }
            OffsetSource source = highlighter.source(field);
            List<ValueHighlight> valueHighlights = List.of();
            if (byField.get(field)[0] instanceof Raw raw) {
                source = raw.source();
                valueHighlights = valueHighlights(raw, indexes, maxSpans);
            }
            matches.add(new FieldMatches(field, source, valueHighlights, chars > MAX_DOCUMENT_CHARS));
        }
        return new DocumentHighlight(List.copyOf(matches));
    }

    /** Every match in each value of a field, up to {@code maxSpans} per value, with UTF-8 offsets. */
    private static List<ValueHighlight> valueHighlights(Raw raw, List<Integer> textIndexes, int maxSpans) {
        List<ValueHighlight> valueHighlights = new ArrayList<>();
        for (RawPassage passage : raw.passages()) {
            Value value = Value.of(raw.content(), passage.start());
            if (value.ordinal() >= textIndexes.size()) {
                continue;
            }
            List<int[]> matches = passage.matches(value.start(), value.end());
            if (matches.isEmpty()) {
                continue;
            }
            Utf8Offsets offsets = new Utf8Offsets(raw.content().substring(value.start(), value.end()));
            List<Span> spans = new ArrayList<>(Math.min(matches.size(), maxSpans));
            for (int[] match : matches.subList(0, Math.min(matches.size(), maxSpans))) {
                spans.add(new Span(offsets.at(match[0] - value.start()), offsets.at(match[1] - value.start())));
            }
            valueHighlights.add(
                    new ValueHighlight(textIndexes.get(value.ordinal()), matches.size(), List.copyOf(spans)));
        }
        return List.copyOf(valueHighlights);
    }

    /** The passages of a field of a hit, cut around their first match, with UTF-8 offsets. */
    private static List<Passage> passages(Raw raw, List<Integer> textIndexes) {
        String content = raw.content();
        List<Passage> passages = new ArrayList<>(raw.passages().size());
        for (RawPassage passage : raw.passages()) {
            Value value = Value.of(content, passage.start());
            if (value.ordinal() >= textIndexes.size()) {
                continue;
            }
            List<int[]> matches = passage.matches(value.start(), value.end());
            if (matches.isEmpty()) {
                continue;
            }
            int first = matches.getFirst()[0];
            int last = matches.getLast()[1];
            int start = Math.max(passage.start(), value.start());
            int end = Math.min(passage.end(), value.end());
            if (end - start > MAX_PASSAGE_CHARS) {
                int from = Math.max(start, first - PASSAGE_BEFORE);
                int to = Math.min(end, from + MAX_PASSAGE_CHARS);
                start = Math.max(start, to - MAX_PASSAGE_CHARS);
                end = to;
            }
            while (start < first && Character.isWhitespace(content.charAt(start))) {
                start++;
            }
            while (end > last && Character.isWhitespace(content.charAt(end - 1))) {
                end--;
            }
            start = characterStart(content, start);
            end = characterStart(content, end);
            Utf8Offsets offsets = new Utf8Offsets(content.substring(value.start(), value.end()));
            int byteStart = offsets.at(start - value.start());
            List<Span> spans = new ArrayList<>(matches.size());
            for (int[] match : matches) {
                if (match[0] >= start && match[1] <= end) {
                    spans.add(new Span(offsets.at(match[0] - value.start()), offsets.at(match[1] - value.start())));
                }
            }
            passages.add(new Passage(
                    textIndexes.get(value.ordinal()),
                    byteStart,
                    content.substring(start, end),
                    List.copyOf(spans),
                    passage.score()));
        }
        return List.copyOf(passages);
    }

    /** {@code at}, or the position before it if it falls between the halves of a surrogate pair. */
    private static int characterStart(String text, int at) {
        return at > 0 && at < text.length() && Character.isLowSurrogate(text.charAt(at)) ? at - 1 : at;
    }

    /** The positions of the {@code STRING} values among {@code values}, the ones the highlighter reads. */
    private static List<Integer> textIndexes(List<StoredValue> values) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) instanceof StoredValue.Text) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static UnifiedHighlighter.Builder builder(
            IndexSearcher searcher, HighlightSpec spec, int maxLength, Supplier<BreakIterator> breakIterator) {
        UnifiedHighlighter.Builder builder = UnifiedHighlighter.builder(searcher, spec.analyzer())
                .withMaxLength(maxLength)
                .withBreakIterator(breakIterator)
                .withFormatter(new CopyingFormatter())
                .withMaxNoHighlightPassages(0);
        if (!spec.requireFieldMatch()) {
            // Weight matches run the whole query against one field's text, so a clause on another
            // field would make the query fail there: the query's terms are highlighted instead.
            builder.withFieldMatcher(_ -> true).withWeightMatches(false);
        }
        return builder;
    }

    private static OffsetSource of(UnifiedHighlighter.OffsetSource source) {
        return switch (source) {
            case POSTINGS -> OffsetSource.POSTINGS;
            case TERM_VECTORS -> OffsetSource.TERM_VECTORS;
            case POSTINGS_WITH_TERM_VECTORS -> OffsetSource.POSTINGS_WITH_TERM_VECTORS;
            case ANALYSIS -> OffsetSource.ANALYSIS;
            case NONE_NEEDED -> OffsetSource.NONE;
        };
    }

    /**
     * The value of the highlighter's content that {@code position} is in.
     *
     * @param ordinal how many values come before it
     * @param start where it starts in the content
     * @param end where it ends in the content
     */
    private record Value(int ordinal, int start, int end) {

        static Value of(String content, int position) {
            int start = content.lastIndexOf(SEPARATOR, position - 1) + 1;
            int ordinal = 0;
            for (int i = content.indexOf(SEPARATOR); i >= 0 && i < start; i = content.indexOf(SEPARATOR, i + 1)) {
                ordinal++;
            }
            int end = content.indexOf(SEPARATOR, start);
            return new Value(ordinal, start, end < 0 ? content.length() : end);
        }
    }

    /** A passage as the highlighter found it, in UTF-16 positions into the field's content. */
    private record RawPassage(int start, int end, float score, int[] matchStarts, int[] matchEnds) {

        /** The matches inside {@code from} to {@code to}, in order, overlapping ones merged. */
        List<int[]> matches(int from, int to) {
            List<int[]> matches = new ArrayList<>(matchStarts.length);
            for (int i = 0; i < matchStarts.length; i++) {
                int start = Math.max(matchStarts[i], from);
                int end = Math.min(matchEnds[i], to);
                if (start < end) {
                    matches.add(new int[] {start, end});
                }
            }
            matches.sort(Comparator.comparingInt(match -> match[0]));
            List<int[]> merged = new ArrayList<>(matches.size());
            for (int[] match : matches) {
                int[] previous = merged.isEmpty() ? null : merged.getLast();
                if (previous != null && match[0] < previous[1]) {
                    previous[1] = Math.max(previous[1], match[1]);
                } else {
                    merged.add(match);
                }
            }
            return merged;
        }
    }

    /** What the highlighter found in one field of one document. */
    private record Raw(OffsetSource source, String content, List<RawPassage> passages) {}

    /** Keeps the highlighter's passages instead of formatting them. */
    private static final class CopyingFormatter extends PassageFormatter {

        @Override
        public Object format(org.apache.lucene.search.uhighlight.Passage[] passages, String content) {
            List<RawPassage> copies = new ArrayList<>(passages.length);
            for (org.apache.lucene.search.uhighlight.Passage passage : passages) {
                int count = passage.getNumMatches();
                copies.add(new RawPassage(
                        passage.getStartOffset(),
                        passage.getEndOffset(),
                        passage.getScore(),
                        Arrays.copyOf(passage.getMatchStarts(), count),
                        Arrays.copyOf(passage.getMatchEnds(), count)));
            }
            return List.copyOf(copies);
        }
    }

    /** A highlighter that says where it took each field's offsets from. */
    private static final class Highlighter extends UnifiedHighlighter {

        // Qualified: inside this class, OffsetSource is the highlighter's own enum.
        private final Map<String, io.github.mfuerstenau.leia.core.OffsetSource> sources = new HashMap<>();

        Highlighter(Builder builder) {
            super(builder);
        }

        /**
         * Per field, what was found in each document of {@code docIds}: a {@link Raw}, or {@code null}
         * without a match.
         */
        Map<String, Object[]> highlight(String[] fields, Query query, int[] docIds, int maxPassages)
                throws IOException {
            int[] max = new int[fields.length];
            Arrays.fill(max, maxPassages);
            return highlight(fields, query, docIds, max);
        }

        /** As {@link #highlight(String[], Query, int[], int)}, with the most passages per field. */
        Map<String, Object[]> highlight(String[] fields, Query query, int[] docIds, int[] maxPassages)
                throws IOException {
            return highlightFieldsAsObjects(fields, query, docIds, maxPassages);
        }

        /** Where the offsets of {@code field} come from, as chosen for the last query. */
        io.github.mfuerstenau.leia.core.OffsetSource source(String field) {
            return sources.getOrDefault(field, io.github.mfuerstenau.leia.core.OffsetSource.NONE);
        }

        @Override
        protected FieldHighlighter getFieldHighlighter(String field, Query query, Set<Term> allTerms, int maxPassages) {
            UHComponents components = getHighlightComponents(field, query, allTerms);
            UnifiedHighlighter.OffsetSource source = getOptimizedOffsetSource(components);
            sources.put(field, of(source));
            return new CheckedFieldHighlighter(
                    field,
                    getOffsetStrategy(source, components),
                    () -> getOffsetStrategy(UnifiedHighlighter.OffsetSource.ANALYSIS, components),
                    new SplittingBreakIterator(getBreakIterator(field), MULTIVAL_SEP_CHAR),
                    getScorer(field),
                    maxPassages,
                    getFormatter(field),
                    getPassageSortComparator(field));
        }
    }

    /**
     * Highlights a field, and analyzes the value again where its term vector has no offsets. Its
     * result is a {@link Raw} that names the offset source used.
     */
    private static final class CheckedFieldHighlighter extends FieldHighlighter {

        private final Supplier<FieldOffsetStrategy> analysis;

        CheckedFieldHighlighter(
                String field,
                FieldOffsetStrategy strategy,
                Supplier<FieldOffsetStrategy> analysis,
                BreakIterator breakIterator,
                PassageScorer scorer,
                int maxPassages,
                PassageFormatter formatter,
                Comparator<org.apache.lucene.search.uhighlight.Passage> sortComparator) {
            super(field, strategy, breakIterator, scorer, maxPassages, 0, formatter, sortComparator);
            this.analysis = analysis;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object highlightFieldForDoc(LeafReader reader, int docId, String content) throws IOException {
            UnifiedHighlighter.OffsetSource source = getOffsetSource();
            Object found;
            try {
                found = super.highlightFieldForDoc(reader, docId, content);
            } catch (IllegalArgumentException e) {
                boolean termVectors = source == UnifiedHighlighter.OffsetSource.TERM_VECTORS
                        || source == UnifiedHighlighter.OffsetSource.POSTINGS_WITH_TERM_VECTORS;
                if (!termVectors || e.getMessage() == null || !e.getMessage().contains("without offsets")) {
                    throw e;
                }
                source = UnifiedHighlighter.OffsetSource.ANALYSIS;
                found = new FieldHighlighter(
                                field,
                                analysis.get(),
                                breakIterator,
                                passageScorer,
                                maxPassages,
                                0,
                                passageFormatter,
                                passageSortComparator)
                        .highlightFieldForDoc(reader, docId, content);
            }
            return found == null ? null : new Raw(of(source), content, (List<RawPassage>) found);
        }
    }
}
