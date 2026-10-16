package io.github.mfuerstenau.leia.server.points;

import io.github.mfuerstenau.leia.core.Points;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.LeafPoint;
import io.github.mfuerstenau.leia.protocol.points.PointCell;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;

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
        if (currentIndex.runtime().isPresent()) {
            return RuntimeResults.pointCell(currentIndex.runtime().get(), params);
        }
        ReadOnlyIndex index = currentIndex.readable();
        String field = params.field();
        int bytes = PointMapper.checkPointField(index, field);
        Points.CellContents contents;
        try {
            contents = index.pointCell(field, params.segment(), List.copyOf(params.path()));
        } catch (IllegalArgumentException e) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + e.getMessage(), e);
        } catch (IOException e) {
            throw PointMapper.notReadable(field, e);
        }
        return new GetPointCellResult(
                cell(contents.cell(), bytes),
                contents.children().stream().map(cell -> cell(cell, bytes)).toList(),
                contents.points().stream()
                        .map(point -> new LeafPoint(
                                point.docId(), point.deleted(), PointMapper.pointValue(point.value(), bytes)))
                        .toList(),
                contents.pointsCut());
    }

    private static PointCell cell(Points.Cell cell, int bytes) {
        return new PointCell(
                PointMapper.pointValue(cell.min(), bytes),
                PointMapper.pointValue(cell.max(), bytes),
                cell.size(),
                cell.leaf());
    }
}
