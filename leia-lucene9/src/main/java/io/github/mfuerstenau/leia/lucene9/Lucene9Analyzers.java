package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.runtime.Search.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.shingle.ShingleAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

/**
 * The analyzers of Lucene 9.12 for the query parsers, More-Like-This and highlighting (ADR 0049, ADR
 * 0067), as leia-core builds them on Lucene 10: the standard analyzer, a preset ({@link
 * Lucene9Presets}) or a custom analyzer ({@link Lucene9CustomAnalyzers}), each in shingles and a token
 * limit, then analyzers per field and auto stop words.
 */
final class Lucene9Analyzers {

    private Lucene9Analyzers() {}

    /**
     * Builds {@code spec}; the caller closes it.
     *
     * @throws UnsupportedAnalyzerException for what 9.12 doesn't have
     * @throws InvalidAnalyzerException for what is wrong on Lucene 10 too, with leia-core's message
     */
    static Analyzer analyzer(AnalyzerSpec spec) throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        return switch (spec) {
            case AnalyzerSpec.Standard _ -> new StandardAnalyzer();
            case AnalyzerSpec.Preset preset -> Lucene9Presets.create(preset);
            case AnalyzerSpec.Custom custom -> Lucene9CustomAnalyzers.create(custom);
            case AnalyzerSpec.Wrapped wrapped -> wrapped(wrapped);
            case AnalyzerSpec.PerField perField -> perField(perField);
            case AnalyzerSpec.AutoStopWords(AnalyzerSpec analyzer, Map<String, List<String>> words) ->
                new Lucene9AutoStopWordsAnalyzer(analyzer(analyzer), words);
        };
    }

    /** The default analyzer and each field's own; one that fails closes those built before. */
    private static Analyzer perField(AnalyzerSpec.PerField spec)
            throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        Analyzer fallback = analyzer(spec.fallback());
        Map<String, Analyzer> fields = new TreeMap<>();
        try {
            for (Map.Entry<String, AnalyzerSpec> field : new TreeMap<>(spec.fields()).entrySet()) {
                fields.put(field.getKey(), fieldAnalyzer(field.getKey(), field.getValue()));
            }
        } catch (UnsupportedAnalyzerException | InvalidAnalyzerException | RuntimeException e) {
            fields.values().forEach(Analyzer::close);
            fallback.close();
            throw e;
        }
        return new PerFieldAnalyzerWrapper(fallback, fields);
    }

    /** The analyzer of {@code field}, with the field's name in the message of an invalid one. */
    private static Analyzer fieldAnalyzer(String field, AnalyzerSpec spec)
            throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        try {
            return analyzer(spec);
        } catch (InvalidAnalyzerException e) {
            throw new InvalidAnalyzerException("field '" + field + "': " + e.getMessage(), e);
        }
    }

    /** The analyzer in Lucene's shingle wrapper, then in its token limit, checked first as leia-core checks them. */
    private static Analyzer wrapped(AnalyzerSpec.Wrapped spec)
            throws UnsupportedAnalyzerException, InvalidAnalyzerException {
        if (spec.shingles() != null) {
            AnalyzerSpec.Shingles shingles =
                    Optional.ofNullable(spec.shingles()).orElseThrow();
            if (shingles.minShingleSize() < 2) {
                throw new InvalidAnalyzerException(
                        "shingles: the minimum size must be at least 2, not " + shingles.minShingleSize(), null);
            }
            if (shingles.maxShingleSize() < shingles.minShingleSize()) {
                throw new InvalidAnalyzerException(
                        "shingles: the maximum size must be at least the minimum size, " + shingles.minShingleSize()
                                + ", not " + shingles.maxShingleSize(),
                        null);
            }
        }
        if (spec.limitTokenCount() != null
                && Optional.ofNullable(spec.limitTokenCount()).orElseThrow().maxTokenCount() < 1) {
            throw new InvalidAnalyzerException(
                    "token limit: the maximum token count must be at least 1, not "
                            + Optional.ofNullable(spec.limitTokenCount())
                                    .orElseThrow()
                                    .maxTokenCount(),
                    null);
        }
        Analyzer wrapped = analyzer(spec.analyzer());
        if (spec.shingles() != null) {
            AnalyzerSpec.Shingles shingles =
                    Optional.ofNullable(spec.shingles()).orElseThrow();
            wrapped = new ShingleAnalyzerWrapper(
                    wrapped,
                    shingles.minShingleSize(),
                    shingles.maxShingleSize(),
                    shingles.tokenSeparator(),
                    shingles.outputUnigrams(),
                    shingles.outputUnigramsIfNoShingles(),
                    shingles.fillerToken());
        }
        if (spec.limitTokenCount() != null) {
            AnalyzerSpec.LimitTokenCount limit =
                    Optional.ofNullable(spec.limitTokenCount()).orElseThrow();
            wrapped = new LimitTokenCountAnalyzer(wrapped, limit.maxTokenCount(), limit.consumeAllTokens());
        }
        return wrapped;
    }
}
