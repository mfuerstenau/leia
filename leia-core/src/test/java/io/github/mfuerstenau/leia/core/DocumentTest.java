package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.legacyIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.IndexDocument.DocValues;
import io.github.mfuerstenau.leia.core.IndexDocument.StoredValue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentTest {

    /**
     * The "body" field: not stored, with term vectors that have positions, offsets and payloads.
     * Lucene wants the same type for a field in every document.
     */
    private static final FieldType BODY = new FieldType(TextField.TYPE_NOT_STORED);

    static {
        BODY.setStoreTermVectors(true);
        BODY.setStoreTermVectorPositions(true);
        BODY.setStoreTermVectorOffsets(true);
        BODY.setStoreTermVectorPayloads(true);
        BODY.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        BODY.freeze();
    }

    @TempDir
    Path tmp;

    @Test
    void readsStoredValuesNormsAndDocValues() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            IndexDocument doc = index.document(0);

            assertThat(doc.docId()).isZero();
            assertThat(doc.deleted()).isFalse();
            assertThat(doc.fields())
                    .extracting(IndexDocument.Field::name)
                    .containsExactly(
                            "id", "body", "stored", "rank", "prices", "category", "tags", "blob", "plain", "extra");
            assertThat(field(doc, "id"))
                    .isEqualTo(new IndexDocument.Field(
                            "id", OptionalLong.empty(), List.of(new StoredValue.Text("1")), Optional.empty(), false));
            IndexDocument.Field body = field(doc, "body");
            // Norms are the encoded field length: 3 terms.
            assertThat(body.norm()).hasValue(3);
            assertThat(body.stored()).containsExactly(new StoredValue.Text("red blue red"));
            assertThat(body.hasTermVector()).isTrue();
            assertThat(field(doc, "stored").stored())
                    .containsExactly(
                            new StoredValue.IntValue(7),
                            new StoredValue.LongValue(-8L),
                            new StoredValue.FloatValue(1.5f),
                            new StoredValue.DoubleValue(Double.NaN),
                            new StoredValue.Binary(new BytesRef(new byte[] {(byte) 0xff, 0})));
            assertThat(field(doc, "rank").docValues())
                    .hasValue(new DocValues(DocValuesType.NUMERIC, List.of(42L), List.of()));
            // Sorted numeric and sorted set values come in ascending order.
            assertThat(field(doc, "prices").docValues())
                    .hasValue(new DocValues(DocValuesType.SORTED_NUMERIC, List.of(-1L, 5L), List.of()));
            assertThat(field(doc, "category").docValues())
                    .hasValue(new DocValues(DocValuesType.SORTED, List.of(), List.of(new BytesRef("books"))));
            assertThat(field(doc, "tags").docValues())
                    .hasValue(new DocValues(
                            DocValuesType.SORTED_SET, List.of(), List.of(new BytesRef("a"), new BytesRef("b"))));
            assertThat(field(doc, "blob").docValues())
                    .hasValue(new DocValues(DocValuesType.BINARY, List.of(), List.of(new BytesRef(new byte[] {1, 2}))));
            assertThat(field(doc, "plain").hasTermVector()).isFalse();
        }
    }

    @Test
    void readsADocumentInALaterSegment() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            // Document 2 is the first of the second segment, the only one with "extra".
            IndexDocument doc = index.document(2);

            assertThat(field(doc, "id").stored()).containsExactly(new StoredValue.Text("3"));
            assertThat(field(doc, "extra").stored()).containsExactly(new StoredValue.Text("only here"));
            assertThat(field(doc, "rank").docValues())
                    .hasValue(new DocValues(DocValuesType.NUMERIC, List.of(44L), List.of()));
            assertThat(field(doc, "body").norm()).hasValue(1);
            // The first segment has no "extra" field.
            assertThat(field(index.document(0), "extra"))
                    .isEqualTo(
                            new IndexDocument.Field("extra", OptionalLong.empty(), List.of(), Optional.empty(), false));
        }
    }

    @Test
    void readsADeletedDocument() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            IndexDocument doc = index.document(1);

            assertThat(doc.deleted()).isTrue();
            assertThat(field(doc, "id").stored()).containsExactly(new StoredValue.Text("2"));
            assertThat(field(doc, "rank").docValues())
                    .hasValue(new DocValues(DocValuesType.NUMERIC, List.of(43L), List.of()));
        }
    }

    @Test
    void documentWithoutValuesInAFieldHasNone() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            // Document 1 has neither prices nor tags, though its segment has both fields.
            IndexDocument doc = index.document(1);

            assertThat(field(doc, "prices").docValues()).isEmpty();
            assertThat(field(doc, "tags").docValues()).isEmpty();
            assertThat(field(doc, "stored").stored()).isEmpty();
            assertThat(field(doc, "body").stored()).isEmpty();
            assertThat(field(doc, "body").hasTermVector()).isTrue();
            assertThat(field(doc, "plain").hasTermVector()).isFalse();
        }
    }

    @Test
    void readsTheStoredValuesOfOneField() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.storedValues(0, "stored"))
                    .isEqualTo(field(index.document(0), "stored").stored())
                    .hasSize(5);
            assertThat(index.storedValues(2, "extra")).containsExactly(new StoredValue.Text("only here"));
            // Deleted, and without stored values in the field.
            assertThat(index.storedValues(1, "id")).containsExactly(new StoredValue.Text("2"));
            assertThat(index.storedValues(1, "stored")).isEmpty();
            assertThatThrownBy(() -> index.storedValues(0, "nope")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.storedValues(4, "id")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsTermVectorWithPositionsOffsetsAndPayloads() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            List<TermVectorTerm> terms = index.termVector(0, "body");

            assertThat(terms)
                    .containsExactly(
                            new TermVectorTerm(
                                    new BytesRef("blue"),
                                    1,
                                    List.of(position(1, 4, 8, Optional.of(new BytesRef("p1"))))),
                            new TermVectorTerm(
                                    new BytesRef("red"),
                                    2,
                                    List.of(
                                            position(0, 0, 3, Optional.of(new BytesRef("p0"))),
                                            position(2, 9, 12, Optional.empty()))));
        }
    }

    @Test
    void termVectorWithoutPositionsHasNoPositions() throws Exception {
        Path dir = tmp.resolve("index");
        FieldType freqsOnly = new FieldType(TextField.TYPE_NOT_STORED);
        freqsOnly.setStoreTermVectors(true);
        FieldType offsetsOnly = new FieldType(freqsOnly);
        offsetsOnly.setStoreTermVectorOffsets(true);
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new Field("freqs", "a b a", freqsOnly));
            doc.add(new Field("offsets", "a b", offsetsOnly));
            writer.addDocument(doc);
        }

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.termVector(0, "freqs"))
                    .containsExactly(
                            new TermVectorTerm(new BytesRef("a"), 2, List.of()),
                            new TermVectorTerm(new BytesRef("b"), 1, List.of()));
            assertThat(index.termVector(0, "offsets"))
                    .containsExactly(
                            new TermVectorTerm(
                                    new BytesRef("a"),
                                    1,
                                    List.of(new TermPosition(
                                            OptionalInt.empty(),
                                            OptionalInt.of(0),
                                            OptionalInt.of(1),
                                            Optional.empty()))),
                            new TermVectorTerm(
                                    new BytesRef("b"),
                                    1,
                                    List.of(new TermPosition(
                                            OptionalInt.empty(),
                                            OptionalInt.of(2),
                                            OptionalInt.of(3),
                                            Optional.empty()))));
        }
    }

    @Test
    void fieldWithoutTermVectorHasAnEmptyOne() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.termVector(0, "plain")).isEmpty();
            assertThat(index.termVector(1, "plain")).isEmpty();
            assertThat(index.termVector(1, "body"))
                    .containsExactly(
                            new TermVectorTerm(new BytesRef("green"), 1, List.of(position(0, 0, 5, Optional.empty()))));
        }
    }

    @Test
    void rejectsUnknownDocumentOrField() throws Exception {
        Path dir = writeIndex();

        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            assertThat(index.hasDocId(3)).isTrue();
            assertThat(index.hasDocId(4)).isFalse();
            assertThat(index.hasDocId(-1)).isFalse();
            assertThatThrownBy(() -> index.document(4))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No document 4; maxDoc is 4");
            assertThatThrownBy(() -> index.document(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.termVector(0, "nope"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No field nope");
        }
    }

    @Test
    void readsDocumentsOfALucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(legacyIndex("lucene9"))) {
            // "document number N" for N = 1..3; document 2 is deleted.
            IndexDocument doc = index.document(1);

            assertThat(doc.deleted()).isTrue();
            assertThat(doc.fields()).extracting(IndexDocument.Field::name).containsExactly("id", "body");
            assertThat(field(doc, "id").stored()).containsExactly(new StoredValue.Text("2"));
            assertThat(field(doc, "body").stored()).containsExactly(new StoredValue.Text("document number 2"));
            assertThat(field(doc, "body").norm()).hasValue(3);
            assertThat(index.document(2).deleted()).isFalse();
        }
    }

    private static IndexDocument.Field field(IndexDocument doc, String name) {
        return doc.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static TermPosition position(int position, int start, int end, Optional<BytesRef> payload) {
        return new TermPosition(OptionalInt.of(position), OptionalInt.of(start), OptionalInt.of(end), payload);
    }

    /**
     * Four documents, ids 1 to 4. Documents 1 and 2 (doc ids 0, 1) are in the first segment, and
     * doc id 1 is deleted; documents 3 and 4 are in the second segment, which alone has the field
     * "extra".
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            Document first = new Document();
            first.add(new StringField("id", "1", Field.Store.YES));
            first.add(new Field(
                    "body",
                    new PayloadTokens(new String[] {"red", "blue", "red"}, new int[] {0, 4, 9}, "p0", "p1", null),
                    BODY));
            first.add(new StoredField("body", "red blue red"));
            first.add(new StoredField("stored", 7));
            first.add(new StoredField("stored", -8L));
            first.add(new StoredField("stored", 1.5f));
            first.add(new StoredField("stored", Double.NaN));
            first.add(new StoredField("stored", new BytesRef(new byte[] {(byte) 0xff, 0})));
            first.add(new NumericDocValuesField("rank", 42));
            first.add(new SortedNumericDocValuesField("prices", 5));
            first.add(new SortedNumericDocValuesField("prices", -1));
            first.add(new SortedDocValuesField("category", new BytesRef("books")));
            first.add(new SortedSetDocValuesField("tags", new BytesRef("b")));
            first.add(new SortedSetDocValuesField("tags", new BytesRef("a")));
            first.add(new BinaryDocValuesField("blob", new BytesRef(new byte[] {1, 2})));
            first.add(new TextField("plain", "no vectors", Field.Store.NO));
            writer.addDocument(first);
            writer.addDocument(document("2", "green", 43));
            // Committed first, so that the delete marks a document in a segment instead of dropping
            // it from the in-memory buffer.
            writer.commit();
            Document third = document("3", "yellow", 44);
            third.add(new StringField("extra", "only here", Field.Store.YES));
            writer.addDocument(third);
            writer.addDocument(document("4", "blue", 45));
            writer.deleteDocuments(new Term("id", "2"));
            writer.commit();
        }
        return dir;
    }

    private static Document document(String id, String body, long rank) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new Field("body", body, BODY));
        doc.add(new NumericDocValuesField("rank", rank));
        return doc;
    }
}
