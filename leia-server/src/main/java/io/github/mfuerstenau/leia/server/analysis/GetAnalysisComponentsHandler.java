package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.RuntimeAnalysis;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import jakarta.inject.Inject;

/**
 * {@code getAnalysisComponents}: the preset analyzers, and the word sets and options they take (ADR
 * 0015, ADR 0017, ADR 0023), and what a custom analyzer can be built from (ADR 0014): those of the
 * first runtime with an analysis tab (ADR 0067); without one, it is {@code NOT_SUPPORTED} (ADR 0071).
 */
public final class GetAnalysisComponentsHandler
        implements MethodHandler<GetAnalysisComponentsParams, GetAnalysisComponentsResult> {

    private final Runtimes runtimes;

    @Inject
    GetAnalysisComponentsHandler(Runtimes runtimes) {
        this.runtimes = runtimes;
    }

    @Override
    public RpcMethod<GetAnalysisComponentsParams, GetAnalysisComponentsResult> method() {
        return Methods.GET_ANALYSIS_COMPONENTS;
    }

    @Override
    public GetAnalysisComponentsResult handle(GetAnalysisComponentsParams params) throws RpcException {
        return runtimes.analysis().orElseThrow(RuntimeAnalysis::notSupported).components();
    }
}
