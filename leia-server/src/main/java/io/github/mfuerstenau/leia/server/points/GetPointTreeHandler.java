package io.github.mfuerstenau.leia.server.points;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/** {@code getPointTree}: a point field's BKD tree in the {@link CurrentIndex}, segment by segment (ADR 0041). */
public final class GetPointTreeHandler implements MethodHandler<GetPointTreeParams, GetPointTreeResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetPointTreeHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetPointTreeParams, GetPointTreeResult> method() {
        return Methods.GET_POINT_TREE;
    }

    @Override
    public GetPointTreeResult handle(GetPointTreeParams params) throws RpcException {
        return RuntimeResults.pointTree(currentIndex.runtime(), params);
    }
}
