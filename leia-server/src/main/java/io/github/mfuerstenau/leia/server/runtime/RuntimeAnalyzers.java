package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The analyzer of a search as a runtime builds it (ADR 0045), for the classic and the standard parser,
 * More-Like-This (ADR 0066) and highlighting: the standard analyzer, a preset of the
 * runtime's own by its simple class name with stop words and the longest token, analyzers per field,
 * a token limit and auto stop words (ADR 0061). Anything else is refused with {@code
 * NOT_SUPPORTED}, naming what the runtime has.
 */
final class RuntimeAnalyzers {

    private RuntimeAnalyzers() {}

    /**
     * {@code analyzer} for {@code search} of {@code runtime}.
     *
     * @throws RpcException {@code INVALID_PARAMS} for an analyzer that is wrong anywhere, {@code
     *     NOT_SUPPORTED} for one the runtime doesn't have, {@code INVALID_ANALYZER} if a word list
     *     file can't be read
     */
    static AnalyzerSpec spec(Analyzer analyzer, LuceneRuntime runtime, Search search) throws RpcException {
        AnalyzerSpec spec = single(analyzer, runtime, search);
        if (!analyzer.fields().isEmpty()) {
            spec = new AnalyzerSpec.PerField(spec, fields(analyzer, runtime, search));
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

    private static Map<String, AnalyzerSpec> fields(Analyzer analyzer, LuceneRuntime runtime, Search search)
            throws RpcException {
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
            fields.put(name, single(own, runtime, search));
        }
        return fields;
    }

    private static AnalyzerSpec single(Analyzer analyzer, LuceneRuntime runtime, Search search) throws RpcException {
        if (analyzer.shingles().isPresent()) {
            throw unsupported("shingles", runtime, search);
        }
        AnalyzerSpec base = base(analyzer, runtime, search);
        // consumeAllTokens changes no token, only how far the stream is read.
        return analyzer.limitTokenCount().isPresent()
                ? new AnalyzerSpec.LimitTokenCount(
                        base, analyzer.limitTokenCount().get().maxTokenCount())
                : base;
    }

    private static AnalyzerSpec base(Analyzer analyzer, LuceneRuntime runtime, Search search) throws RpcException {
        long present = Stream.of(analyzer.standard(), analyzer.preset(), analyzer.custom())
                .filter(Optional::isPresent)
                .count();
        if (present != 1) {
            throw invalid("the analyzer needs exactly one of standard, preset and custom");
        }
        if (analyzer.standard().isPresent()) {
            return new AnalyzerSpec.Named("StandardAnalyzer", Optional.empty(), OptionalInt.empty());
        }
        if (analyzer.custom().isPresent()) {
            throw unsupported("custom analyzers", runtime, search);
        }
        PresetAnalyzer preset = analyzer.preset().orElseThrow();
        String name = preset.className().substring(preset.className().lastIndexOf('.') + 1);
        if (!search.analyzers().contains(name)) {
            throw unsupported("the analyzer " + name, runtime, search);
        }
        if (preset.stemExclusion().isPresent()
                || preset.stemOverride().isPresent()
                || preset.japanese().isPresent()
                || preset.korean().isPresent()
                || preset.collation().isPresent()
                || preset.japaneseCompletion().isPresent()) {
            throw unsupported("options of " + name + " other than stop words and the longest token", runtime, search);
        }
        return new AnalyzerSpec.Named(name, words(preset.stopwords()), preset.maxTokenLength());
    }

    /** The words of {@code set}: given, or read from its file, one a line, {@code #} lines left out. */
    private static Optional<List<String>> words(Optional<WordSet> wordSet) throws RpcException {
        if (wordSet.isEmpty()) {
            return Optional.empty();
        }
        WordSet set = wordSet.get();
        if (set.words().isPresent() == set.file().isPresent()) {
            throw invalid("the stop words need exactly one of words and file");
        }
        if (set.words().isPresent()) {
            return set.words();
        }
        String file = set.file().get();
        try {
            return Optional.of(Files.readAllLines(Path.of(file), StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.startsWith("#"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList());
        } catch (IOException | InvalidPathException e) {
            throw new RpcException(
                    ErrorCode.INVALID_ANALYZER,
                    "Invalid analyzer: cannot read the stop words file " + file + ": " + e.getMessage(),
                    e);
        }
    }

    private static RpcException invalid(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }

    /** {@code NOT_SUPPORTED} for {@code what}, naming the analyzers the runtime has. */
    static RpcException unsupported(String what, LuceneRuntime runtime, Search search) {
        return new RpcException(
                ErrorCode.NOT_SUPPORTED,
                "Not supported with Lucene " + runtime.luceneVersion() + ": " + what + "; it has the analyzers "
                        + String.join(", ", new TreeSet<>(search.analyzers())));
    }
}
