package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.core.AnalyzerSpec;
import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.core.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseCompletionOptions;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseOptions;
import io.github.mfuerstenau.leia.protocol.analysis.KoreanOptions;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StemDictionary;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.server.RpcException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Builds the Lucene analyzer for an {@link Analyzer} of a request (ADR 0014, ADR 0015, ADR 0017, ADR
 * 0023, ADR 0038, ADR 0039).
 */
public final class AnalyzerSpecs {

    private AnalyzerSpecs() {}

    /**
     * A new Lucene analyzer for {@code analyzer}, which the caller closes.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present, of the
     *     analyzer, of each word set and of a stem dictionary, for a field analyzer without a name,
     *     named twice or with fields or auto stop words of its own, or for a field with two sets of
     *     auto stop words; {@code INVALID_ANALYZER} if the analyzer can't be built
     */
    public static org.apache.lucene.analysis.Analyzer build(Analyzer analyzer) throws RpcException {
        try {
            return Analyzers.build(spec(analyzer));
        } catch (InvalidAnalyzerException e) {
            throw new RpcException(ErrorCode.INVALID_ANALYZER, "Invalid analyzer: " + e.getMessage(), e);
        }
    }

    private static AnalyzerSpec spec(Analyzer analyzer) throws RpcException, InvalidAnalyzerException {
        AnalyzerSpec spec = single(analyzer);
        if (!analyzer.fields().isEmpty()) {
            Map<String, AnalyzerSpec> fields = new LinkedHashMap<>();
            for (FieldAnalyzer field : analyzer.fields()) {
                String name = field.field();
                if (name.isEmpty()) {
                    throw invalid("a field analyzer needs a field name");
                }
                if (fields.containsKey(name)) {
                    throw invalid("field '" + name + "' has more than one analyzer");
                }
                Analyzer own = field.analyzer();
                if (!own.fields().isEmpty() || !own.autoStopWords().isEmpty()) {
                    throw invalid("the analyzer of field '" + name + "' can't have fields or auto stop words");
                }
                fields.put(name, single(own));
            }
            spec = new AnalyzerSpec.PerField(spec, fields);
        }
        if (!analyzer.autoStopWords().isEmpty()) {
            Map<String, List<String>> words = new LinkedHashMap<>();
            for (FieldStopWords field : analyzer.autoStopWords()) {
                if (words.putIfAbsent(field.field(), field.words()) != null) {
                    throw invalid("field '" + field.field() + "' has more than one set of auto stop words");
                }
            }
            spec = new AnalyzerSpec.AutoStopWords(spec, words);
        }
        return spec;
    }

    private static RpcException invalid(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }

    /** The analyzer's own alternative, in its wrappers. */
    private static AnalyzerSpec single(Analyzer analyzer) throws RpcException, InvalidAnalyzerException {
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
                    korean(preset.korean()),
                    preset.collation().map(AnalyzerSpecs::collation),
                    japaneseCompletion(preset.japaneseCompletion()));
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

    private static AnalyzerSpec.Collation collation(Collation collation) {
        return new AnalyzerSpec.Collation(
                collation.locale(),
                collation.strength().map(strength -> switch (strength) {
                    case PRIMARY -> AnalyzerSpec.CollationStrength.PRIMARY;
                    case SECONDARY -> AnalyzerSpec.CollationStrength.SECONDARY;
                    case TERTIARY -> AnalyzerSpec.CollationStrength.TERTIARY;
                    case QUATERNARY -> AnalyzerSpec.CollationStrength.QUATERNARY;
                    case IDENTICAL -> AnalyzerSpec.CollationStrength.IDENTICAL;
                }),
                collation.decomposition().map(decomposition -> switch (decomposition) {
                    case NO -> AnalyzerSpec.CollationDecomposition.NO;
                    case CANONICAL -> AnalyzerSpec.CollationDecomposition.CANONICAL;
                    case FULL -> AnalyzerSpec.CollationDecomposition.FULL;
                }));
    }

    private static Optional<AnalyzerSpec.JapaneseCompletion> japaneseCompletion(
            Optional<JapaneseCompletionOptions> options) throws InvalidAnalyzerException {
        if (options.isEmpty()) {
            return Optional.empty();
        }
        JapaneseCompletionOptions given = options.orElseThrow();
        return Optional.of(new AnalyzerSpec.JapaneseCompletion(
                userDictionary(given.userDictionary()), given.mode().map(mode -> switch (mode) {
                    case INDEX -> AnalyzerSpec.JapaneseCompletionMode.INDEX;
                    case QUERY -> AnalyzerSpec.JapaneseCompletionMode.QUERY;
                })));
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
