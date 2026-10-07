package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.core.AnalyzerSpec;
import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.core.InvalidAnalyzerException;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.server.RpcException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Builds the Lucene analyzer for an {@link Analyzer} of a request (ADR 0014). */
public final class AnalyzerSpecs {

    private AnalyzerSpecs() {}

    /**
     * A new Lucene analyzer for {@code analyzer}, which the caller closes.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present, {@code
     *     INVALID_ANALYZER} if the analyzer can't be built
     */
    public static org.apache.lucene.analysis.Analyzer build(Analyzer analyzer) throws RpcException {
        try {
            return Analyzers.build(spec(analyzer));
        } catch (InvalidAnalyzerException e) {
            throw new RpcException(ErrorCode.INVALID_ANALYZER, "Invalid analyzer: " + e.getMessage(), e);
        }
    }

    private static AnalyzerSpec spec(Analyzer analyzer) throws RpcException, InvalidAnalyzerException {
        if (analyzer.standard().isPresent() == analyzer.custom().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: the analyzer needs exactly one of standard and custom");
        }
        if (analyzer.standard().isPresent()) {
            return new AnalyzerSpec.Standard();
        }
        CustomAnalyzer custom = analyzer.custom().orElseThrow();
        return new AnalyzerSpec.Custom(
                components(custom.charFilters()),
                component(custom.tokenizer()),
                components(custom.tokenFilters()),
                configDir(custom.configDir()));
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
