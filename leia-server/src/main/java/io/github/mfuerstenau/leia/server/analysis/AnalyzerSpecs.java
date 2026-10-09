package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.core.AnalyzerSpec;
import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.core.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseOptions;
import io.github.mfuerstenau.leia.protocol.analysis.KoreanOptions;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StemDictionary;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.server.RpcException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Builds the Lucene analyzer for an {@link Analyzer} of a request (ADR 0014, ADR 0015, ADR 0017, ADR
 * 0023).
 */
public final class AnalyzerSpecs {

    private AnalyzerSpecs() {}

    /**
     * A new Lucene analyzer for {@code analyzer}, which the caller closes.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present, of the
     *     analyzer, of each word set and of a stem dictionary, {@code INVALID_ANALYZER} if the
     *     analyzer can't be built
     */
    public static org.apache.lucene.analysis.Analyzer build(Analyzer analyzer) throws RpcException {
        try {
            return Analyzers.build(spec(analyzer));
        } catch (InvalidAnalyzerException e) {
            throw new RpcException(ErrorCode.INVALID_ANALYZER, "Invalid analyzer: " + e.getMessage(), e);
        }
    }

    private static AnalyzerSpec spec(Analyzer analyzer) throws RpcException, InvalidAnalyzerException {
        AnalyzerSpec base = base(analyzer);
        if (analyzer.shingles().isEmpty() && analyzer.limitTokenCount().isEmpty()) {
            return base;
        }
        return new AnalyzerSpec.Wrapped(
                base,
                analyzer.shingles()
                        .map(shingles -> new AnalyzerSpec.Shingles(
                                shingles.minShingleSize(),
                                shingles.maxShingleSize(),
                                shingles.tokenSeparator(),
                                shingles.outputUnigrams(),
                                shingles.outputUnigramsIfNoShingles(),
                                shingles.fillerToken())),
                analyzer.limitTokenCount()
                        .map(limit ->
                                new AnalyzerSpec.LimitTokenCount(limit.maxTokenCount(), limit.consumeAllTokens())));
    }

    private static AnalyzerSpec base(Analyzer analyzer) throws RpcException, InvalidAnalyzerException {
        long present = Stream.of(analyzer.standard(), analyzer.preset(), analyzer.custom())
                .filter(Optional::isPresent)
                .count();
        if (present != 1) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the analyzer needs exactly one of standard, preset and custom");
        }
        if (analyzer.standard().isPresent()) {
            return new AnalyzerSpec.Standard();
        }
        if (analyzer.preset().isPresent()) {
            PresetAnalyzer preset = analyzer.preset().orElseThrow();
            return new AnalyzerSpec.Preset(
                    preset.className(),
                    wordSet("stop words", preset.stopwords()),
                    wordSet("stem exclusions", preset.stemExclusion()),
                    preset.maxTokenLength(),
                    stemDictionary(preset.stemOverride()),
                    japanese(preset.japanese()),
                    korean(preset.korean()));
        }
        CustomAnalyzer custom = analyzer.custom().orElseThrow();
        return new AnalyzerSpec.Custom(
                components(custom.charFilters()),
                component(custom.tokenizer()),
                components(custom.tokenFilters()),
                configDir(custom.configDir()));
    }

    private static Optional<AnalyzerSpec.WordSet> wordSet(String what, Optional<WordSet> wordSet)
            throws RpcException, InvalidAnalyzerException {
        if (wordSet.isEmpty()) {
            return Optional.empty();
        }
        WordSet set = wordSet.orElseThrow();
        if (set.words().isPresent() == set.file().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: the " + what + " need exactly one of words and file");
        }
        if (set.words().isPresent()) {
            return Optional.of(new AnalyzerSpec.WordSet.Words(set.words().orElseThrow()));
        }
        return Optional.of(new AnalyzerSpec.WordSet.File(path(what, set.file().orElseThrow())));
    }

    private static Optional<AnalyzerSpec.StemDictionary> stemDictionary(Optional<StemDictionary> dictionary)
            throws RpcException, InvalidAnalyzerException {
        if (dictionary.isEmpty()) {
            return Optional.empty();
        }
        StemDictionary given = dictionary.orElseThrow();
        if (given.entries().isPresent() == given.file().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the stem overrides need exactly one of entries and file");
        }
        if (given.entries().isPresent()) {
            return Optional.of(
                    new AnalyzerSpec.StemDictionary.Entries(given.entries().orElseThrow()));
        }
        return Optional.of(new AnalyzerSpec.StemDictionary.File(
                path("stem overrides", given.file().orElseThrow())));
    }

    private static Optional<AnalyzerSpec.Japanese> japanese(Optional<JapaneseOptions> options)
            throws RpcException, InvalidAnalyzerException {
        if (options.isEmpty()) {
            return Optional.empty();
        }
        JapaneseOptions given = options.orElseThrow();
        return Optional.of(new AnalyzerSpec.Japanese(
                userDictionary(given.userDictionary()),
                given.mode().map(mode -> switch (mode) {
                    case NORMAL -> AnalyzerSpec.JapaneseMode.NORMAL;
                    case SEARCH -> AnalyzerSpec.JapaneseMode.SEARCH;
                    case EXTENDED -> AnalyzerSpec.JapaneseMode.EXTENDED;
                }),
                wordSet("stop tags", given.stopTags())));
    }

    private static Optional<AnalyzerSpec.Korean> korean(Optional<KoreanOptions> options)
            throws RpcException, InvalidAnalyzerException {
        if (options.isEmpty()) {
            return Optional.empty();
        }
        KoreanOptions given = options.orElseThrow();
        return Optional.of(new AnalyzerSpec.Korean(
                userDictionary(given.userDictionary()),
                given.decompoundMode().map(mode -> switch (mode) {
                    case NONE -> AnalyzerSpec.KoreanDecompoundMode.NONE;
                    case DISCARD -> AnalyzerSpec.KoreanDecompoundMode.DISCARD;
                    case MIXED -> AnalyzerSpec.KoreanDecompoundMode.MIXED;
                }),
                wordSet("stop tags", given.stopTags()),
                given.outputUnknownUnigrams()));
    }

    private static Optional<Path> userDictionary(Optional<String> file) throws InvalidAnalyzerException {
        return file.isPresent() ? Optional.of(path("user dictionary", file.orElseThrow())) : Optional.empty();
    }

    private static Path path(String what, String path) throws InvalidAnalyzerException {
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            throw new InvalidAnalyzerException(what + ": " + e.getMessage(), e);
        }
    }

    private static Optional<Path> configDir(Optional<String> configDir) throws InvalidAnalyzerException {
        try {
            return configDir.map(Path::of);
        } catch (InvalidPathException e) {
            throw new InvalidAnalyzerException("config directory: " + e.getMessage(), e);
        }
    }

    private static List<AnalyzerSpec.Component> components(List<AnalysisComponent> components) {
        return components.stream().map(AnalyzerSpecs::component).toList();
    }

    private static AnalyzerSpec.Component component(AnalysisComponent component) {
        return new AnalyzerSpec.Component(component.name(), component.params());
    }
}
