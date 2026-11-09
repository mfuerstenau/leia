package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
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
import org.jspecify.annotations.Nullable;

/**
 * The analyzers that tools name: by simple or class name, with their default settings (ADR 0104),
 * for {@code search} and {@code analyze}.
 */
final class Analyzers {

    private static final String STANDARD_ANALYZER = "StandardAnalyzer";

    /** The default analyzer of the tools. */
    static final Analyzer STANDARD = of(new StandardAnalyzer(), null, List.of());

    private Analyzers() {}

    static Analyzer of(
            @Nullable StandardAnalyzer standard, @Nullable PresetAnalyzer preset, List<FieldAnalyzer> fields) {
        return new Analyzer(
                Optional.ofNullable(standard),
                Optional.ofNullable(preset),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                fields,
                List.of());
    }

    /** The analyzer of {@code name} with its default settings; {@code StandardAnalyzer} is the standard one. */
    static Analyzer named(String name, Names names) throws ToolException, Jobs.Cancelled {
        if (name.equals(STANDARD_ANALYZER)) {
            return STANDARD;
        }
        String className = name.indexOf('.') >= 0 ? name : names.resolve(name);
        return of(
                null,
                new PresetAnalyzer(
                        className,
                        Optional.empty(),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                List.of());
    }

    /**
     * The class names of the analyzers that {@code getAnalysisComponents} lists, fetched on first use
     * (ADR 0104).
     */
    static final class Names {

        private final Call call;
        private @Nullable List<AvailableAnalyzer> available;

        Names(Call call) {
            this.call = call;
        }

        String resolve(String name) throws ToolException, Jobs.Cancelled {
            List<AvailableAnalyzer> names = available;
            if (names == null) {
                try {
                    names = call.call(Methods.GET_ANALYSIS_COMPONENTS, new GetAnalysisComponentsParams())
                            .analyzers();
                } catch (ToolException e) {
                    if (e.code().equals(Optional.of(ErrorCode.NOT_SUPPORTED))) {
                        // No Lucene 10 runtime to list them: the older runtimes take the simple name.
                        names = List.of();
                    } else {
                        throw e;
                    }
                }
                available = names;
            }
            if (names.isEmpty()) {
                return name;
            }
            List<AvailableAnalyzer> matches = names.stream()
                    .filter(analyzer -> analyzer.name().equalsIgnoreCase(name))
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0).className();
            }
            if (matches.isEmpty()) {
                throw ToolError.invalid("No analyzer " + name + "; there are "
                        + names.stream().map(AvailableAnalyzer::name).collect(Collectors.joining(", ")));
            }
            throw ToolError.invalid("Several analyzers are called " + name + "; give the class name of one of "
                    + matches.stream().map(AvailableAnalyzer::className).collect(Collectors.joining(", ")));
        }
    }
}
