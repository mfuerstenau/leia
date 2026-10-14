package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnnTest {

    private static final Similarity BM25 = Similarities.bm25(1.2f, 0.75f);
    /** Documents written; {@link #NO_VECTOR} has no vectors and {@link #DELETED} is deleted. */
    private static final int DOCS = 60;

    private static final int NO_VECTOR = 7;
    private static final int DELETED = 40;

    @TempDir
    Path tmp;

    @Test
    void readsADocumentsVectors() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(field(index.document(3), "f").vector()).hasValue(new KnnVector.Floats(floats(3)));
            assertThat(field(index.document(3), "b").vector()).hasValue(new KnnVector.Bytes(bytes(3)));
            // In the second segment.
            assertThat(field(index.document(45), "f").vector()).hasValue(new KnnVector.Floats(floats(45)));
            assertThat(field(index.document(NO_VECTOR), "f").vector()).isEmpty();
            assertThat(field(index.document(3), "color").vector()).isEmpty();
            assertThat(index.document(DELETED).deleted()).isTrue();
            assertThat(index.vector(DELETED, "f")).hasValue(new KnnVector.Floats(floats(DELETED)));
            assertThat(index.vector(NO_VECTOR, "b")).isEmpty();
            assertThatThrownBy(() -> index.vector(DOCS, "f")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.vector(0, "nope")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void findsTheNearestDocumentsApproximately() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            float[] target = floats(3);
            Query query = KnnQueries.approximate(field(index, "f"), new KnnVector.Floats(target), 5, Optional.empty());
            SearchResults results = search(index, query, 10);

            // The graph of so few vectors finds the exact nearest.
            assertThat(ids(results)).containsExactlyElementsOf(nearest(target, 5, Optional.empty()));
            assertThat(results.hits().getFirst().score()).isEqualTo(1f);
            assertThat(results.totalHits()).isEqualTo(new TotalHits(5, TotalHits.Relation.EQUAL_TO));
            assertThat(results.query()).startsWith("KnnFloatVectorQuery:f[");
        }
    }

    @Test
    void scoresEveryDocumentWithAVectorExactly() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            float[] target = floats(3);
            Query query = KnnQueries.exact(field(index, "f"), new KnnVector.Floats(target), Optional.empty());
            SearchResults results = search(index, query, 100);

            // Every live document but the one without a vector.
            assertThat(results.totalHits().value()).isEqualTo(DOCS - 2);
            assertThat(ids(results)).containsExactlyElementsOf(nearest(target, DOCS, Optional.empty()));
            for (SearchResults.Hit hit : results.hits()) {
                assertThat(hit.score())
                        .isCloseTo(
                                VectorSimilarityFunction.EUCLIDEAN.compare(target, floats(hit.docId())), within(1e-6f));
            }
            assertThat(results.query()).isEqualTo("ExactKnnQuery:f[" + target[0] + ",...]");
        }
    }

    @Test
    void searchesOnlyTheDocumentsOfTheFilter() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            float[] target = floats(3);
            Optional<Query> red = Optional.of(new TermQuery(new Term("color", "red")));
            KnnVector vector = new KnnVector.Floats(target);
            List<Integer> expected = nearest(target, 4, red);

            assertThat(ids(search(index, KnnQueries.approximate(field(index, "f"), vector, 4, red), 10)))
                    .containsExactlyElementsOf(expected);
            assertThat(ids(search(index, KnnQueries.exact(field(index, "f"), vector, red), 4)))
                    .containsExactlyElementsOf(expected);
            assertThat(KnnQueries.exact(field(index, "f"), vector, red).toString())
                    .endsWith("[color:red]");
        }
    }

    @Test
    void searchesByteVectorsBothWays() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            byte[] target = bytes(11);
            KnnVector vector = new KnnVector.Bytes(target);
            List<Integer> exact = ids(search(index, KnnQueries.exact(field(index, "b"), vector, Optional.empty()), 3));
            List<Integer> approximate =
                    ids(search(index, KnnQueries.approximate(field(index, "b"), vector, 3, Optional.empty()), 3));

            assertThat(exact.getFirst()).isEqualTo(11);
            assertThat(approximate).containsExactlyElementsOf(exact);
        }
    }

    @Test
    void cancelsAnExactSearch() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            AtomicInteger calls = new AtomicInteger();
            ScanProgress cancelled = _ -> {
                if (calls.incrementAndGet() > 1) {
                    throw new CancellationException("cancelled");
                }
            };
            Query query = KnnQueries.exact(field(index, "f"), new KnnVector.Floats(floats(3)), Optional.empty());

            assertThatThrownBy(() ->
                            index.search(query, BM25, Optional.empty(), 0, 10, false, Optional.empty(), cancelled))
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void cancelsAnApproximateSearch() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            AtomicInteger calls = new AtomicInteger();
            ScanProgress cancelled = _ -> {
                if (calls.incrementAndGet() > 1) {
                    throw new CancellationException("cancelled");
                }
            };
            Query query =
                    KnnQueries.approximate(field(index, "f"), new KnnVector.Floats(floats(3)), 10, Optional.empty());

            assertThatThrownBy(() ->
                            index.search(query, BM25, Optional.empty(), 0, 10, false, Optional.empty(), cancelled))
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void refusesTargetsThatDontFit() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            FieldInfo f = field(index, "f");
            FieldInfo b = field(index, "b");
            assertThatThrownBy(() -> KnnQueries.check(field(index, "color"), new KnnVector.Floats(new float[] {1})))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'color' has no KNN vectors");
            assertThatThrownBy(() -> KnnQueries.check(f, new KnnVector.Bytes(new byte[] {1, 2, 3, 4})))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'f' has vectors of floats, not of bytes");
            assertThatThrownBy(() -> KnnQueries.check(f, new KnnVector.Floats(new float[] {1, 2})))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'f' has vectors of 4 dimensions, not 2");
            assertThatThrownBy(() -> KnnQueries.check(f, new KnnVector.Floats(new float[] {1, Float.NaN, 0, 0})))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("component 1 of the vector is NaN, not a finite number");
            assertThatThrownBy(() -> KnnQueries.check(b, new KnnVector.Bytes(new byte[3])))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the field 'b' compares by cosine, which a vector of zeros has none of");
            // Euclidean distance has a vector of zeros.
            KnnQueries.check(f, new KnnVector.Floats(new float[4]));
            assertThatThrownBy(() -> KnnQueries.approximate(f, new KnnVector.Floats(new float[4]), 0, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("k must be at least 1: 0");
        }
    }

    private static SearchResults search(ReadOnlyIndex index, Query query, int limit)
            throws IOException, InvalidQueryException {
        return index.search(query, BM25, Optional.empty(), 0, limit, true, Optional.empty(), ScanProgress.NONE);
    }

    private static List<Integer> ids(SearchResults results) {
        return results.hits().stream().map(SearchResults.Hit::docId).toList();
    }

    /** The live documents with a vector nearest to {@code target}, by brute force. */
    private static List<Integer> nearest(float[] target, int k, Optional<Query> red) {
        return IntStream.range(0, DOCS)
                .filter(id -> id != NO_VECTOR && id != DELETED)
                .filter(id -> red.isEmpty() || color(id).equals("red"))
                .boxed()
                .sorted(Comparator.comparingDouble(
                        (Integer id) -> -VectorSimilarityFunction.EUCLIDEAN.compare(target, floats(id))))
                .limit(k)
                .toList();
    }

    private static IndexDocument.Field field(IndexDocument doc, String name) {
        return doc.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static FieldInfo field(ReadOnlyIndex index, String name) {
        return index.fieldInfo(name).orElseThrow();
    }

    /** The float vector of document {@code id}: random, but the same each time. */
    private static float[] floats(int id) {
        Random random = new Random(id);
        float[] vector = new float[4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }

    /** The byte vector of document {@code id}: random, never all zeros, but the same each time. */
    private static byte[] bytes(int id) {
        Random random = new Random(1000 + id);
        byte[] vector = new byte[3];
        random.nextBytes(vector);
        vector[0] |= 1;
        return vector;
    }

    private static String color(int id) {
        return id % 3 == 0 ? "red" : "blue";
    }

    /**
     * {@link #DOCS} documents in two segments, each with a float vector {@code f} (4 dimensions,
     * euclidean), a byte vector {@code b} (3 dimensions, cosine) and a {@code color}, but for {@link
     * #NO_VECTOR}, which has no vectors; {@link #DELETED} is deleted.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (int id = 0; id < DOCS; id++) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id), Field.Store.YES));
                doc.add(new StringField("color", color(id), Field.Store.NO));
                if (id != NO_VECTOR) {
                    doc.add(new KnnFloatVectorField("f", floats(id), VectorSimilarityFunction.EUCLIDEAN));
                    doc.add(new KnnByteVectorField("b", bytes(id), VectorSimilarityFunction.COSINE));
                }
                writer.addDocument(doc);
                if (id == DOCS / 2) {
                    writer.commit();
                }
            }
            writer.deleteDocuments(new Term("id", Integer.toString(DELETED)));
            writer.commit();
        }
        return dir;
    }
}
