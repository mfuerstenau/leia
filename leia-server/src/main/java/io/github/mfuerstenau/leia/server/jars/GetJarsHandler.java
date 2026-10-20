package io.github.mfuerstenau.leia.server.jars;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import jakarta.inject.Inject;

/** {@code getJars}: the jars of the command line and those added since (ADR 0024). */
public final class GetJarsHandler implements MethodHandler<GetJarsParams, GetJarsResult> {

    @Inject
    GetJarsHandler() {}

    @Override
    public RpcMethod<GetJarsParams, GetJarsResult> method() {
        return Methods.GET_JARS;
    }

    @Override
    public GetJarsResult handle(GetJarsParams params) {
        return new GetJarsResult(AddJarsHandler.strings(JarChain.jars()));
    }
}
