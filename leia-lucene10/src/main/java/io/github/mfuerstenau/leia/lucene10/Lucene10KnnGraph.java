package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.KnnGraphs;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.runtime.KnnGraph;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The HNSW graphs of KNN vector fields, read by leia-core's {@code KnnGraphs} (ADR 0085). */
final class Lucene10KnnGraph implements KnnGraph {

    private final ReadOnlyIndex index;

    Lucene10KnnGraph(ReadOnlyIndex index) {
        this.index = index;
    }

    @Override
    public Graph graph(String field) throws IOException {
        KnnGraphs.Graph graph = index.knnGraph(field);
        return new Graph(
                graph.field(),
                graph.segments().stream()
                        .map(segment -> new SegmentGraph(
                                segment.segment(),
                                segment.name(),
                                segment.docBase(),
                                segment.maxDoc(),
                                segment.vectors(),
                                shape(segment.shape())))
                        .toList());
    }

    @Override
    public NodePage nodes(String field, int segment, int level, int offset, int limit) throws IOException {
        KnnGraphs.NodePage page = index.knnGraphNodes(field, segment, level, offset, limit);
        return new NodePage(
                page.total(), page.nodes().stream().map(Lucene10KnnGraph::node).toList());
    }

    @Override
    public NodeNeighbours node(String field, int segment, int ord) throws IOException {
        KnnGraphs.NodeNeighbours node = index.knnGraphNode(field, segment, ord);
        return new NodeNeighbours(
                node(node.node()),
                node.levels().stream()
                        .map(level -> new LevelNeighbours(
                                level.level(),
                                level.neighbours().stream()
                                        .map(neighbour -> new Neighbour(node(neighbour.node()), neighbour.score()))
                                        .toList()))
                        .toList());
    }

    @Override
    public Optional<NodeRef> find(String field, int docId) throws IOException {
        return index.knnGraphFind(field, docId).map(ref -> new NodeRef(ref.segment(), ref.ord()));
    }

    private static Node node(KnnGraphs.Node node) {
        return new Node(node.ord(), node.docId(), node.deleted(), node.topLevel());
    }

    private static @Nullable Shape shape(KnnGraphs.@Nullable Shape shape) {
        return shape == null ? null : new Shape(shape.levelSizes(), node(shape.entry()), shape.maxConn());
    }
}
