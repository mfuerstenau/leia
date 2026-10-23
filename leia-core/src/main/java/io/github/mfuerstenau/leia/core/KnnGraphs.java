package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.hnsw.HnswGraphProvider;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.hnsw.HnswGraph;

/**
 * The HNSW graphs of a KNN vector field, segment by segment (ADR 0085). A node is a vector ordinal of
 * its segment. The graph is reached as Lucene's own {@code HnswUtil.graphIsRooted} reaches it: through
 * the segment's vectors reader, if that is an {@link HnswGraphProvider}. Each call reads the node
 * lists of the upper levels, which are on the heap and hold about one in M of the nodes below, and
 * at most one page of nodes or one node's neighbours, so none needs a job.
 */
public final class KnnGraphs {

    /** The most nodes {@link #nodes} returns. */
    public static final int MAX_NODES = 1000;

    /**
     * The graphs of a field.
     *
     * @param segments the segments with vectors in the field, in reader order
     */
    public record Graph(String field, List<SegmentGraph> segments) {}

    /**
     * The graph of a field in one segment.
     *
     * @param segment the segment's position among the reader's leaves
     * @param name the segment's name, such as {@code _0}; empty if the leaf isn't a segment reader
     * @param docBase the document id of the segment's first document
     * @param vectors the vectors of the field in the segment, deleted documents' included
     * @param shape the graph; empty if the segment has none that can be read: its format has none,
     *     or the segment was too small for Lucene to build one
     */
    public record SegmentGraph(
            int segment, Optional<String> name, int docBase, int maxDoc, int vectors, Optional<Shape> shape) {}

    /**
     * The levels of a graph.
     *
     * @param levelSizes the nodes on each level, from level 0, which has every node, up
     * @param entry the node a search starts from, on the top level
     * @param maxConn the most neighbours of a node on the levels above 0; empty if the graph doesn't
     *     say
     */
    public record Shape(List<Integer> levelSizes, Node entry, OptionalInt maxConn) {}

    /**
     * A node of a graph.
     *
     * @param ord the vector ordinal in the segment
     * @param docId the document id in the index
     * @param topLevel the highest level the node is on
     */
    public record Node(int ord, int docId, boolean deleted, int topLevel) {}

    /**
     * A page of a level's nodes, in ordinal order.
     *
     * @param total the nodes on the level
     */
    public record NodePage(int total, List<Node> nodes) {}

    /**
     * A neighbour of a node.
     *
     * @param score the field's similarity of the two vectors
     */
    public record Neighbour(Node node, float score) {}

    /** A node's neighbours on one level, in the graph's order. */
    public record LevelNeighbours(int level, List<Neighbour> neighbours) {}

    /**
     * A node with its neighbours.
     *
     * @param levels the levels the node is on, from its top level down to 0
     */
    public record NodeNeighbours(Node node, List<LevelNeighbours> levels) {}

    /** Where a document's vector is: its segment and ordinal. */
    public record NodeRef(int segment, int ord) {}

    private KnnGraphs() {}

    static Graph graph(IndexReader reader, FieldInfo field) throws IOException {
        String name = field.getName();
        List<SegmentGraph> segments = new ArrayList<>();
        for (LeafReaderContext context : reader.leaves()) {
            KnnVectorValues values = values(context.reader(), field);
            if (values == null || values.size() == 0) {
                continue;
            }
            Optional<Shape> shape = Optional.empty();
            Optional<HnswGraph> graph = hnsw(context.reader(), name);
            if (graph.isPresent()) {
                SegmentNodes nodes = new SegmentNodes(context, graph.get(), values);
                List<Integer> sizes = new ArrayList<>();
                for (int level = 0; level < nodes.numLevels(); level++) {
                    sizes.add(nodes.levelSize(level));
                }
                int maxConn = graph.get().maxConn();
                shape = Optional.of(new Shape(
                        List.copyOf(sizes),
                        nodes.node(graph.get().entryNode()),
                        maxConn == HnswGraph.UNKNOWN_MAX_CONN ? OptionalInt.empty() : OptionalInt.of(maxConn)));
            }
            segments.add(new SegmentGraph(
                    context.ord,
                    segmentName(context.reader()),
                    context.docBase,
                    context.reader().maxDoc(),
                    values.size(),
                    shape));
        }
        return new Graph(name, List.copyOf(segments));
    }

    /**
     * A page of the nodes of a level of a field's graph in one segment.
     *
     * @throws IllegalArgumentException if there is no such segment or it has no graph of the field,
     *     there is no such level, or the offset or limit is out of range
     */
    static NodePage nodes(IndexReader reader, FieldInfo field, int segment, int level, int offset, int limit)
            throws IOException {
        if (limit < 1 || limit > MAX_NODES) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_NODES + ", not " + limit);
        }
        SegmentNodes nodes = segmentNodes(reader, field, segment);
        nodes.checkLevel(level);
        int total = nodes.levelSize(level);
        if (offset < 0 || offset > total) {
            throw new IllegalArgumentException(
                    "offset must be from 0 to " + total + " on level " + level + ", not " + offset);
        }
        List<Node> page = new ArrayList<>();
        for (int i = offset; i < Math.min(total, offset + limit); i++) {
            page.add(nodes.node(level == 0 ? i : nodes.levels[level][i]));
        }
        return new NodePage(total, List.copyOf(page));
    }

    /**
     * A node of a field's graph in one segment, with its neighbours on each of its levels, scored with
     * the field's similarity.
     *
     * @throws IllegalArgumentException if there is no such segment or it has no graph of the field, or
     *     there is no such node
     */
    static NodeNeighbours node(IndexReader reader, FieldInfo field, int segment, int ord) throws IOException {
        SegmentNodes nodes = segmentNodes(reader, field, segment);
        nodes.checkOrd(ord);
        Node node = nodes.node(ord);
        VectorSimilarityFunction similarity = field.getVectorSimilarityFunction();
        List<LevelNeighbours> levels = new ArrayList<>();
        for (int level = node.topLevel(); level >= 0; level--) {
            List<Neighbour> neighbours = new ArrayList<>();
            for (int neighbour : nodes.neighbours(level, ord)) {
                neighbours.add(new Neighbour(nodes.node(neighbour), nodes.score(similarity, ord, neighbour)));
            }
            levels.add(new LevelNeighbours(level, List.copyOf(neighbours)));
        }
        return new NodeNeighbours(node, List.copyOf(levels));
    }

    /** The segment and ordinal of a document's vector in a field; empty if it has none. */
    static Optional<NodeRef> find(IndexReader reader, FieldInfo field, int docId) throws IOException {
        List<LeafReaderContext> leaves = reader.leaves();
        LeafReaderContext context = leaves.get(ReaderUtil.subIndex(docId, leaves));
        KnnVectorValues values = values(context.reader(), field);
        if (values == null) {
            return Optional.empty();
        }
        int doc = docId - context.docBase;
        KnnVectorValues.DocIndexIterator iterator = values.iterator();
        return iterator.advance(doc) == doc
                ? Optional.of(new NodeRef(context.ord, iterator.index()))
                : Optional.empty();
    }

    /**
     * Up to {@code n} live documents with a vector in a field (ADR 0086), spread evenly by ordinal
     * over the segments; a position on a deleted document takes the next live one of its segment.
     * In document id order, without duplicates.
     */
    static List<Integer> sample(IndexReader reader, FieldInfo field, int n) throws IOException {
        if (n < 1) {
            throw new IllegalArgumentException("A sample needs at least 1 document, not " + n);
        }
        List<LeafReaderContext> leaves = reader.leaves();
        long[] starts = new long[leaves.size() + 1];
        List<KnnVectorValues> values = new ArrayList<>();
        for (int i = 0; i < leaves.size(); i++) {
            KnnVectorValues segment = values(leaves.get(i).reader(), field);
            values.add(segment);
            starts[i + 1] = starts[i] + (segment == null ? 0 : segment.size());
        }
        long total = starts[leaves.size()];
        java.util.TreeSet<Integer> docs = new java.util.TreeSet<>();
        int segment = 0;
        for (int i = 0; i < Math.min(n, total); i++) {
            long position = total <= n ? i : (long) ((i + 0.5) * total / n);
            while (starts[segment + 1] <= position) {
                segment++;
            }
            addFirstLive(docs, leaves.get(segment), values.get(segment), (int) (position - starts[segment]));
        }
        return List.copyOf(docs);
    }

    /** Adds the first live document of {@code vectors} from ordinal {@code from} on that {@code docs} lacks. */
    private static void addFirstLive(
            java.util.TreeSet<Integer> docs, LeafReaderContext context, KnnVectorValues vectors, int from)
            throws IOException {
        Bits liveDocs = context.reader().getLiveDocs();
        for (int ord = from; ord < vectors.size(); ord++) {
            int doc = vectors.ordToDoc(ord);
            if ((liveDocs == null || liveDocs.get(doc)) && docs.add(context.docBase + doc)) {
                return;
            }
        }
    }

    private static SegmentNodes segmentNodes(IndexReader reader, FieldInfo field, int segment) throws IOException {
        List<LeafReaderContext> leaves = reader.leaves();
        if (segment < 0 || segment >= leaves.size()) {
            throw new IllegalArgumentException(
                    "No segment " + segment + "; the index has " + leaves.size() + " segments");
        }
        LeafReaderContext context = leaves.get(segment);
        KnnVectorValues values = values(context.reader(), field);
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("Segment " + segment + " has no vectors in field " + field.getName());
        }
        HnswGraph graph = hnsw(context.reader(), field.getName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Segment " + segment + " has no graph of field " + field.getName()));
        return new SegmentNodes(context, graph, values);
    }

    /** The vectors of a field in a segment; null if the segment has none. */
    private static KnnVectorValues values(LeafReader leaf, FieldInfo field) throws IOException {
        FieldInfo segmentField = leaf.getFieldInfos().fieldInfo(field.getName());
        if (segmentField == null || segmentField.getVectorDimension() == 0) {
            return null;
        }
        return segmentField.getVectorEncoding() == VectorEncoding.FLOAT32
                ? leaf.getFloatVectorValues(field.getName())
                : leaf.getByteVectorValues(field.getName());
    }

    /** The graph of a field in a segment, if its vectors reader has one and it isn't empty. */
    private static Optional<HnswGraph> hnsw(LeafReader leaf, String field) throws IOException {
        if (!(FilterLeafReader.unwrap(leaf) instanceof CodecReader codec)) {
            return Optional.empty();
        }
        KnnVectorsReader vectors = codec.getVectorReader();
        if (vectors instanceof PerFieldKnnVectorsFormat.FieldsReader perField) {
            vectors = perField.getFieldReader(field);
        }
        if (!(vectors instanceof HnswGraphProvider provider)) {
            return Optional.empty();
        }
        HnswGraph graph;
        try {
            graph = provider.getGraph(field);
        } catch (IllegalArgumentException _) {
            // The reader doesn't know the field: it holds another field's format.
            return Optional.empty();
        }
        return graph.size() == 0 || graph.numLevels() == 0 ? Optional.empty() : Optional.of(graph);
    }

    private static Optional<String> segmentName(LeafReader leaf) {
        return FilterLeafReader.unwrap(leaf) instanceof SegmentReader segment
                ? Optional.of(segment.getSegmentName())
                : Optional.empty();
    }

    /**
     * A segment's graph with the sorted nodes of each level above 0, its vectors and its live
     * documents. The graph is a fresh one: moving through its neighbours changes its state.
     */
    private static final class SegmentNodes {

        private final LeafReaderContext context;
        private final HnswGraph graph;
        private final KnnVectorValues values;
        private final Bits liveDocs;
        /** The sorted nodes of each level; null for level 0, which has every node. */
        private final int[][] levels;

        SegmentNodes(LeafReaderContext context, HnswGraph graph, KnnVectorValues values) throws IOException {
            this.context = context;
            this.graph = graph;
            this.values = values;
            this.liveDocs = context.reader().getLiveDocs();
            this.levels = new int[graph.numLevels()][];
            for (int level = 1; level < levels.length; level++) {
                HnswGraph.NodesIterator nodes = graph.getSortedNodes(level);
                int[] sorted = new int[nodes.size()];
                for (int i = 0; nodes.hasNext(); i++) {
                    sorted[i] = nodes.nextInt();
                }
                levels[level] = sorted;
            }
        }

        int numLevels() {
            return levels.length;
        }

        int levelSize(int level) {
            return level == 0 ? graph.size() : levels[level].length;
        }

        void checkLevel(int level) {
            if (level < 0 || level >= levels.length) {
                throw new IllegalArgumentException("No level " + level + "; the graph has " + levels.length);
            }
        }

        void checkOrd(int ord) {
            if (ord < 0 || ord >= graph.size()) {
                throw new IllegalArgumentException("No node " + ord + "; the graph has " + graph.size());
            }
        }

        /** The highest level {@code ord} is on. */
        int topLevel(int ord) {
            for (int level = levels.length - 1; level > 0; level--) {
                if (Arrays.binarySearch(levels[level], ord) >= 0) {
                    return level;
                }
            }
            return 0;
        }

        Node node(int ord) {
            int doc = values.ordToDoc(ord);
            return new Node(ord, context.docBase + doc, liveDocs != null && !liveDocs.get(doc), topLevel(ord));
        }

        /** The neighbours of {@code ord} on {@code level}, which it is on. */
        List<Integer> neighbours(int level, int ord) throws IOException {
            graph.seek(level, ord);
            List<Integer> neighbours = new ArrayList<>();
            for (int neighbour = graph.nextNeighbor();
                    neighbour != DocIdSetIterator.NO_MORE_DOCS;
                    neighbour = graph.nextNeighbor()) {
                neighbours.add(neighbour);
            }
            return neighbours;
        }

        float score(VectorSimilarityFunction similarity, int ord, int other) throws IOException {
            return switch (values) {
                case FloatVectorValues floats -> {
                    float[] vector = floats.vectorValue(ord).clone();
                    yield similarity.compare(vector, floats.vectorValue(other));
                }
                case ByteVectorValues bytes -> {
                    byte[] vector = bytes.vectorValue(ord).clone();
                    yield similarity.compare(vector, bytes.vectorValue(other));
                }
                default -> throw new IllegalStateException("Vectors of " + values.getEncoding());
            };
        }
    }
}
