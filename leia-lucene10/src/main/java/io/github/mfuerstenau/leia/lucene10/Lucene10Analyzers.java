package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.AnalyzerSpec;
import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Search.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.runtime.support.Optionals;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.analysis.Analyzer;

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
                        Optional.ofNullable(preset.stopwords()).map(Lucene10Analyzers::wordSet),
                        Optional.ofNullable(preset.stemExclusion()).map(Lucene10Analyzers::wordSet),
                        Optionals.optionalInt(preset.maxTokenLength()),
                        Optional.ofNullable(preset.stemOverride()).map(Lucene10Analyzers::stemDictionary),
                        Optional.ofNullable(preset.japanese())
                                .map(japanese -> new AnalyzerSpec.Japanese(
                                        Optional.ofNullable(japanese.userDictionary()),
                                        Optional.ofNullable(japanese.mode())
                                                .map(mode -> AnalyzerSpec.JapaneseMode.valueOf(mode.name())),
                                        Optional.ofNullable(japanese.stopTags()).map(Lucene10Analyzers::wordSet))),
                        Optional.ofNullable(preset.korean())
                                .map(korean -> new AnalyzerSpec.Korean(
                                        Optional.ofNullable(korean.userDictionary()),
                                        Optional.ofNullable(korean.decompoundMode())
                                                .map(mode -> AnalyzerSpec.KoreanDecompoundMode.valueOf(mode.name())),
                                        Optional.ofNullable(korean.stopTags()).map(Lucene10Analyzers::wordSet),
                                        Optional.ofNullable(korean.outputUnknownUnigrams()))),
                        Optional.ofNullable(preset.collation())
                                .map(collation -> new AnalyzerSpec.Collation(
                                        collation.locale(),
                                        Optional.ofNullable(collation.strength())
                                                .map(strength ->
                                                        AnalyzerSpec.CollationStrength.valueOf(strength.name())),
                                        Optional.ofNullable(collation.decomposition())
                                                .map(decomposition -> AnalyzerSpec.CollationDecomposition.valueOf(
                                                        decomposition.name())))),
                        Optional.ofNullable(preset.japaneseCompletion())
                                .map(completion -> new AnalyzerSpec.JapaneseCompletion(
                                        Optional.ofNullable(completion.userDictionary()),
                                        Optional.ofNullable(completion.mode())
                                                .map(mode ->
                                                        AnalyzerSpec.JapaneseCompletionMode.valueOf(mode.name())))));
            case Search.AnalyzerSpec.Custom custom ->
                new AnalyzerSpec.Custom(
                        components(custom.charFilters()),
                        component(custom.tokenizer()),
                        components(custom.tokenFilters()),
                        Optional.ofNullable(custom.configDir()));
            case Search.AnalyzerSpec.Wrapped wrapped ->
                new AnalyzerSpec.Wrapped(
                        coreSpec(wrapped.analyzer()),
                        Optional.ofNullable(wrapped.shingles())
                                .map(shingles -> new AnalyzerSpec.Shingles(
                                        shingles.minShingleSize(),
                                        shingles.maxShingleSize(),
                                        shingles.tokenSeparator(),
                                        shingles.outputUnigrams(),
                                        shingles.outputUnigramsIfNoShingles(),
                                        shingles.fillerToken())),
                        Optional.ofNullable(wrapped.limitTokenCount())
                                .map(limit -> new AnalyzerSpec.LimitTokenCount(
                                        limit.maxTokenCount(), limit.consumeAllTokens())));
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

    private static AnalyzerSpec.WordSet wordSet(Search.AnalyzerSpec.WordSet set) {
        return switch (set) {
            case Search.AnalyzerSpec.WordSet.Words words -> new AnalyzerSpec.WordSet.Words(words.words());
            case Search.AnalyzerSpec.WordSet.File file -> new AnalyzerSpec.WordSet.File(file.path());
        };
    }

    private static AnalyzerSpec.StemDictionary stemDictionary(Search.AnalyzerSpec.StemDictionary dictionary) {
        return switch (dictionary) {
            case Search.AnalyzerSpec.StemDictionary.Entries entries ->
                new AnalyzerSpec.StemDictionary.Entries(entries.entries());
            case Search.AnalyzerSpec.StemDictionary.File file -> new AnalyzerSpec.StemDictionary.File(file.path());
        };
    }

    private static List<AnalyzerSpec.Component> components(List<Search.AnalyzerSpec.Component> components) {
        return components.stream().map(Lucene10Analyzers::component).toList();
    }

    private static AnalyzerSpec.Component component(Search.AnalyzerSpec.Component component) {
        return new AnalyzerSpec.Component(component.name(), component.params());
    }
}
