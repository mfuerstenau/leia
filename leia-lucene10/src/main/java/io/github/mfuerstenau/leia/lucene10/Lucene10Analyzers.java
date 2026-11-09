package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.AnalyzerSpec;
import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Search.InvalidAnalyzerException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.jspecify.annotations.Nullable;

/**
 * The analyzers of a search and of the analysis tab, built by leia-core's {@link Analyzers} (ADR 0065,
 * ADR 0067): the runtime's spec is leia-core's, one to one, so a preset is leia-core's of that class
 * name, the presets of the extension jars included, and every failure is leia-core's.
 */
final class Lucene10Analyzers {

    private Lucene10Analyzers() {}

    /** Builds {@code spec}; the caller closes it. */
    static Analyzer analyzer(Search.AnalyzerSpec spec) throws InvalidAnalyzerException {
        try {
            return Analyzers.build(coreSpec(spec));
        } catch (io.github.mfuerstenau.leia.core.InvalidAnalyzerException e) {
            throw new InvalidAnalyzerException(e.getMessage(), e);
        }
    }

    static AnalyzerSpec coreSpec(Search.AnalyzerSpec spec) {
        return switch (spec) {
            case Search.AnalyzerSpec.Standard _ -> new AnalyzerSpec.Standard();
            case Search.AnalyzerSpec.Preset preset ->
                new AnalyzerSpec.Preset(
                        preset.className(),
                        wordSet(preset.stopwords()),
                        wordSet(preset.stemExclusion()),
                        preset.maxTokenLength(),
                        stemDictionary(preset.stemOverride()),
                        japanese(preset.japanese()),
                        korean(preset.korean()),
                        collation(preset.collation()),
                        japaneseCompletion(preset.japaneseCompletion()));
            case Search.AnalyzerSpec.Custom custom ->
                new AnalyzerSpec.Custom(
                        components(custom.charFilters()),
                        component(custom.tokenizer()),
                        components(custom.tokenFilters()),
                        custom.configDir());
            case Search.AnalyzerSpec.Wrapped wrapped ->
                new AnalyzerSpec.Wrapped(
                        coreSpec(wrapped.analyzer()), shingles(wrapped.shingles()), limit(wrapped.limitTokenCount()));
            case Search.AnalyzerSpec.PerField(
                    Search.AnalyzerSpec fallback,
                    Map<String, Search.AnalyzerSpec> fields) -> {
                Map<String, AnalyzerSpec> coreFields = new LinkedHashMap<>();
                fields.forEach((field, analyzer) -> coreFields.put(field, coreSpec(analyzer)));
                yield new AnalyzerSpec.PerField(coreSpec(fallback), coreFields);
            }
            case Search.AnalyzerSpec.AutoStopWords(Search.AnalyzerSpec analyzer, Map<String, List<String>> words) ->
                new AnalyzerSpec.AutoStopWords(coreSpec(analyzer), words);
        };
    }

    private static AnalyzerSpec.@Nullable WordSet wordSet(Search.AnalyzerSpec.@Nullable WordSet set) {
        return switch (set) {
            case null -> null;
            case Search.AnalyzerSpec.WordSet.Words words -> new AnalyzerSpec.WordSet.Words(words.words());
            case Search.AnalyzerSpec.WordSet.File file -> new AnalyzerSpec.WordSet.File(file.path());
        };
    }

    private static AnalyzerSpec.@Nullable StemDictionary stemDictionary(
            Search.AnalyzerSpec.@Nullable StemDictionary dictionary) {
        return switch (dictionary) {
            case null -> null;
            case Search.AnalyzerSpec.StemDictionary.Entries entries ->
                new AnalyzerSpec.StemDictionary.Entries(entries.entries());
            case Search.AnalyzerSpec.StemDictionary.File file -> new AnalyzerSpec.StemDictionary.File(file.path());
        };
    }

    private static AnalyzerSpec.@Nullable Japanese japanese(Search.AnalyzerSpec.@Nullable Japanese japanese) {
        if (japanese == null) {
            return null;
        }
        Search.AnalyzerSpec.JapaneseMode mode = japanese.mode();
        return new AnalyzerSpec.Japanese(
                japanese.userDictionary(),
                mode != null ? AnalyzerSpec.JapaneseMode.valueOf(mode.name()) : null,
                wordSet(japanese.stopTags()));
    }

    private static AnalyzerSpec.@Nullable Korean korean(Search.AnalyzerSpec.@Nullable Korean korean) {
        if (korean == null) {
            return null;
        }
        Search.AnalyzerSpec.KoreanDecompoundMode mode = korean.decompoundMode();
        return new AnalyzerSpec.Korean(
                korean.userDictionary(),
                mode != null ? AnalyzerSpec.KoreanDecompoundMode.valueOf(mode.name()) : null,
                wordSet(korean.stopTags()),
                korean.outputUnknownUnigrams());
    }

    private static AnalyzerSpec.@Nullable Collation collation(Search.AnalyzerSpec.@Nullable Collation collation) {
        if (collation == null) {
            return null;
        }
        Search.AnalyzerSpec.CollationStrength strength = collation.strength();
        Search.AnalyzerSpec.CollationDecomposition decomposition = collation.decomposition();
        return new AnalyzerSpec.Collation(
                collation.locale(),
                strength != null ? AnalyzerSpec.CollationStrength.valueOf(strength.name()) : null,
                decomposition != null ? AnalyzerSpec.CollationDecomposition.valueOf(decomposition.name()) : null);
    }

    private static AnalyzerSpec.@Nullable JapaneseCompletion japaneseCompletion(
            Search.AnalyzerSpec.@Nullable JapaneseCompletion completion) {
        if (completion == null) {
            return null;
        }
        Search.AnalyzerSpec.JapaneseCompletionMode mode = completion.mode();
        return new AnalyzerSpec.JapaneseCompletion(
                completion.userDictionary(),
                mode != null ? AnalyzerSpec.JapaneseCompletionMode.valueOf(mode.name()) : null);
    }

    private static AnalyzerSpec.@Nullable Shingles shingles(Search.AnalyzerSpec.@Nullable Shingles shingles) {
        return shingles == null
                ? null
                : new AnalyzerSpec.Shingles(
                        shingles.minShingleSize(),
                        shingles.maxShingleSize(),
                        shingles.tokenSeparator(),
                        shingles.outputUnigrams(),
                        shingles.outputUnigramsIfNoShingles(),
                        shingles.fillerToken());
    }

    private static AnalyzerSpec.@Nullable LimitTokenCount limit(Search.AnalyzerSpec.@Nullable LimitTokenCount limit) {
        return limit == null ? null : new AnalyzerSpec.LimitTokenCount(limit.maxTokenCount(), limit.consumeAllTokens());
    }

    private static List<AnalyzerSpec.Component> components(List<Search.AnalyzerSpec.Component> components) {
        return components.stream().map(Lucene10Analyzers::component).toList();
    }

    private static AnalyzerSpec.Component component(Search.AnalyzerSpec.Component component) {
        return new AnalyzerSpec.Component(component.name(), component.params());
    }
}
