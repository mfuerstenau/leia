package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The HNSW graphs of a KNN vector field, segment by segment (ADR 0085). */
class KnnGraphsTest {

    /** Documents of the first segment; {@link #NO_VECTOR} has no vectors and {@link #DELETED} is deleted. */
    private static final int FIRST_SEGMENT = 300;

    private static final int SECOND_SEGMENT = 20;
    private static final int NO_VECTOR = 5;
    private static final int DELETED = 10;
    /** The documents with a {@code tiny} vector: too few for Lucene's default format to build a graph. */
    private static final int TINY = 50;
    /** A small M, so that the graph has several levels. */
    private static final int M = 4;

    @TempDir
    Path tmp;

    @Test
    void listsEachSegmentsGraphWithItsLevelsAndEntryNode() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            KnnGraphs.Graph graph = index.knnGraph("hnsw");

            assertThat(graph.segments())
                    .extracting(
                            KnnGraphs.SegmentGraph::segment,
                            KnnGraphs.SegmentGraph::name,
                            KnnGraphs.SegmentGraph::docBase,
                            KnnGraphs.SegmentGraph::vectors)
                    .containsExactly(
                            tuple(0, Optional.of("_0"), 0, FIRST_SEGMENT - 1),
                            tuple(1, Optional.of("_1"), FIRST_SEGMENT, SECOND_SEGMENT));
            KnnGraphs.Shape shape = graph.segments().getFirst().shape().orElseThrow();
            List<Integer> sizes = shape.levelSizes();
            assertThat(sizes).hasSizeGreaterThanOrEqualTo(3);
            assertThat(sizes.getFirst()).isEqualTo(FIRST_SEGMENT - 1);
            for (int level = 1; level < sizes.size(); level++) {
                assertThat(sizes.get(level)).isPositive().isLessThan(sizes.get(level - 1));
            }
            assertThat(shape.entry().topLevel()).isEqualTo(sizes.size() - 1);
            assertThat(shape.maxConn()).isEqualTo(OptionalInt.of(M));
        }
    }

    @Test
    void aSegmentTooSmallForAGraphHasNone() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            KnnGraphs.Graph graph = index.knnGraph("tiny");

            assertThat(graph.segments())
                    .extracting(
                            KnnGraphs.SegmentGraph::vectors,
                            segment -> segment.shape().isPresent())
                    .containsExactly(tuple(TINY, false));
            assertThatThrownBy(() -> index.knnGraphNodes("tiny", 0, 0, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Segment 0 has no graph of field tiny");
        }
    }

    @Test
    void pagesALevelsNodesInOrdinalOrder() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            KnnGraphs.NodePage page = index.knnGraphNodes("hnsw", 0, 0, 0, 12);

            assertThat(page.total()).isEqualTo(FIRST_SEGMENT - 1);
            // The document without a vector has no ordinal: ordinal 5 is document 6.
            assertThat(page.nodes())
                    .extracting(KnnGraphs.Node::ord, KnnGraphs.Node::docId)
                    .startsWith(tuple(0, 0), tuple(1, 1), tuple(2, 2), tuple(3, 3), tuple(4, 4), tuple(5, 6));
            assertThat(page.nodes().get(DELETED - 1).deleted()).isTrue();
            assertThat(page.nodes().stream().filter(KnnGraphs.Node::deleted)).hasSize(1);

            KnnGraphs.Shape shape =
                    index.knnGraph("hnsw").segments().getFirst().shape().orElseThrow();
            int top = shape.levelSizes().size() - 1;
            KnnGraphs.NodePage topNodes = index.knnGraphNodes("hnsw", 0, top, 0, KnnGraphs.MAX_NODES);
            assertThat(topNodes.total()).isEqualTo(shape.levelSizes().get(top));
            assertThat(topNodes.nodes()).contains(shape.entry());
            assertThat(topNodes.nodes())
                    .allSatisfy(node -> assertThat(node.topLevel()).isEqualTo(top));
            assertThat(topNodes.nodes()).isSortedAccordingTo((a, b) -> Integer.compare(a.ord(), b.ord()));

            // The page after the last node is empty.
            assertThat(index.knnGraphNodes("hnsw", 0, 0, FIRST_SEGMENT - 1, 10).nodes())
                    .isEmpty();
        }
    }

    @Test
    void readsANodesNeighboursOnEachOfItsLevelsWithTheirScores() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            KnnGraphs.Node entry = index.knnGraph("hnsw")
                    .segments()
                    .getFirst()
                    .shape()
                    .orElseThrow()
                    .entry();
            KnnGraphs.NodeNeighbours node = index.knnGraphNode("hnsw", 0, entry.ord());

            assertThat(node.node()).isEqualTo(entry);
            assertThat(node.levels())
                    .extracting(KnnGraphs.LevelNeighbours::level)
                    .containsExactly(java.util.stream.IntStream.iterate(
                                    entry.topLevel(), level -> level >= 0, level -> level - 1)
                            .boxed()
                            .toArray(Integer[]::new));
            assertThat(node.levels().getLast().neighbours()).isNotEmpty();
            for (KnnGraphs.LevelNeighbours level : node.levels()) {
                assertThat(level.neighbours()).hasSizeLessThanOrEqualTo(level.level() == 0 ? 2 * M : M);
                for (KnnGraphs.Neighbour neighbour : level.neighbours()) {
                    assertThat(neighbour.node().topLevel()).isGreaterThanOrEqualTo(level.level());
                    assertThat(neighbour.score())
                            .isEqualTo(VectorSimilarityFunction.EUCLIDEAN.compare(
                                    floats(entry.docId()),
                                    floats(neighbour.node().docId())));
                }
            }
        }
    }

    @Test
    void scoresQuantizedAndByteGraphsWithTheirRawVectors() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            KnnGraphs.NodeNeighbours quantized = index.knnGraphNode("quantized", 0, 0);
            KnnGraphs.Neighbour first =
                    quantized.levels().getLast().neighbours().getFirst();
            assertThat(first.score())
                    .isEqualTo(VectorSimilarityFunction.COSINE.compare(
                            floats(0), floats(first.node().docId())));

            KnnGraphs.NodeNeighbours bytes = index.knnGraphNode("bytes", 0, 0);
            KnnGraphs.Neighbour byteNeighbour =
                    bytes.levels().getLast().neighbours().getFirst();
            assertThat(byteNeighbour.score())
                    .isEqualTo(VectorSimilarityFunction.EUCLIDEAN.compare(
                            bytes(0), bytes(byteNeighbour.node().docId())));
        }
    }

    @Test
    void findsADocumentsNode() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(index.knnGraphFind("hnsw", 0)).contains(new KnnGraphs.NodeRef(0, 0));
            assertThat(index.knnGraphFind("hnsw", NO_VECTOR + 1)).contains(new KnnGraphs.NodeRef(0, NO_VECTOR));
            assertThat(index.knnGraphFind("hnsw", NO_VECTOR)).isEmpty();
            assertThat(index.knnGraphFind("hnsw", DELETED)).contains(new KnnGraphs.NodeRef(0, DELETED - 1));
            assertThat(index.knnGraphFind("hnsw", FIRST_SEGMENT + 3)).contains(new KnnGraphs.NodeRef(1, 3));
            // The second segment has no tiny vectors.
            assertThat(index.knnGraphFind("tiny", FIRST_SEGMENT + 3)).isEmpty();
        }
    }

    @Test
    void samplesLiveDocumentsSpreadOverTheSegments() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            List<Integer> sample = index.knnSample("hnsw", 10);

            assertThat(sample).hasSize(10).isSorted().doesNotHaveDuplicates().doesNotContain(NO_VECTOR, DELETED);
            // 319 vectors: one in about 32, the last in the second segment.
            assertThat(sample.getFirst()).isEqualTo(15 + 1);
            assertThat(sample.getLast()).isEqualTo(FIRST_SEGMENT + 4);

            // Every live document with a vector, if there are no more: all but the one without and the deleted one.
            assertThat(index.knnSample("hnsw", 1000)).hasSize(FIRST_SEGMENT + SECOND_SEGMENT - 2);
            // A position on the deleted document takes the next one.
            assertThat(index.knnSample("tiny", 50)).doesNotContain(DELETED).contains(DELETED + 1);
            assertThatThrownBy(() -> index.knnSample("hnsw", 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesWhatDoesNotExist() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThatThrownBy(() -> index.knnGraph("nope"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No field nope");
            assertThatThrownBy(() -> index.knnGraph("id"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Field id has no KNN vectors");
            assertThatThrownBy(() -> index.knnGraphNodes("hnsw", 2, 0, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No segment 2; the index has 2 segments");
            assertThatThrownBy(() -> index.knnGraphNodes("hnsw", 0, 99, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("No level 99; the graph has ");
            assertThatThrownBy(() -> index.knnGraphNodes("hnsw", 0, 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("limit must be from 1 to 1000, not 0");
            assertThatThrownBy(() -> index.knnGraphNodes("hnsw", 0, 0, FIRST_SEGMENT, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("offset must be from 0 to 299 on level 0, not 300");
            assertThatThrownBy(() -> index.knnGraphNode("hnsw", 0, FIRST_SEGMENT - 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No node 299; the graph has 299");
            assertThatThrownBy(() -> index.knnGraphFind("hnsw", FIRST_SEGMENT + SECOND_SEGMENT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        directory,
                        new IndexWriterConfig()
                                .setCodec(codec())
                                .setMergePolicy(NoMergePolicy.INSTANCE)
                                .setMaxBufferedDocs(FIRST_SEGMENT + SECOND_SEGMENT + 1))) {
            for (int i = 0; i < FIRST_SEGMENT + SECOND_SEGMENT; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                if (i != NO_VECTOR) {
                    doc.add(new KnnFloatVectorField("hnsw", floats(i), VectorSimilarityFunction.EUCLIDEAN));
                    if (i < FIRST_SEGMENT) {
                        doc.add(new KnnFloatVectorField("quantized", floats(i), VectorSimilarityFunction.COSINE));
                        doc.add(new KnnByteVectorField("bytes", bytes(i), VectorSimilarityFunction.EUCLIDEAN));
                    }
                }
                if (i < TINY) {
                    doc.add(new KnnFloatVectorField("tiny", floats(i), VectorSimilarityFunction.EUCLIDEAN));
                }
                writer.addDocument(doc);
                if (i == FIRST_SEGMENT - 1) {
                    writer.commit();
                }
            }
            writer.deleteDocuments(new Term("id", Integer.toString(DELETED)));
            writer.commit();
        }
        return dir;
    }

    /**
     * The default codec, with graphs of a small M for every vector field but {@code tiny}, built
     * however small the segment, and a scalar-quantized one for {@code quantized}.
     */
    private static Codec codec() {
        Codec codec = Codec.getDefault();
        KnnVectorsFormat defaults = new Lucene99HnswVectorsFormat();
        KnnVectorsFormat hnsw = new Lucene99HnswVectorsFormat(M, 20, 0);
        KnnVectorsFormat quantized =
                new Lucene104HnswScalarQuantizedVectorsFormat(ScalarEncoding.UNSIGNED_BYTE, M, 20, 1, null, 0);
        PerFieldKnnVectorsFormat perField = new PerFieldKnnVectorsFormat() {
            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                return switch (field) {
                    case "tiny" -> defaults;
                    case "quantized" -> quantized;
                    default -> hnsw;
                };
            }
        };
        return new FilterCodec(codec.getName(), codec) {
            @Override
            public KnnVectorsFormat knnVectorsFormat() {
                return perField;
            }
        };
    }

    private static float[] floats(int doc) {
        Random random = new Random(doc);
        float[] vector = new float[6];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        vector[0] += 2; // never all zeros, which cosine rejects
        return vector;
    }

    private static byte[] bytes(int doc) {
        byte[] vector = new byte[6];
        new Random(-doc - 1).nextBytes(vector);
        return vector;
    }
}
