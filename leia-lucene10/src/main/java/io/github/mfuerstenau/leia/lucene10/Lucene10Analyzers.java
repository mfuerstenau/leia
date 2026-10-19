package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.AnalysisComponents.AvailableAnalyzer;
import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.core.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import io.github.mfuerstenau.leia.runtime.Search.UnsupportedAnalyzerException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;

/**
 * The analyzers of a search, built by leia-core's {@link Analyzers} (ADR 0065): a named one is
 * leia-core's preset of that simple class name (ADR 0015), the presets of the extension jars
 * included.
 */
final class Lucene10Analyzers {

    private Lucene10Analyzers() {}

    /** The presets by simple class name; of two with the same name, the first. */
    private static Map<String, String> presets() {
        Map<String, String> presets = new LinkedHashMap<>();
        for (AvailableAnalyzer analyzer : Analyzers.components().analyzers()) {
            presets.putIfAbsent(analyzer.name(), analyzer.className());
        }
        return presets;
    }

    static Set<String> names() {
        return Set.copyOf(presets().keySet());
    }

    /** Builds {@code spec}; the caller closes it. */
    static Analyzer analyzer(AnalyzerSpec spec) throws UnsupportedAnalyzerException {
        try {
            return Analyzers.build(coreSpec(spec, presets()));
        } catch (InvalidAnalyzerException e) {
            throw new UnsupportedAnalyzerException(e.getMessage());
        }
    }

    private static io.github.mfuerstenau.leia.core.AnalyzerSpec coreSpec(AnalyzerSpec spec, Map<String, String> presets)
            throws UnsupportedAnalyzerException {
        return switch (spec) {
            case AnalyzerSpec.Named named -> {
                if (named.name().equals("StandardAnalyzer")
                        && named.stopwords().isEmpty()
                        && named.maxTokenLength().isEmpty()) {
                    yield new io.github.mfuerstenau.leia.core.AnalyzerSpec.Standard();
                }
                String className = presets.get(named.name());
                if (className == null) {
                    throw new UnsupportedAnalyzerException("no analyzer " + named.name());
                }
                yield new io.github.mfuerstenau.leia.core.AnalyzerSpec.Preset(
                        className,
                        named.stopwords().map(io.github.mfuerstenau.leia.core.AnalyzerSpec.WordSet.Words::new),
                        Optional.empty(),
                        named.maxTokenLength(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
            }
            case AnalyzerSpec.PerField(AnalyzerSpec fallback, Map<String, AnalyzerSpec> fields) -> {
                Map<String, io.github.mfuerstenau.leia.core.AnalyzerSpec> coreFields = new LinkedHashMap<>();
                for (Map.Entry<String, AnalyzerSpec> field : fields.entrySet()) {
                    coreFields.put(field.getKey(), coreSpec(field.getValue(), presets));
                }
                yield new io.github.mfuerstenau.leia.core.AnalyzerSpec.PerField(
                        coreSpec(fallback, presets), coreFields);
            }
            case AnalyzerSpec.LimitTokenCount(AnalyzerSpec analyzer, int maxTokenCount) ->
                new io.github.mfuerstenau.leia.core.AnalyzerSpec.Wrapped(
                        coreSpec(analyzer, presets),
                        Optional.empty(),
                        Optional.of(new io.github.mfuerstenau.leia.core.AnalyzerSpec.LimitTokenCount(
                                maxTokenCount, false)));
            case AnalyzerSpec.AutoStopWords(AnalyzerSpec analyzer, Map<String, List<String>> words) ->
                new io.github.mfuerstenau.leia.core.AnalyzerSpec.AutoStopWords(coreSpec(analyzer, presets), words);
        };
    }
}
