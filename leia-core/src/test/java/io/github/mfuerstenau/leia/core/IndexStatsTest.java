package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.core.FieldStats.TermStats;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexStatsTest {

    private static final FieldType TEXT_WITH_TERM_VECTORS = textWithTermVectors();

    @TempDir
    Path tmp;

    @Test
    void reportsCommitAndFields() throws Exception {
        Path dir = writeIndex(tmp.resolve("index"));

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            IndexStats stats = index.stats();

            assertThat(stats.path()).isEqualTo(index.path());
            IndexStats.CommitStats commit = stats.indexes().getFirst();
            assertThat(commit.summary()).isEqualTo(index.indexes().getFirst());
            assertThat(stats.directoryImpl()).isEqualTo("MMapDirectory");
            assertThat(commit.generation()).isEqualTo(2);
            assertThat(commit.version()).isEqualTo(((DirectoryReader) index.reader()).getVersion());
            assertThat(stats.segmentCount()).isEqualTo(2);
            assertThat(commit.segmentCount()).isEqualTo(2);
            // The open reader's segments, one deleted document in the first.
            assertThat(commit.segments())
                    .extracting(
                            IndexStats.SegmentStats::name,
                            IndexStats.SegmentStats::docBase,
                            IndexStats.SegmentStats::maxDoc,
                            IndexStats.SegmentStats::numDocs)
                    .containsExactly(tuple("_0", 0, 2, 1), tuple("_1", 2, 1, 1));
            // Each with its id and its own files, which the stored fields cache keys on (ADR 0113).
            assertThat(commit.segments()).allSatisfy(segment -> {
                assertThat(segment.id()).satisfies(id -> assertThat(id).hasSize(32));
                assertThat(segment.files()).contains(segment.name() + ".si").isSorted();
            });
            assertThat(commit.segments().get(0).id())
                    .isNotEqualTo(commit.segments().get(1).id());
            assertThat(commit.commitLuceneVersion()).isEqualTo(Version.LATEST);
            assertThat(commit.userData()).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
            assertThat(stats.deletedDocs()).isEqualTo(1);
            assertThat(stats.fields())
                    .containsExactly(
                            field("id", 0, IndexOptions.DOCS, false, false, DocValuesType.NONE, 0, 0, 0)
                                    .withTerms(3, 3, 3),
                            // Deleted documents count: 3 docs with the terms "red" (twice) and "blue".
                            field(
                                            "body",
                                            1,
                                            IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                                            true,
                                            true,
                                            DocValuesType.NONE,
                                            0,
                                            0,
                                            0)
                                    .withTerms(3, 6, 9),
                            field("price", 2, IndexOptions.NONE, false, false, DocValuesType.NONE, 1, 4, 0)
                                    .withoutTerms(),
                            field("rank", 3, IndexOptions.NONE, false, false, DocValuesType.NUMERIC, 0, 0, 0)
                                    .withoutTerms(),
                            field("stored", 4, IndexOptions.NONE, false, false, DocValuesType.NONE, 0, 0, 0)
                                    .withoutTerms(),
                            field("vector", 5, IndexOptions.NONE, false, false, DocValuesType.NONE, 0, 0, 3)
                                    .withoutTerms());
            assertThat(stats.field("body"))
                    .hasValueSatisfying(field -> assertThat(field.number()).isEqualTo(1));
            assertThat(stats.field("missing")).isEmpty();
        }
    }

    @Test
    void reportsEmptyIndex() throws Exception {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.commit();
        }

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            IndexStats stats = index.stats();

            assertThat(stats.segmentCount()).isZero();
            assertThat(stats.fields()).isEmpty();
            assertThat(stats.indexes().getFirst().userData()).isEmpty();
            assertThat(stats.deletedDocs()).isZero();
        }
    }

    @Test
    void reportsLucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            IndexStats stats = index.stats();

            assertThat(stats.indexes().getFirst().generation()).isEqualTo(2);
            assertThat(stats.segmentCount()).isEqualTo(1);
            assertThat(stats.indexes().getFirst().commitLuceneVersion().major).isEqualTo(9);
            assertThat(stats.indexes().getFirst().userData())
                    .containsExactly(Map.entry("writer", "leia LegacyIndexWriter"));
            assertThat(stats.fields())
                    .extracting(FieldStats::name, FieldStats::indexOptions, FieldStats::vectorDimension)
                    .containsExactly(
                            tuple("id", IndexOptions.DOCS, 0),
                            tuple("body", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, 0),
                            tuple("vector", IndexOptions.NONE, 3),
                            tuple("number", IndexOptions.NONE, 0));
        }
    }

    /**
     * Two segments: documents 1 and 2 in the first, 3 in the second; document 2 is deleted. Every
     * document has the same fields, so the field numbers follow their order here.
     */
    private static Path writeIndex(Path dir) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(
                        // No merges, which would reclaim the deleted document and join the segments.
                        directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.addDocument(document(1));
            writer.addDocument(document(2));
            writer.commit();
            writer.addDocument(document(3));
            writer.deleteDocuments(new Term("id", "2"));
            writer.setLiveCommitData(Map.of("b", "2", "a", "1").entrySet());
            writer.commit();
        }
        return dir;
    }

    private static Document document(int id) {
        Document doc = new Document();
        doc.add(new StringField("id", Integer.toString(id), Field.Store.YES));
        doc.add(new Field("body", "red blue red", TEXT_WITH_TERM_VECTORS));
        doc.add(new IntPoint("price", id * 10));
        doc.add(new NumericDocValuesField("rank", id));
        doc.add(new StoredField("stored", "only stored"));
        doc.add(new KnnFloatVectorField(
                "vector", new float[] {id, 0, 1}, VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT));
        return doc;
    }

    private static FieldType textWithTermVectors() {
        FieldType type = new FieldType(TextField.TYPE_NOT_STORED);
        type.setStoreTermVectors(true);
        type.freeze();
        return type;
    }

    private static FieldBuilder field(
            String name,
            int number,
            IndexOptions indexOptions,
            boolean hasNorms,
            boolean hasTermVectors,
            DocValuesType docValuesType,
            int pointDimensionCount,
            int pointNumBytes,
            int vectorDimension) {
        return terms -> new FieldStats(
                name,
                number,
                indexOptions,
                hasNorms,
                false,
                hasTermVectors,
                docValuesType,
                pointDimensionCount,
                pointNumBytes,
                vectorDimension,
                VectorEncoding.FLOAT32,
                vectorDimension == 0
                        ? VectorSimilarityFunction.EUCLIDEAN
                        : VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT,
                terms);
    }

    @FunctionalInterface
    private interface FieldBuilder {
        FieldStats build(@Nullable TermStats terms);

        default FieldStats withTerms(int docCount, long sumDocFreq, long sumTotalTermFreq) {
            return build(new TermStats(docCount, sumDocFreq, sumTotalTermFreq));
        }

        default FieldStats withoutTerms() {
            return build(null);
        }
    }
}
