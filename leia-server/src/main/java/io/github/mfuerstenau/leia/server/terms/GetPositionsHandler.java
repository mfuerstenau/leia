package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/** {@code getPositions}: where a term occurs in a document of the {@link CurrentIndex}. */
public final class GetPositionsHandler implements MethodHandler<GetPositionsParams, GetPositionsResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetPositionsHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetPositionsParams, GetPositionsResult> method() {
        return Methods.GET_POSITIONS;
    }

    @Override
    public GetPositionsResult handle(GetPositionsParams params) throws RpcException {
        return RuntimeResults.positions(currentIndex.runtime(), params);
    }
}
