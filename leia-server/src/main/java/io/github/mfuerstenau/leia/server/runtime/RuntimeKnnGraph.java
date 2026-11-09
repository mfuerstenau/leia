package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphLevel;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphNeighbour;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphNode;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphSegment;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphShape;
import io.github.mfuerstenau.leia.runtime.KnnGraph;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.support.Optionals;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.points.PointMapper;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The HNSW graphs of KNN vector fields on a runtime with a {@link KnnGraph} group (ADR 0085), as the
 * protocol carries them. Like the point trees (ADR 0041), a segment is its position in the open
 * reader, and each call reads a bounded part of a graph, so none is a job.
 */
public final class RuntimeKnnGraph {

    private RuntimeKnnGraph() {}

    /** {@code getKnnGraph}: the graph of each segment with vectors in the field. */
    public static GetKnnGraphResult graph(OpenRuntime open, GetKnnGraphParams params) throws RpcException {
        KnnGraph graphs = open.index().knnGraph().orElseThrow(open::notSupported);
        String field = params.field();
        RuntimeIndex.VectorFormat format = vectorField(open.index(), field);
        KnnGraph.Graph graph;
        try {
            graph = graphs.graph(field);
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        } catch (IOException e) {
            throw notReadable(field, e);
        }
        List<Integer> docBases = RuntimeResults.stats(open.index()).indexes().stream()
                .map(RuntimeIndex.IndexStats::docBase)
                .toList();
        return new GetKnnGraphResult(
                graph.field(),
                format.dimension(),
                RuntimeResults.vectorEncoding(format.encoding()),
                RuntimeResults.vectorSimilarity(format.similarity()),
                graph.segments().stream()
                        .map(segment -> new KnnGraphSegment(
                                segment.segment(),
                                PointMapper.indexOf(docBases, segment.docBase()),
                                Optional.ofNullable(segment.name()),
                                segment.docBase(),
                                segment.maxDoc(),
                                segment.vectors(),
                                Optional.ofNullable(segment.shape())
                                        .map(shape -> new KnnGraphShape(
                                                shape.levelSizes(),
                                                node(shape.entry()),
                                                Optionals.optionalInt(shape.maxConn())))))
                        .toList());
    }

    /** {@code getKnnGraphNodes}: a page of a level's nodes. */
    public static GetKnnGraphNodesResult nodes(OpenRuntime open, GetKnnGraphNodesParams params) throws RpcException {
        KnnGraph graphs = open.index().knnGraph().orElseThrow(open::notSupported);
        String field = params.field();
        vectorField(open.index(), field);
        if (params.limit() < 1 || params.limit() > GetKnnGraphNodesParams.MAX_NODES) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: limit must be between 1 and " + GetKnnGraphNodesParams.MAX_NODES + ", not "
                            + params.limit());
        }
        try {
            KnnGraph.NodePage page =
                    graphs.nodes(field, params.segment(), params.level(), params.offset(), params.limit());
            return new GetKnnGraphNodesResult(
                    page.total(),
                    page.nodes().stream().map(RuntimeKnnGraph::node).toList());
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        } catch (IOException e) {
            throw notReadable(field, e);
        }
    }

    /**
     * {@code getKnnGraphNode}: a node with its neighbours, by a document, or by a segment and an
     * ordinal.
     */
    public static GetKnnGraphNodeResult node(OpenRuntime open, GetKnnGraphNodeParams params) throws RpcException {
        KnnGraph graphs = open.index().knnGraph().orElseThrow(open::notSupported);
        String field = params.field();
        vectorField(open.index(), field);
        boolean byDoc = params.docId().isPresent();
        if (byDoc == (params.segment().isPresent() || params.ord().isPresent())
                || params.segment().isPresent() != params.ord().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: a node needs either docId, or segment and ord");
        }
        try {
            KnnGraph.NodeRef ref;
            if (byDoc) {
                int docId = params.docId().getAsInt();
                RuntimeResults.checkDocId(open.index(), docId);
                ref = graphs.find(field, docId)
                        .orElseThrow(() -> new RpcException(
                                ErrorCode.INVALID_PARAMS,
                                "Invalid params: document " + docId + " has no vector in the field '" + field + "'"));
            } else {
                ref = new KnnGraph.NodeRef(
                        params.segment().getAsInt(), params.ord().getAsInt());
            }
            KnnGraph.NodeNeighbours node = graphs.node(field, ref.segment(), ref.ord());
            return new GetKnnGraphNodeResult(
                    ref.segment(),
                    node(node.node()),
                    node.levels().stream()
                            .map(level -> new KnnGraphLevel(
                                    level.level(),
                                    level.neighbours().stream()
                                            .map(neighbour ->
                                                    new KnnGraphNeighbour(node(neighbour.node()), neighbour.score()))
                                            .toList()))
                            .toList());
        } catch (IllegalArgumentException e) {
            throw invalid(e);
        } catch (IOException e) {
            throw notReadable(field, e);
        }
    }

    private static KnnGraphNode node(KnnGraph.Node node) {
        return new KnnGraphNode(node.ord(), node.docId(), node.deleted(), node.topLevel());
    }

    /** The vectors of {@code field}, which must be a field with them. */
    private static RuntimeIndex.VectorFormat vectorField(RuntimeIndex index, String field) throws RpcException {
        RuntimeIndex.Field info = index.fields().stream()
                .filter(f -> f.name().equals(field))
                .findFirst()
                .orElseThrow(
                        () -> new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index"));
        return Optional.ofNullable(info.vectors())
                .orElseThrow(() -> new RpcException(
                        ErrorCode.INVALID_PARAMS, "Invalid params: the field '" + field + "' has no KNN vectors"));
    }

    private static RpcException invalid(IllegalArgumentException e) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + e.getMessage(), e);
    }

    private static RpcException notReadable(String field, IOException e) {
        return new RpcException(
                ErrorCode.INDEX_NOT_READABLE,
                "Cannot read the HNSW graph of field '" + field + "': " + e.getMessage(),
                e);
    }
}
