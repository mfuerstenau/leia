package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.core.AnalysisComponents;
import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableComponent;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import jakarta.inject.Inject;
import java.util.List;

/**
 * {@code getAnalysisComponents}: the preset analyzers (ADR 0015), and what a custom analyzer can be
 * built from (ADR 0014).
 */
public final class GetAnalysisComponentsHandler
        implements MethodHandler<GetAnalysisComponentsParams, GetAnalysisComponentsResult> {

    @Inject
    GetAnalysisComponentsHandler() {}

    @Override
    public RpcMethod<GetAnalysisComponentsParams, GetAnalysisComponentsResult> method() {
        return Methods.GET_ANALYSIS_COMPONENTS;
    }

    @Override
    public GetAnalysisComponentsResult handle(GetAnalysisComponentsParams params) {
        AnalysisComponents components = Analyzers.components();
        return new GetAnalysisComponentsResult(
                components.analyzers().stream()
                        .map(analyzer -> new AvailableAnalyzer(analyzer.name(), analyzer.className()))
                        .toList(),
                available(components.charFilters()),
                available(components.tokenizers()),
                available(components.tokenFilters()));
    }

    private static List<AvailableComponent> available(List<AnalysisComponents.Available> components) {
        return components.stream()
                .map(component -> new AvailableComponent(component.name(), component.className()))
                .toList();
    }
}
