package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeJar;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import jakarta.inject.Inject;

/** {@code getRuntimes}: every runtime jar leia knows, and whether it is loaded (ADR 0072). */
public final class GetRuntimesHandler implements MethodHandler<GetRuntimesParams, GetRuntimesResult> {

    private final Runtimes runtimes;

    @Inject
    GetRuntimesHandler(Runtimes runtimes) {
        this.runtimes = runtimes;
    }

    @Override
    public RpcMethod<GetRuntimesParams, GetRuntimesResult> method() {
        return Methods.GET_RUNTIMES;
    }

    @Override
    public GetRuntimesResult handle(GetRuntimesParams params) {
        return new GetRuntimesResult(runtimes.known().stream()
                .map(state -> new RuntimeJar(state.known().jar(), state.known().indexMajors(), state.luceneVersion()))
                .toList());
    }
}
