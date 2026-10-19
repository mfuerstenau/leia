package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.Highlight;
import io.github.mfuerstenau.leia.runtime.Search.ParsedQuery;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexReader;
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
 * Lucene 9.12's {@link UnifiedHighlighter}, set up as leia-core's {@code Highlights} sets up Lucene
 * 10's (ADR 0042, ADR 0063): a copy, as the facades are (ADR 0050 §1).
 */
final class Lucene9Highlight implements Highlight {

    private final IndexReader reader;

    Lucene9Highlight(IndexReader reader) {
        this.reader = reader;
    }

    @Override
    public Highlighted highlight(
            ParsedQuery parsed,
            Options options,
            List<String> fields,
            int[] docIds,
            int[] maxPassages,
            LongConsumer progress)
            throws IOException, UnsupportedAnalyzerException {
        Query query = Lucene9Index.query(parsed);
        IndexSearcher searcher = new IndexSearcher(reader);
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        Analyzer analyzer = Lucene9Analyzers.analyzer(options.analyzer());
        try {
            Highlighter highlighter = new Highlighter(builder(searcher, analyzer, options), progress);
            Map<String, Object[]> byField =
                    highlighter.highlight(fields.toArray(String[]::new), query, docIds, maxPassages);
            Map<String, List<Optional<FieldText>>> found = new HashMap<>();
            byField.forEach((field, perDoc) -> found.put(
                    field,
                    Arrays.stream(perDoc)
                            .map(text -> Optional.ofNullable((FieldText) text))
                            .toList()));
            return new Highlighted(Map.copyOf(highlighter.sources), Map.copyOf(found));
        } finally {
            analyzer.close();
        }
    }

    private static UnifiedHighlighter.Builder builder(IndexSearcher searcher, Analyzer analyzer, Options options) {
        Supplier<BreakIterator> breakIterator =
                options.wholeValues() ? WholeBreakIterator::new : () -> BreakIterator.getSentenceInstance(Locale.ROOT);
        UnifiedHighlighter.Builder builder = UnifiedHighlighter.builder(searcher, analyzer)
                .withMaxLength(options.maxLength())
                .withBreakIterator(breakIterator)
                .withFormatter(new CopyingFormatter())
                .withMaxNoHighlightPassages(0);
        if (!options.requireFieldMatch()) {
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

    /** Keeps the highlighter's passages instead of formatting them. */
    private static final class CopyingFormatter extends PassageFormatter {

        @Override
        public Object format(org.apache.lucene.search.uhighlight.Passage[] passages, String content) {
            List<Passage> copies = new ArrayList<>(passages.length);
            for (org.apache.lucene.search.uhighlight.Passage passage : passages) {
                int count = passage.getNumMatches();
                copies.add(new Passage(
                        passage.getStartOffset(),
                        passage.getEndOffset(),
                        passage.getScore(),
                        Arrays.copyOf(passage.getMatchStarts(), count),
                        Arrays.copyOf(passage.getMatchEnds(), count)));
            }
            return List.copyOf(copies);
        }
    }

    /** A highlighter that says where it took each field's offsets from, and reports its progress. */
    private static final class Highlighter extends UnifiedHighlighter {

        // Qualified: inside this class, OffsetSource is the highlighter's own enum.
        private final Map<String, Highlight.OffsetSource> sources = new HashMap<>();
        private final LongConsumer progress;
        private long done;

        Highlighter(Builder builder, LongConsumer progress) {
            super(builder);
            this.progress = progress;
        }

        /**
         * Per field, what was found in each document of {@code docIds}: a {@link FieldText}, or
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
                    getPassageSortComparator(field),
                    () -> progress.accept(done++));
        }
    }

    /**
     * Highlights a field, and analyzes the value again where its term vector has no offsets. Its
     * result is a {@link FieldText} that names the offset source used.
     */
    private static final class CheckedFieldHighlighter extends FieldHighlighter {

        private final Supplier<FieldOffsetStrategy> analysis;
        private final Runnable started;

        CheckedFieldHighlighter(
                String field,
                FieldOffsetStrategy strategy,
                Supplier<FieldOffsetStrategy> analysis,
                BreakIterator breakIterator,
                PassageScorer scorer,
                int maxPassages,
                PassageFormatter formatter,
                Comparator<org.apache.lucene.search.uhighlight.Passage> sortComparator,
                Runnable started) {
            super(field, strategy, breakIterator, scorer, maxPassages, 0, formatter, sortComparator);
            this.analysis = analysis;
            this.started = started;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object highlightFieldForDoc(LeafReader reader, int docId, String content) throws IOException {
            started.run();
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
            return found == null ? null : new FieldText(of(source), content, (List<Passage>) found);
        }
    }
}
