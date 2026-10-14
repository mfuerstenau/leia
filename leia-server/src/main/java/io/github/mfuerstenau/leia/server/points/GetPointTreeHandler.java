package io.github.mfuerstenau.leia.server.points;

import io.github.mfuerstenau.leia.core.IndexSummary;
import io.github.mfuerstenau.leia.core.Points;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.PointSegment;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;

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
        ReadOnlyIndex index = currentIndex.readable();
        String field = params.field();
        int bytes = PointMapper.checkPointField(index, field);
        Points.Tree tree;
        try {
            tree = index.pointTree(field);
        } catch (IOException e) {
            throw PointMapper.notReadable(field, e);
        }
        List<IndexSummary> indexes = index.indexes();
        return new GetPointTreeResult(
                tree.field(),
                tree.dimensions(),
                tree.indexDimensions(),
                tree.bytesPerDimension(),
                tree.size(),
                tree.docCount(),
                tree.min().map(min -> PointMapper.pointValue(min, bytes)),
                tree.max().map(max -> PointMapper.pointValue(max, bytes)),
                tree.segments().stream()
                        .map(segment -> new PointSegment(
                                segment.segment(),
                                indexOf(indexes, segment.docBase()),
                                segment.name(),
                                segment.docBase(),
                                segment.maxDoc(),
                                segment.size(),
                                segment.docCount(),
                                PointMapper.pointValue(segment.min(), bytes),
                                PointMapper.pointValue(segment.max(), bytes)))
                        .toList());
    }

    /** The index directory that a document id is in: the last one starting at or before it. */
    private static int indexOf(List<IndexSummary> indexes, int docId) {
        int found = 0;
        for (int i = 0; i < indexes.size(); i++) {
            if (indexes.get(i).docBase() <= docId) {
                found = i;
            }
        }
        return found;
    }
}
