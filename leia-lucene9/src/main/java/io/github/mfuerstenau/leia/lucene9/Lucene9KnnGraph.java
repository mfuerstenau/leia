package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.KnnGraph;
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
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.RandomAccessVectorValues;

/**
 * The HNSW graphs of KNN vector fields, read with Lucene 9.12 (ADR 0049) as leia-core reads Lucene
 * 10's (ADR 0085). 9.12 has the same {@link HnswGraphProvider}, implemented by the readers of 9.5's
 * and 9.9's HNSW formats, but not by those of 9.0 to 9.4. Its {@link FloatVectorValues} is an
 * iterator without ordinals: the vectors its readers return are {@link RandomAccessVectorValues}
 * too, which map an ordinal to its document and vector; for any other, the ordinal of a vector is
 * its position in document order, as 9.12 writes them.
 */
final class Lucene9KnnGraph implements KnnGraph {

    private final IndexReader reader;
    private final FieldInfos fieldInfos;

    Lucene9KnnGraph(IndexReader reader, FieldInfos fieldInfos) {
        this.reader = reader;
        this.fieldInfos = fieldInfos;
    }

    @Override
    public Graph graph(String field) throws IOException {
        FieldInfo info = vectorField(field);
        List<SegmentGraph> segments = new ArrayList<>();
        for (LeafReaderContext context : reader.leaves()) {
            Optional<Vectors> vectors = vectors(context, info);
            if (vectors.isEmpty() || vectors.get().size() == 0) {
                continue;
            }
            Optional<Shape> shape = Optional.empty();
            Optional<HnswGraph> graph = hnsw(context.reader(), field);
            if (graph.isPresent()) {
                SegmentNodes nodes = new SegmentNodes(context, graph.get(), vectors.get());
                List<Integer> sizes = new ArrayList<>();
                for (int level = 0; level < nodes.numLevels(); level++) {
                    sizes.add(nodes.levelSize(level));
                }
                shape = Optional.of(
                        new Shape(List.copyOf(sizes), nodes.node(graph.get().entryNode()), OptionalInt.empty()));
            }
            segments.add(new SegmentGraph(
                    context.ord,
                    segmentName(context.reader()),
                    context.docBase,
                    context.reader().maxDoc(),
                    vectors.get().size(),
                    shape));
        }
        return new Graph(field, List.copyOf(segments));
    }

    @Override
    public NodePage nodes(String field, int segment, int level, int offset, int limit) throws IOException {
        if (limit < 1 || limit > MAX_NODES) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_NODES + ", not " + limit);
        }
        SegmentNodes nodes = segmentNodes(vectorField(field), segment);
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

    @Override
    public NodeNeighbours node(String field, int segment, int ord) throws IOException {
        FieldInfo info = vectorField(field);
        SegmentNodes nodes = segmentNodes(info, segment);
        nodes.checkOrd(ord);
        Node node = nodes.node(ord);
        List<LevelNeighbours> levels = new ArrayList<>();
        for (int level = node.topLevel(); level >= 0; level--) {
            List<Neighbour> neighbours = new ArrayList<>();
            for (int neighbour : nodes.neighbours(level, ord)) {
                neighbours.add(new Neighbour(
                        nodes.node(neighbour),
                        nodes.vectors.score(info.getVectorSimilarityFunction(), ord, neighbour)));
            }
            levels.add(new LevelNeighbours(level, List.copyOf(neighbours)));
        }
        return new NodeNeighbours(node, List.copyOf(levels));
    }

    @Override
    public Optional<NodeRef> find(String field, int docId) throws IOException {
        FieldInfo info = vectorField(field);
        if (docId < 0 || docId >= reader.maxDoc()) {
            throw new IllegalArgumentException("No document " + docId + "; maxDoc is " + reader.maxDoc());
        }
        List<LeafReaderContext> leaves = reader.leaves();
        LeafReaderContext context = leaves.get(ReaderUtil.subIndex(docId, leaves));
        Optional<Vectors> vectors = vectors(context, info);
        if (vectors.isEmpty()) {
            return Optional.empty();
        }
        OptionalInt ord = vectors.get().ordOf(docId - context.docBase);
        return ord.isPresent() ? Optional.of(new NodeRef(context.ord, ord.getAsInt())) : Optional.empty();
    }

    /**
     * Up to {@code n} live documents with a vector in {@code field}, as leia-core's {@code
     * KnnGraphs.sample} picks them (ADR 0086), for {@link Lucene9Index#sample}.
     */
    List<Integer> sample(String field, int n) throws IOException {
        FieldInfo info = vectorField(field);
        if (n < 1) {
            throw new IllegalArgumentException("A sample needs at least 1 document, not " + n);
        }
        List<LeafReaderContext> leaves = reader.leaves();
        long[] starts = new long[leaves.size() + 1];
        List<Optional<Vectors>> values = new ArrayList<>();
        for (int i = 0; i < leaves.size(); i++) {
            Optional<Vectors> segment = vectors(leaves.get(i), info);
            values.add(segment);
            starts[i + 1] = starts[i] + segment.map(Vectors::size).orElse(0);
        }
        long total = starts[leaves.size()];
        java.util.TreeSet<Integer> docs = new java.util.TreeSet<>();
        int segment = 0;
        for (int i = 0; i < Math.min(n, total); i++) {
            long position = total <= n ? i : (long) ((i + 0.5) * total / n);
            while (starts[segment + 1] <= position) {
                segment++;
            }
            LeafReaderContext context = leaves.get(segment);
            Vectors vectors = values.get(segment).orElseThrow();
            Bits liveDocs = context.reader().getLiveDocs();
            for (int ord = (int) (position - starts[segment]); ord < vectors.size(); ord++) {
                int doc = vectors.ordToDoc(ord);
                if ((liveDocs == null || liveDocs.get(doc)) && docs.add(context.docBase + doc)) {
                    break;
                }
            }
        }
        return List.copyOf(docs);
    }

    /** The field {@code field}, which has vectors. */
    private FieldInfo vectorField(String field) {
        FieldInfo info = fieldInfos.fieldInfo(field);
        if (info == null) {
            throw new IllegalArgumentException("No field " + field);
        }
        if (info.getVectorDimension() == 0) {
            throw new IllegalArgumentException("Field " + field + " has no KNN vectors");
        }
        return info;
    }

    private SegmentNodes segmentNodes(FieldInfo field, int segment) throws IOException {
        List<LeafReaderContext> leaves = reader.leaves();
        if (segment < 0 || segment >= leaves.size()) {
            throw new IllegalArgumentException(
                    "No segment " + segment + "; the index has " + leaves.size() + " segments");
        }
        LeafReaderContext context = leaves.get(segment);
        Optional<Vectors> vectors = vectors(context, field);
        if (vectors.isEmpty() || vectors.get().size() == 0) {
            throw new IllegalArgumentException("Segment " + segment + " has no vectors in field " + field.name);
        }
        HnswGraph graph = hnsw(context.reader(), field.name)
                .orElseThrow(() ->
                        new IllegalArgumentException("Segment " + segment + " has no graph of field " + field.name));
        return new SegmentNodes(context, graph, vectors.get());
    }

    /** The vectors of a field in a segment; empty if the segment has none. */
    private static Optional<Vectors> vectors(LeafReaderContext context, FieldInfo field) throws IOException {
        LeafReader leaf = context.reader();
        FieldInfo segmentField = leaf.getFieldInfos().fieldInfo(field.name);
        if (segmentField == null || segmentField.getVectorDimension() == 0) {
            return Optional.empty();
        }
        DocIdSetIterator values = segmentField.getVectorEncoding() == VectorEncoding.FLOAT32
                ? leaf.getFloatVectorValues(field.name)
                : leaf.getByteVectorValues(field.name);
        return values == null ? Optional.empty() : Optional.of(new Vectors(leaf, field.name, values));
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
        } catch (IllegalArgumentException e) {
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
     * A field's vectors in a segment, by ordinal: through {@link RandomAccessVectorValues} where the
     * reader's vectors are that, else by their position in document order.
     */
    private static final class Vectors {

        private final LeafReader leaf;
        private final String field;
        private final DocIdSetIterator values;
        /** The document of each ordinal, if the vectors aren't random access; else null. */
        private int[] docs;

        Vectors(LeafReader leaf, String field, DocIdSetIterator values) {
            this.leaf = leaf;
            this.field = field;
            this.values = values;
        }

        int size() {
            return values instanceof FloatVectorValues floats ? floats.size() : ((ByteVectorValues) values).size();
        }

        int ordToDoc(int ord) throws IOException {
            if (values instanceof RandomAccessVectorValues random) {
                return random.ordToDoc(ord);
            }
            return docs()[ord];
        }

        /** The ordinal of {@code doc}'s vector; ordinals are in document order. */
        OptionalInt ordOf(int doc) throws IOException {
            int low = 0;
            int high = size() - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int midDoc = ordToDoc(mid);
                if (midDoc < doc) {
                    low = mid + 1;
                } else if (midDoc > doc) {
                    high = mid - 1;
                } else {
                    return OptionalInt.of(mid);
                }
            }
            return OptionalInt.empty();
        }

        float score(VectorSimilarityFunction similarity, int ord, int other) throws IOException {
            if (values instanceof RandomAccessVectorValues.Floats floats) {
                float[] vector = floats.vectorValue(ord).clone();
                return similarity.compare(vector, floats.vectorValue(other));
            }
            if (values instanceof RandomAccessVectorValues.Bytes bytes) {
                byte[] vector = bytes.vectorValue(ord).clone();
                return similarity.compare(vector, bytes.vectorValue(other));
            }
            if (values instanceof FloatVectorValues) {
                return similarity.compare(floats(ordToDoc(ord)), floats(ordToDoc(other)));
            }
            return similarity.compare(bytes(ordToDoc(ord)), bytes(ordToDoc(other)));
        }

        /** The vector of {@code doc}, through a fresh iterator. */
        private float[] floats(int doc) throws IOException {
            FloatVectorValues fresh = leaf.getFloatVectorValues(field);
            fresh.advance(doc);
            return fresh.vectorValue().clone();
        }

        private byte[] bytes(int doc) throws IOException {
            ByteVectorValues fresh = leaf.getByteVectorValues(field);
            fresh.advance(doc);
            return fresh.vectorValue().clone();
        }

        private int[] docs() throws IOException {
            if (docs == null) {
                int[] all = new int[size()];
                DocIdSetIterator iterator =
                        leaf.getFieldInfos().fieldInfo(field).getVectorEncoding() == VectorEncoding.FLOAT32
                                ? leaf.getFloatVectorValues(field)
                                : leaf.getByteVectorValues(field);
                int ord = 0;
                for (int doc = iterator.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = iterator.nextDoc()) {
                    all[ord++] = doc;
                }
                docs = all;
            }
            return docs;
        }
    }

    /**
     * A segment's graph with the sorted nodes of each level above 0, its vectors and its live
     * documents. The graph is a fresh one: moving through its neighbours changes its state.
     */
    private static final class SegmentNodes {

        private final LeafReaderContext context;
        private final HnswGraph graph;
        private final Vectors vectors;
        private final Bits liveDocs;
        /** The sorted nodes of each level; null for level 0, which has every node. */
        private final int[][] levels;

        SegmentNodes(LeafReaderContext context, HnswGraph graph, Vectors vectors) throws IOException {
            this.context = context;
            this.graph = graph;
            this.vectors = vectors;
            this.liveDocs = context.reader().getLiveDocs();
            this.levels = new int[graph.numLevels()][];
            for (int level = 1; level < levels.length; level++) {
                levels[level] = HnswGraph.NodesIterator.getSortedNodes(graph.getNodesOnLevel(level));
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

        Node node(int ord) throws IOException {
            int doc = vectors.ordToDoc(ord);
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
    }
}
