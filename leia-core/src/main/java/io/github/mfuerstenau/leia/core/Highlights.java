package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.DocumentHighlight.FieldMatches;
import io.github.mfuerstenau.leia.core.DocumentHighlight.ValueHighlight;
import io.github.mfuerstenau.leia.core.IndexDocument.StoredValue;
import io.github.mfuerstenau.leia.core.SearchResults.FieldHighlight;
import io.github.mfuerstenau.leia.core.SearchResults.Passage;
import io.github.mfuerstenau.leia.core.StoredScan.Span;
import io.github.mfuerstenau.leia.core.StoredScan.Utf8Offsets;
import io.github.mfuerstenau.leia.core.TextHighlighter.FoundPassage;
import io.github.mfuerstenau.leia.core.TextHighlighter.FoundText;
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
import java.util.Optional;
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
 * <p>Finding the passages is a {@link TextHighlighter}'s work: Lucene 10's from {@link #lucene}, or a
 * runtime's (ADR 0063). The rest uses only the JDK.
 *
 * <p>A field with term vectors but without offsets in them can't give the highlighter offsets. It
 * is then analyzed again instead, and says so by its offset source.
 */
public final class Highlights {

    /** The most characters of a passage's text. */
    static final int MAX_PASSAGE_CHARS = 300;

    /** How many characters before its first match a cut passage starts. */
    static final int PASSAGE_BEFORE = 60;

    /** How many characters of a field the passages of a hit are found in. */
    public static final int MAX_HIT_CHARS = 100_000;

    /** How many characters of a field the matches of one document are found in. */
    public static final int MAX_DOCUMENT_CHARS = 1 << 20;

    /** The highlighter's separator between the values of a field. */
    private static final char SEPARATOR = 0;

    private Highlights() {}

    /**
     * The best passages of each hit, per field, in the order of each hit's {@code stored} fields.
     *
     * @param highlighter finds the passages of the search's query
     * @param maxPassages the most passages per field and hit
     * @param docIds the hits' document ids
     * @param stored per hit, its stored values by field: the fields to highlight
     */
    public static List<List<FieldHighlight>> passages(
            TextHighlighter highlighter, int maxPassages, int[] docIds, List<Map<String, List<StoredValue>>> stored)
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
        int[] max = new int[fields.size()];
        Arrays.fill(max, maxPassages);
        Map<String, List<Optional<FoundText>>> byField = highlighter
                .highlight(List.copyOf(fields), docIds, max, TextHighlighter.Mode.PASSAGES)
                .found();
        for (int i = 0; i < docIds.length; i++) {
            highlights.add(hit(i, stored.get(i), byField));
        }
        return List.copyOf(highlights);
    }

    /** The highlighted fields of hit {@code i}, from what the highlighter found in each field. */
    private static List<FieldHighlight> hit(
            int i, Map<String, List<StoredValue>> stored, Map<String, List<Optional<FoundText>>> byField) {
        List<FieldHighlight> hit = new ArrayList<>();
        for (Map.Entry<String, List<StoredValue>> entry : stored.entrySet()) {
            List<Optional<FoundText>> perDoc = byField.get(entry.getKey());
            Optional<FoundText> inDoc = perDoc == null ? Optional.empty() : perDoc.get(i);
            if (inDoc.isPresent()) {
                FoundText found = inDoc.get();
                List<Passage> passages = passages(found, textIndexes(entry.getValue()));
                if (!passages.isEmpty()) {
                    hit.add(new FieldHighlight(entry.getKey(), found.source(), passages));
                }
            }
        }
        return List.copyOf(hit);
    }

    /**
     * Every match in the text values of each field of {@code stored} of a document, up to {@code
     * maxSpans} per value. Fields without a text value are left out.
     *
     * @param highlighter finds the matches of the query
     * @param stored the document's stored values by field, in stored order: the fields to highlight
     */
    public static DocumentHighlight document(
            TextHighlighter highlighter, int docId, Map<String, List<StoredValue>> stored, int maxSpans)
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
        // One passage per value: the whole value.
        int[] maxPassages = textIndexes.stream().mapToInt(List::size).toArray();
        TextHighlighter.Highlighted highlighted =
                highlighter.highlight(List.copyOf(fields), new int[] {docId}, maxPassages, TextHighlighter.Mode.VALUES);
        List<FieldMatches> matches = new ArrayList<>(fields.size());
        for (int f = 0; f < fields.size(); f++) {
            String field = fields.get(f);
            List<Integer> indexes = textIndexes.get(f);
            List<StoredValue> values = stored.get(field);
            long chars = indexes.size() - 1L;
            for (int index : indexes) {
                chars += ((StoredValue.Text) values.get(index)).value().length();
            }
            OffsetSource source = highlighted.sources().getOrDefault(field, OffsetSource.NONE);
            List<ValueHighlight> valueHighlights = List.of();
            List<Optional<FoundText>> perDoc = highlighted.found().get(field);
            Optional<FoundText> inDoc = perDoc == null ? Optional.empty() : perDoc.getFirst();
            if (inDoc.isPresent()) {
                FoundText found = inDoc.get();
                source = found.source();
                valueHighlights = valueHighlights(found, indexes, maxSpans);
            }
            matches.add(new FieldMatches(field, source, valueHighlights, chars > MAX_DOCUMENT_CHARS));
        }
        return new DocumentHighlight(List.copyOf(matches));
    }

    /** Every match in each value of a field, up to {@code maxSpans} per value, with UTF-8 offsets. */
    private static List<ValueHighlight> valueHighlights(FoundText raw, List<Integer> textIndexes, int maxSpans) {
        List<ValueHighlight> valueHighlights = new ArrayList<>();
        for (FoundPassage passage : raw.passages()) {
            Value value = Value.of(raw.content(), passage.start());
            if (value.ordinal() >= textIndexes.size()) {
                continue;
            }
            List<int[]> matches = matches(passage, value.start(), value.end());
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
    private static List<Passage> passages(FoundText raw, List<Integer> textIndexes) {
        String content = raw.content();
        List<Passage> passages = new ArrayList<>(raw.passages().size());
        for (FoundPassage passage : raw.passages()) {
            passage(content, passage, textIndexes).ifPresent(passages::add);
        }
        return List.copyOf(passages);
    }

    /** {@code passage} within the value it starts in, cut to its matches; empty without a match. */
    private static Optional<Passage> passage(String content, FoundPassage passage, List<Integer> textIndexes) {
        Value value = Value.of(content, passage.start());
        if (value.ordinal() >= textIndexes.size()) {
            return Optional.empty();
        }
        List<int[]> matches = matches(passage, value.start(), value.end());
        if (matches.isEmpty()) {
            return Optional.empty();
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
        return Optional.of(new Passage(
                textIndexes.get(value.ordinal()),
                byteStart,
                content.substring(start, end),
                List.copyOf(spans),
                passage.score()));
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

    /** The matches of {@code passage} inside {@code from} to {@code to}, in order, overlapping ones merged. */
    private static List<int[]> matches(FoundPassage passage, int from, int to) {
        int[] matchStarts = passage.matchStarts();
        int[] matchEnds = passage.matchEnds();
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

    /**
     * Lucene 10's {@link UnifiedHighlighter} for {@code query} on {@code searcher}, with the analyzer
     * and field matching of {@code spec}.
     *
     * @param query the query as built, not rewritten: the highlighter handles multi-term queries itself
     */
    public static TextHighlighter lucene(IndexSearcher searcher, Query query, HighlightSpec spec) {
        return (fields, docIds, maxPassages, mode) -> {
            Highlighter highlighter = new Highlighter(
                    switch (mode) {
                        case PASSAGES ->
                            builder(
                                    searcher,
                                    spec,
                                    MAX_HIT_CHARS,
                                    () -> BreakIterator.getSentenceInstance(Locale.ROOT));
                        case VALUES -> builder(searcher, spec, MAX_DOCUMENT_CHARS, WholeBreakIterator::new);
                    });
            Map<String, Object[]> byField =
                    highlighter.highlight(fields.toArray(String[]::new), query, docIds, maxPassages);
            Map<String, List<Optional<FoundText>>> found = new HashMap<>();
            byField.forEach((field, perDoc) -> found.put(
                    field,
                    Arrays.stream(perDoc)
                            .map(raw -> Optional.ofNullable((FoundText) raw))
                            .toList()));
            return new TextHighlighter.Highlighted(Map.copyOf(highlighter.sources), Map.copyOf(found));
        };
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

    /** Keeps the highlighter's passages instead of formatting them. */
    private static final class CopyingFormatter extends PassageFormatter {

        @Override
        public Object format(org.apache.lucene.search.uhighlight.Passage[] passages, String content) {
            List<FoundPassage> copies = new ArrayList<>(passages.length);
            for (org.apache.lucene.search.uhighlight.Passage passage : passages) {
                int count = passage.getNumMatches();
                copies.add(new FoundPassage(
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
         * Per field, what was found in each document of {@code docIds}: a {@link FoundText}, or
         * {@code null} without a match.
         */
        Map<String, Object[]> highlight(String[] fields, Query query, int[] docIds, int[] maxPassages)
                throws IOException {
            return highlightFieldsAsObjects(fields, query, docIds, maxPassages);
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
     * result is a {@link FoundText} that names the offset source used.
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
            return found == null ? null : new FoundText(of(source), content, (List<FoundPassage>) found);
        }
    }
}
