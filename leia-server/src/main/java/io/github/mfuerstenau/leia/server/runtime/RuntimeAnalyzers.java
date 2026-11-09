package io.github.mfuerstenau.leia.server.runtime;

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
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.runtime.support.Optionals;
import io.github.mfuerstenau.leia.server.RpcException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The analyzer of a request as the runtime interface has it (ADR 0045, ADR 0067), for the classic and
 * the standard parser, More-Like-This (ADR 0066), highlighting and the analysis tab: the twin of
 * {@code AnalyzerSpecs}, which builds leia-core's. The runtime builds it, reads its files, and refuses
 * what its Lucene lacks.
 */
public final class RuntimeAnalyzers {

    private RuntimeAnalyzers() {}

    /**
     * {@code analyzer} as a runtime's spec.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present, of the
     *     analyzer, of each word set and of a stem dictionary, for a field analyzer without a name,
     *     named twice or with fields or auto stop words of its own, or for a field with two sets of auto
     *     stop words; {@code INVALID_ANALYZER} for a path that isn't one
     */
    public static AnalyzerSpec spec(Analyzer analyzer) throws RpcException {
        AnalyzerSpec spec = single(analyzer);
        if (!analyzer.fields().isEmpty()) {
            spec = new AnalyzerSpec.PerField(spec, fieldSpecs(analyzer.fields()));
        }
        if (!analyzer.autoStopWords().isEmpty()) {
            // After the whole chain, as on Lucene 10 (ADR 0061).
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

    /** The analyzer of each field, which has neither fields nor auto stop words of its own. */
    private static Map<String, AnalyzerSpec> fieldSpecs(List<FieldAnalyzer> analyzers) throws RpcException {
        Map<String, AnalyzerSpec> fields = new LinkedHashMap<>();
        for (FieldAnalyzer field : analyzers) {
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
        return fields;
    }

    /**
     * The failure of a runtime that can't build an analyzer: {@code INVALID_ANALYZER} for a {@link
     * Search.InvalidAnalyzerException}, as on Lucene 10, and {@code NOT_SUPPORTED}, naming the
     * runtime's Lucene, for a {@link Search.UnsupportedAnalyzerException}.
     */
    static RpcException failed(LuceneRuntime runtime, Exception e) {
        if (e instanceof Search.InvalidAnalyzerException) {
            return new RpcException(ErrorCode.INVALID_ANALYZER, "Invalid analyzer: " + e.getMessage(), e);
        }
        return new RpcException(
                ErrorCode.NOT_SUPPORTED,
                "Not supported with Lucene " + runtime.luceneVersion() + ": " + e.getMessage(),
                e);
    }

    private static RpcException invalid(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }

    /** The analyzer's own alternative, in its wrappers. */
    private static AnalyzerSpec single(Analyzer analyzer) throws RpcException {
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
                                shingles.fillerToken()))
                        .orElse(null),
                analyzer.limitTokenCount()
                        .map(limit -> new AnalyzerSpec.LimitTokenCount(limit.maxTokenCount(), limit.consumeAllTokens()))
                        .orElse(null));
    }

    private static AnalyzerSpec base(Analyzer analyzer) throws RpcException {
        long present = Stream.of(analyzer.standard(), analyzer.preset(), analyzer.custom())
                .filter(Optional::isPresent)
                .count();
        if (present != 1) {
            throw invalid("the analyzer needs exactly one of standard, preset and custom");
        }
        if (analyzer.standard().isPresent()) {
            return new AnalyzerSpec.Standard();
        }
        if (analyzer.preset().isPresent()) {
            PresetAnalyzer preset = analyzer.preset().orElseThrow();
            return new AnalyzerSpec.Preset(
                    preset.className(),
                    wordSet("stop words", preset.stopwords()).orElse(null),
                    wordSet("stem exclusions", preset.stemExclusion()).orElse(null),
                    Optionals.orNull(preset.maxTokenLength()),
                    stemDictionary(preset.stemOverride()).orElse(null),
                    japanese(preset.japanese()).orElse(null),
                    korean(preset.korean()).orElse(null),
                    preset.collation().map(RuntimeAnalyzers::collation).orElse(null),
                    japaneseCompletion(preset.japaneseCompletion()).orElse(null));
        }
        CustomAnalyzer custom = analyzer.custom().orElseThrow();
        return new AnalyzerSpec.Custom(
                components(custom.charFilters()),
                component(custom.tokenizer()),
                components(custom.tokenFilters()),
                custom.configDir().isPresent()
                        ? path("config directory", custom.configDir().orElseThrow())
                        : null);
    }

    private static Optional<AnalyzerSpec.WordSet> wordSet(String what, Optional<WordSet> wordSet) throws RpcException {
        if (wordSet.isEmpty()) {
            return Optional.empty();
        }
        WordSet set = wordSet.orElseThrow();
        if (set.words().isPresent() == set.file().isPresent()) {
            throw invalid("the " + what + " need exactly one of words and file");
        }
        if (set.words().isPresent()) {
            return Optional.of(new AnalyzerSpec.WordSet.Words(set.words().orElseThrow()));
        }
        return Optional.of(new AnalyzerSpec.WordSet.File(path(what, set.file().orElseThrow())));
    }

    private static Optional<AnalyzerSpec.StemDictionary> stemDictionary(Optional<StemDictionary> dictionary)
            throws RpcException {
        if (dictionary.isEmpty()) {
            return Optional.empty();
        }
        StemDictionary given = dictionary.orElseThrow();
        if (given.entries().isPresent() == given.file().isPresent()) {
            throw invalid("the stem overrides need exactly one of entries and file");
        }
        if (given.entries().isPresent()) {
            return Optional.of(
                    new AnalyzerSpec.StemDictionary.Entries(given.entries().orElseThrow()));
        }
        return Optional.of(new AnalyzerSpec.StemDictionary.File(
                path("stem overrides", given.file().orElseThrow())));
    }

    private static Optional<AnalyzerSpec.Japanese> japanese(Optional<JapaneseOptions> options) throws RpcException {
        if (options.isEmpty()) {
            return Optional.empty();
        }
        JapaneseOptions given = options.orElseThrow();
        return Optional.of(new AnalyzerSpec.Japanese(
                userDictionary(given.userDictionary()).orElse(null),
                given.mode()
                        .map(mode -> switch (mode) {
                            case NORMAL -> AnalyzerSpec.JapaneseMode.NORMAL;
                            case SEARCH -> AnalyzerSpec.JapaneseMode.SEARCH;
                            case EXTENDED -> AnalyzerSpec.JapaneseMode.EXTENDED;
                        })
                        .orElse(null),
                wordSet("stop tags", given.stopTags()).orElse(null)));
    }

    private static Optional<AnalyzerSpec.Korean> korean(Optional<KoreanOptions> options) throws RpcException {
        if (options.isEmpty()) {
            return Optional.empty();
        }
        KoreanOptions given = options.orElseThrow();
        return Optional.of(new AnalyzerSpec.Korean(
                userDictionary(given.userDictionary()).orElse(null),
                given.decompoundMode()
                        .map(mode -> switch (mode) {
                            case NONE -> AnalyzerSpec.KoreanDecompoundMode.NONE;
                            case DISCARD -> AnalyzerSpec.KoreanDecompoundMode.DISCARD;
                            case MIXED -> AnalyzerSpec.KoreanDecompoundMode.MIXED;
                        })
                        .orElse(null),
                wordSet("stop tags", given.stopTags()).orElse(null),
                given.outputUnknownUnigrams().orElse(null)));
    }

    private static AnalyzerSpec.Collation collation(Collation collation) {
        return new AnalyzerSpec.Collation(
                collation.locale(),
                collation
                        .strength()
                        .map(strength -> switch (strength) {
                            case PRIMARY -> AnalyzerSpec.CollationStrength.PRIMARY;
                            case SECONDARY -> AnalyzerSpec.CollationStrength.SECONDARY;
                            case TERTIARY -> AnalyzerSpec.CollationStrength.TERTIARY;
                            case QUATERNARY -> AnalyzerSpec.CollationStrength.QUATERNARY;
                            case IDENTICAL -> AnalyzerSpec.CollationStrength.IDENTICAL;
                        })
                        .orElse(null),
                collation
                        .decomposition()
                        .map(decomposition -> switch (decomposition) {
                            case NO -> AnalyzerSpec.CollationDecomposition.NO;
                            case CANONICAL -> AnalyzerSpec.CollationDecomposition.CANONICAL;
                            case FULL -> AnalyzerSpec.CollationDecomposition.FULL;
                        })
                        .orElse(null));
    }

    private static Optional<AnalyzerSpec.JapaneseCompletion> japaneseCompletion(
            Optional<JapaneseCompletionOptions> options) throws RpcException {
        if (options.isEmpty()) {
            return Optional.empty();
        }
        JapaneseCompletionOptions given = options.orElseThrow();
        return Optional.of(new AnalyzerSpec.JapaneseCompletion(
                userDictionary(given.userDictionary()).orElse(null),
                given.mode()
                        .map(mode -> switch (mode) {
                            case INDEX -> AnalyzerSpec.JapaneseCompletionMode.INDEX;
                            case QUERY -> AnalyzerSpec.JapaneseCompletionMode.QUERY;
                        })
                        .orElse(null)));
    }

    private static Optional<Path> userDictionary(Optional<String> file) throws RpcException {
        return file.isPresent() ? Optional.of(path("user dictionary", file.orElseThrow())) : Optional.empty();
    }

    /** {@code path} as a path; {@code what} names it in leia-core's message if it isn't one. */
    private static Path path(String what, String path) throws RpcException {
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            throw new RpcException(ErrorCode.INVALID_ANALYZER, "Invalid analyzer: " + what + ": " + e.getMessage(), e);
        }
    }

    private static List<AnalyzerSpec.Component> components(List<AnalysisComponent> components) {
        return components.stream().map(RuntimeAnalyzers::component).toList();
    }

    private static AnalyzerSpec.Component component(AnalysisComponent component) {
        return new AnalyzerSpec.Component(component.name(), component.params());
    }
}
