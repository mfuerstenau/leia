package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The HNSW graphs of a KNN vector field, segment by segment (ADR 0085). A node of a segment's graph
 * is a vector ordinal: the position of a document's vector among the segment's vectors in the field.
 * Each call reads a bounded part of a graph, the node lists of its upper levels and at most one page
 * or one node's neighbours, so none needs a job.
 */
public interface KnnGraph {

    /** The most nodes {@link #nodes} returns. */
    int MAX_NODES = 1000;

    /**
     * The graphs of {@code field} over the whole index.
     *
     * @throws IllegalArgumentException if the index has no such field, or it has no vectors
     */
    Graph graph(String field) throws IOException;

    /**
     * A page of the nodes of {@code level} of {@code field}'s graph in segment {@code segment}, in
     * ordinal order.
     *
     * @param offset the position of the page's first node among the level's nodes, from 0
     * @param limit the most nodes the page has, from 1 to {@link #MAX_NODES}
     * @throws IllegalArgumentException if there is no such field, it has no vectors, there is no such
     *     segment or it has no graph of the field, there is no such level, or the offset or limit is out
     *     of range
     */
    NodePage nodes(String field, int segment, int level, int offset, int limit) throws IOException;

    /**
     * Node {@code ord} of {@code field}'s graph in segment {@code segment}, with its neighbours on each
     * level it is on.
     *
     * @throws IllegalArgumentException if there is no such field, it has no vectors, there is no such
     *     segment or it has no graph of the field, or there is no such node
     */
    NodeNeighbours node(String field, int segment, int ord) throws IOException;

    /**
     * The segment and ordinal of document {@code docId}'s vector in {@code field}; empty if it has
     * none there. A deleted document is found too.
     *
     * @throws IllegalArgumentException if there is no such document, or field with vectors
     */
    Optional<NodeRef> find(String field, int docId) throws IOException;

    /**
     * The graphs of a field.
     *
     * @param segments the segments with vectors in the field, in reader order
     */
    record Graph(String field, List<SegmentGraph> segments) {}

    /**
     * The graph of a field in one segment.
     *
     * @param segment the segment's position among the reader's leaves
     * @param name the segment's name, such as {@code _0}; empty if the leaf isn't a segment reader
     * @param docBase the document id of the segment's first document
     * @param vectors the vectors of the field in the segment, deleted documents' included
     * @param shape the graph; empty if the segment has none leia can read: its format has none (a flat
     *     one, or 9.0 to 9.4's), or the segment was too small for Lucene to build one
     */
    record SegmentGraph(
            int segment,
            @Nullable String name,
            int docBase,
            int maxDoc,
            int vectors,
            @Nullable Shape shape) {}

    /**
     * The levels of a graph.
     *
     * @param levelSizes the nodes on each level, from level 0, which has every node, up
     * @param entry the node a search starts from, on the top level
     * @param maxConn the most neighbours of a node on the levels above 0 (twice that on level 0);
     *     empty if the runtime's Lucene doesn't say, as 9.12 doesn't
     */
    record Shape(
            List<Integer> levelSizes, Node entry, @Nullable Integer maxConn) {}

    /**
     * A node of a graph.
     *
     * @param ord the vector ordinal in the segment
     * @param docId the document id in the index
     * @param topLevel the highest level the node is on
     */
    record Node(int ord, int docId, boolean deleted, int topLevel) {}

    /**
     * A page of a level's nodes.
     *
     * @param total the nodes on the level
     */
    record NodePage(int total, List<Node> nodes) {}

    /**
     * A neighbour of a node.
     *
     * @param score the field's similarity of the two vectors, as a search scores them
     */
    record Neighbour(Node node, float score) {}

    /** A node's neighbours on one level, in the graph's order. */
    record LevelNeighbours(int level, List<Neighbour> neighbours) {}

    /**
     * A node with its neighbours.
     *
     * @param levels the levels the node is on, from its top level down to 0
     */
    record NodeNeighbours(Node node, List<LevelNeighbours> levels) {}

    /** Where a document's vector is in the graphs: its segment and ordinal. */
    record NodeRef(int segment, int ord) {}
}
