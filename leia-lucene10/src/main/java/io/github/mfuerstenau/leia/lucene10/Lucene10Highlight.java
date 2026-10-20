package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.HighlightSpec;
import io.github.mfuerstenau.leia.core.Highlights;
import io.github.mfuerstenau.leia.core.Queries;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.TextHighlighter;
import io.github.mfuerstenau.leia.runtime.Highlight;
import io.github.mfuerstenau.leia.runtime.Search.ParsedQuery;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

/**
 * Lucene 10's {@code UnifiedHighlighter}, as leia-core's {@link Highlights#lucene} sets it up (ADR
 * 0042, ADR 0065). It highlights one field of one document at a time, so that the progress is asked
 * before each.
 */
final class Lucene10Highlight implements Highlight {

    private final ReadOnlyIndex index;

    Lucene10Highlight(ReadOnlyIndex index) {
        this.index = index;
    }

    /**
     * {@inheritDoc} The content read is that of leia-core's mode: {@link Highlights#MAX_DOCUMENT_CHARS}
     * characters of whole values, {@link Highlights#MAX_HIT_CHARS} of sentences.
     *
     * @throws IllegalArgumentException if {@code options} asks for another length
     */
    @Override
    public Highlighted highlight(
            ParsedQuery parsed,
            Options options,
            List<String> fields,
            int[] docIds,
            int[] maxPassages,
            LongConsumer progress)
            throws IOException, UnsupportedAnalyzerException {
        TextHighlighter.Mode mode = options.wholeValues() ? TextHighlighter.Mode.VALUES : TextHighlighter.Mode.PASSAGES;
        int maxLength = options.wholeValues() ? Highlights.MAX_DOCUMENT_CHARS : Highlights.MAX_HIT_CHARS;
        if (options.maxLength() != maxLength) {
            throw new IllegalArgumentException(
                    "Lucene 10 reads " + maxLength + " characters of a field in this mode, not " + options.maxLength());
        }
        IndexSearcher searcher = new IndexSearcher(index.reader());
        // No cache: a query is run once.
        searcher.setQueryCache(null);
        try (Analyzer analyzer = Lucene10Analyzers.analyzer(options.analyzer())) {
            return Lucene10Index.withQuery(
                    parsed,
                    query ->
                            highlight(searcher, query, mode, options, analyzer, fields, docIds, maxPassages, progress));
        } catch (io.github.mfuerstenau.leia.core.InvalidQueryException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** Highlights with {@code query}, built first as a search builds it. */
    private Highlighted highlight(
            IndexSearcher searcher,
            Query query,
            TextHighlighter.Mode mode,
            Options options,
            Analyzer analyzer,
            List<String> fields,
            int[] docIds,
            int[] maxPassages,
            LongConsumer progress)
            throws IOException {
        TextHighlighter highlighter = Highlights.lucene(
                searcher,
                Queries.built(searcher.getIndexReader(), query),
                new HighlightSpec(1, options.requireFieldMatch(), analyzer));
        Map<String, OffsetSource> sources = new HashMap<>();
        Map<String, List<Optional<FieldText>>> found = new HashMap<>();
        long done = 0;
        for (int f = 0; f < fields.size(); f++) {
            String field = fields.get(f);
            List<Optional<FieldText>> texts = new ArrayList<>(docIds.length);
            for (int docId : docIds) {
                progress.accept(done++);
                TextHighlighter.Highlighted one =
                        highlighter.highlight(List.of(field), new int[] {docId}, new int[] {maxPassages[f]}, mode);
                Optional.ofNullable(one.sources().get(field))
                        .ifPresent(source -> sources.put(field, OffsetSource.valueOf(source.name())));
                List<Optional<TextHighlighter.FoundText>> perDoc = one.found().getOrDefault(field, List.of());
                texts.add(
                        perDoc.isEmpty() ? Optional.empty() : perDoc.getFirst().map(Lucene10Highlight::text));
            }
            found.put(field, List.copyOf(texts));
        }
        return new Highlighted(Map.copyOf(sources), Map.copyOf(found));
    }

    private static FieldText text(TextHighlighter.FoundText text) {
        return new FieldText(
                OffsetSource.valueOf(text.source().name()),
                text.content(),
                text.passages().stream()
                        .map(passage -> new Passage(
                                passage.start(),
                                passage.end(),
                                passage.score(),
                                passage.matchStarts(),
                                passage.matchEnds()))
                        .toList());
    }
}
