package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * The analyzers that tools name: by simple or class name, with their default settings (ADR 0104),
 * for {@code search} and {@code analyze}.
 */
final class Analyzers {

    private static final String STANDARD_ANALYZER = "StandardAnalyzer";

    /** The default analyzer of the tools. */
    static final Analyzer STANDARD = of(Optional.of(new StandardAnalyzer()), Optional.empty(), List.of());

    private Analyzers() {}

    static Analyzer of(
            Optional<StandardAnalyzer> standard, Optional<PresetAnalyzer> preset, List<FieldAnalyzer> fields) {
        return new Analyzer(standard, preset, Optional.empty(), Optional.empty(), Optional.empty(), fields, List.of());
    }

    /** The analyzer of {@code name} with its default settings; {@code StandardAnalyzer} is the standard one. */
    static Analyzer named(String name, Names names) throws ToolException, Jobs.Cancelled {
        if (name.equals(STANDARD_ANALYZER)) {
            return STANDARD;
        }
        String className = name.indexOf('.') >= 0 ? name : names.resolve(name);
        return of(
                Optional.empty(),
                Optional.of(new PresetAnalyzer(
                        className,
                        Optional.empty(),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                List.of());
    }

    /**
     * The class names of the analyzers that {@code getAnalysisComponents} lists, fetched on first use
     * (ADR 0104).
     */
    static final class Names {

        private final Call call;
        private List<AvailableAnalyzer> available;

        Names(Call call) {
            this.call = call;
        }

        String resolve(String name) throws ToolException, Jobs.Cancelled {
            if (available == null) {
                try {
                    available = call.call(Methods.GET_ANALYSIS_COMPONENTS, new GetAnalysisComponentsParams())
                            .analyzers();
                } catch (ToolException e) {
                    if (e.getMessage().endsWith("(NOT_SUPPORTED)")) {
                        // No Lucene 10 runtime to list them: the older runtimes take the simple name.
                        available = List.of();
                    } else {
                        throw e;
                    }
                }
            }
            if (available.isEmpty()) {
                return name;
            }
            List<AvailableAnalyzer> matches = available.stream()
                    .filter(analyzer -> analyzer.name().equalsIgnoreCase(name))
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0).className();
            }
            if (matches.isEmpty()) {
                throw ToolError.invalid("No analyzer " + name + "; there are "
                        + available.stream().map(AvailableAnalyzer::name).collect(Collectors.joining(", ")));
            }
            throw ToolError.invalid("Several analyzers are called " + name + "; give the class name of one of "
                    + matches.stream().map(AvailableAnalyzer::className).collect(Collectors.joining(", ")));
        }
    }
}
