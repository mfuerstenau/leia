package io.github.mfuerstenau.leia.server.points;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/**
 * {@code getPointCell}: a cell of a point field's BKD tree in one segment of the {@link
 * CurrentIndex}, with its children or, for a leaf, its points (ADR 0041).
 */
public final class GetPointCellHandler implements MethodHandler<GetPointCellParams, GetPointCellResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetPointCellHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetPointCellParams, GetPointCellResult> method() {
        return Methods.GET_POINT_CELL;
    }

    @Override
    public GetPointCellResult handle(GetPointCellParams params) throws RpcException {
        return RuntimeResults.pointCell(currentIndex.runtime(), params);
    }
}
