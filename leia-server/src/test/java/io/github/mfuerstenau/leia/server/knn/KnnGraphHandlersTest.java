package io.github.mfuerstenau.leia.server.knn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphNode;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphSegment;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphShape;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.TestIndexes;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getKnnGraph}, {@code getKnnGraphNodes} and {@code getKnnGraphNode} (ADR 0085). */
class KnnGraphHandlersTest {

    @TempDir
    Path tmp;

    private final Jobs jobs = new Jobs();
    private final CurrentIndex currentIndex = new CurrentIndex(jobs, new Filters());
    private final GetKnnGraphHandler graph = new GetKnnGraphHandler(currentIndex);
    private final GetKnnGraphNodesHandler nodes = new GetKnnGraphNodesHandler(currentIndex);
    private final GetKnnGraphNodeHandler node = new GetKnnGraphNodeHandler(currentIndex);

    @Test
    void describesTheGraphItsNodesAndANodesNeighbours() throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex()));

        GetKnnGraphResult result = graph.handle(new GetKnnGraphParams("v"));

        assertThat(result)
                .extracting(GetKnnGraphResult::dimension, GetKnnGraphResult::encoding, GetKnnGraphResult::similarity)
                .containsExactly(2, VectorEncoding.FLOAT32, VectorSimilarity.EUCLIDEAN);
        assertThat(result.segments())
                .extracting(
                        KnnGraphSegment::segment,
                        KnnGraphSegment::index,
                        KnnGraphSegment::name,
                        KnnGraphSegment::maxDoc,
                        KnnGraphSegment::vectors,
                        segment -> segment.shape().map(KnnGraphShape::levelSizes),
                        segment -> segment.shape().map(KnnGraphShape::maxConn))
                .containsExactly(
                        tuple(0, 0, Optional.of("_0"), 4, 3, Optional.of(List.of(3)), Optional.of(OptionalInt.of(16))));
        // Document 2 has no vector, so ordinal 2 is document 3.
        assertThat(nodes.handle(new GetKnnGraphNodesParams("v", 0, 0, 1, 10)).nodes())
                .containsExactly(new KnnGraphNode(1, 1, false, 0), new KnnGraphNode(2, 3, false, 0));

        GetKnnGraphNodeResult byDoc = node.handle(GetKnnGraphNodeParams.ofDoc("v", 3));
        assertThat(byDoc.segment()).isZero();
        assertThat(byDoc.node()).isEqualTo(new KnnGraphNode(2, 3, false, 0));
        // Euclidean, as Lucene scores it: 1 / (1 + the squared distance).
        assertThat(byDoc.levels())
                .singleElement()
                .satisfies(level -> assertThat(level.neighbours())
                        .extracting(neighbour -> neighbour.node().docId(), neighbour -> neighbour.score())
                        .containsExactlyInAnyOrder(tuple(0, 0.5f), tuple(1, 0.5f)));
        assertThat(node.handle(GetKnnGraphNodeParams.ofOrd("v", 0, 2))).isEqualTo(byDoc);
    }

    @Test
    void checksTheFieldSegmentLevelAndNode() throws Exception {
        currentIndex.replace(TestIndexes.open(writeIndex()));

        assertFails(
                () -> graph.handle(new GetKnnGraphParams("nope")),
                ErrorCode.FIELD_NOT_FOUND,
                "No field 'nope' in the index");
        assertFails(
                () -> graph.handle(new GetKnnGraphParams("id")),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: the field 'id' has no KNN vectors");
        assertFails(
                () -> nodes.handle(new GetKnnGraphNodesParams("v", 1, 0, 0, 10)),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: No segment 1; the index has 1 segments");
        assertFails(
                () -> nodes.handle(new GetKnnGraphNodesParams("v", 0, 1, 0, 10)),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: No level 1; the graph has 1");
        assertFails(
                () -> nodes.handle(new GetKnnGraphNodesParams("v", 0, 0, 0, 1001)),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: limit must be between 1 and 1000, not 1001");
        assertFails(
                () -> nodes.handle(new GetKnnGraphNodesParams("v", 0, 0, 4, 10)),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: offset must be from 0 to 3 on level 0, not 4");
        assertFails(
                () -> node.handle(GetKnnGraphNodeParams.ofOrd("v", 0, 3)),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: No node 3; the graph has 3");
        assertFails(
                () -> node.handle(
                        new GetKnnGraphNodeParams("v", OptionalInt.of(0), OptionalInt.empty(), OptionalInt.of(1))),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: a node needs either docId, or segment and ord");
        assertFails(
                () -> node.handle(
                        new GetKnnGraphNodeParams("v", OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty())),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: a node needs either docId, or segment and ord");
        assertFails(
                () -> node.handle(GetKnnGraphNodeParams.ofDoc("v", 2)),
                ErrorCode.INVALID_PARAMS,
                "Invalid params: document 2 has no vector in the field 'v'");
        assertFails(() -> node.handle(GetKnnGraphNodeParams.ofDoc("v", 4)), ErrorCode.DOC_NOT_FOUND, null);
    }

    @Test
    void needsAReader() throws Exception {
        currentIndex.replace(TestIndexes.withoutReader(writeIndex(), DirectoryKind.FS));

        assertFails(() -> graph.handle(new GetKnnGraphParams("v")), ErrorCode.NO_READER, null);
        assertFails(() -> node.handle(GetKnnGraphNodeParams.ofDoc("v", 0)), ErrorCode.NO_READER, null);
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> graph.handle(new GetKnnGraphParams("v")), ErrorCode.NO_INDEX_OPEN, null);
    }

    /**
     * One segment: "v" is (1, 0), (0, 1), none, (1, 1) in documents 0 to 3, with a graph although
     * Lucene builds none for so few vectors.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        Codec codec = Codec.getDefault();
        KnnVectorsFormat vectors = new PerFieldKnnVectorsFormat() {
            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                return new Lucene99HnswVectorsFormat(16, 100, 0);
            }
        };
        float[][] values = {{1, 0}, {0, 1}, null, {1, 1}};
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        directory, new IndexWriterConfig().setCodec(new FilterCodec(codec.getName(), codec) {
                                    @Override
                                    public KnnVectorsFormat knnVectorsFormat() {
                                        return vectors;
                                    }
                                }))) {
            for (int i = 0; i < values.length; i++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
                if (values[i] != null) {
                    doc.add(new KnnFloatVectorField("v", values[i], VectorSimilarityFunction.EUCLIDEAN));
                }
                writer.addDocument(doc);
            }
        }
        return dir;
    }

    private static void assertFails(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code, String message) {
        var thrown = assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
        if (message != null) {
            thrown.hasMessage(message);
        }
    }
}
