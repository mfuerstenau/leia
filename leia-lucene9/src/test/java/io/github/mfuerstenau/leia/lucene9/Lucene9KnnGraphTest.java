package io.github.mfuerstenau.leia.lucene9;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.KnnGraph;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HNSW graphs read with Lucene 9.12 (ADR 0085): the 9.x segment {@code _2} of {@code lucene8to9},
 * whose three vectors make a graph of one level, and an index that 9.12 writes here with a small M,
 * for several levels.
 */
class Lucene9KnnGraphTest {

    private static final int DOCS = 300;
    private static final int NO_VECTOR = 5;
    private static final int DELETED = 10;
    private static final int M = 4;

    @TempDir
    Path tmp;

    private static RuntimeIndex open(Path dir) throws IOException {
        return new Lucene9Runtime().open(List.of(dir.toAbsolutePath()), DirectoryKind.FS);
    }

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir);
    }

    @Test
    void readsTheGraphOfThe9xSegmentOfLucene8to9() throws IOException {
        try (RuntimeIndex index = open(testIndex("lucene8to9"))) {
            KnnGraph graphs = index.knnGraph().orElseThrow();
            KnnGraph.Graph graph = graphs.graph("embedding");

            // Only the 9.x segment has vectors.
            assertThat(graph.segments())
                    .extracting(KnnGraph.SegmentGraph::name, KnnGraph.SegmentGraph::vectors)
                    .containsExactly(tuple(Optional.of("_2"), 3));
            KnnGraph.SegmentGraph segment = graph.segments().getFirst();
            KnnGraph.Shape shape = segment.shape().orElseThrow();
            assertThat(shape.levelSizes()).containsExactly(3);
            assertThat(shape.maxConn()).isEmpty();

            KnnGraph.NodePage nodes = graphs.nodes("embedding", segment.segment(), 0, 0, 10);
            assertThat(nodes.total()).isEqualTo(3);
            assertThat(nodes.nodes())
                    .extracting(KnnGraph.Node::ord, KnnGraph.Node::docId, KnnGraph.Node::topLevel)
                    .containsExactly(
                            tuple(0, segment.docBase(), 0),
                            tuple(1, segment.docBase() + 1, 0),
                            tuple(2, segment.docBase() + 2, 0));

            // (1, 0, 0, 0) and (0.8, 0.6, 0, 0) by cosine, as Lucene scores it: (1 + cos) / 2.
            KnnGraph.NodeNeighbours north = graphs.node("embedding", segment.segment(), 0);
            assertThat(north.levels()).hasSize(1);
            assertThat(north.levels().getFirst().neighbours())
                    .filteredOn(neighbour -> neighbour.node().ord() == 1)
                    .singleElement()
                    .satisfies(neighbour -> assertThat(neighbour.score()).isEqualTo(0.9f));

            assertThat(graphs.find("embedding", segment.docBase() + 2))
                    .contains(new KnnGraph.NodeRef(segment.segment(), 2));
            assertThat(graphs.find("embedding", 0)).isEmpty();
            assertThatThrownBy(() -> graphs.graph("title"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Field title has no KNN vectors");
        }
    }

    @Test
    void readsTheLevelsOfAGraphOfASmallM() throws IOException {
        try (RuntimeIndex index = open(writeIndex())) {
            KnnGraph graphs = index.knnGraph().orElseThrow();
            KnnGraph.Shape shape =
                    graphs.graph("v").segments().getFirst().shape().orElseThrow();
            List<Integer> sizes = shape.levelSizes();
            assertThat(sizes).hasSizeGreaterThanOrEqualTo(3);
            assertThat(sizes.getFirst()).isEqualTo(DOCS - 1);
            assertThat(shape.entry().topLevel()).isEqualTo(sizes.size() - 1);

            int top = sizes.size() - 1;
            KnnGraph.NodePage topNodes = graphs.nodes("v", 0, top, 0, KnnGraph.MAX_NODES);
            assertThat(topNodes.nodes()).contains(shape.entry());

            KnnGraph.NodePage first = graphs.nodes("v", 0, 0, 0, 10);
            assertThat(first.nodes())
                    .extracting(KnnGraph.Node::ord, KnnGraph.Node::docId)
                    .startsWith(tuple(0, 0), tuple(1, 1), tuple(2, 2), tuple(3, 3), tuple(4, 4), tuple(5, 6));
            assertThat(first.nodes().get(DELETED - 1).deleted()).isTrue();

            KnnGraph.NodeNeighbours entry = graphs.node("v", 0, shape.entry().ord());
            assertThat(entry.levels()).hasSize(sizes.size());
            assertThat(entry.levels().getLast().neighbours()).isNotEmpty();
            for (KnnGraph.LevelNeighbours level : entry.levels()) {
                for (KnnGraph.Neighbour neighbour : level.neighbours()) {
                    assertThat(neighbour.node().topLevel()).isGreaterThanOrEqualTo(level.level());
                    assertThat(neighbour.score())
                            .isEqualTo(VectorSimilarityFunction.EUCLIDEAN.compare(
                                    floats(shape.entry().docId()),
                                    floats(neighbour.node().docId())));
                }
            }

            assertThat(graphs.find("v", NO_VECTOR + 1)).contains(new KnnGraph.NodeRef(0, NO_VECTOR));
            assertThat(graphs.find("v", NO_VECTOR)).isEmpty();
            assertThatThrownBy(() -> graphs.node("v", 0, DOCS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No node 300; the graph has 299");
            assertThatThrownBy(() -> graphs.nodes("v", 1, 0, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No segment 1; the index has 1 segments");
        }
    }

    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        Codec codec = Codec.getDefault();
        KnnVectorsFormat hnsw = new PerFieldKnnVectorsFormat() {
            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                return new Lucene99HnswVectorsFormat(M, 20);
            }
        };
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        directory,
                        new IndexWriterConfig()
                                .setCodec(new FilterCodec(codec.getName(), codec) {
                                    @Override
                                    public KnnVectorsFormat knnVectorsFormat() {
                                        return hnsw;
                                    }
                                })
                                .setMergePolicy(NoMergePolicy.INSTANCE)
                                .setMaxBufferedDocs(DOCS + 1))) {
            for (int i = 0; i < DOCS; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                if (i != NO_VECTOR) {
                    doc.add(new KnnFloatVectorField("v", floats(i), VectorSimilarityFunction.EUCLIDEAN));
                }
                writer.addDocument(doc);
            }
            writer.deleteDocuments(new Term("id", Integer.toString(DELETED)));
            writer.commit();
        }
        return dir;
    }

    private static float[] floats(int doc) {
        Random random = new Random(doc);
        float[] vector = new float[6];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }
}
