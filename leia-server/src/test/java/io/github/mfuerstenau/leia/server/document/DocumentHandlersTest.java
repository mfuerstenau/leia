package io.github.mfuerstenau.leia.server.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.IndexDocument;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getDocument}, {@code getStoredValue} and {@code getTermVector}. */
class DocumentHandlersTest {

    @TempDir
    Path tmp;

    private final CurrentIndex currentIndex = new CurrentIndex(new Jobs(), new Filters());
    private final GetDocumentHandler getDocument = new GetDocumentHandler(currentIndex);
    private final GetTermVectorHandler getTermVector = new GetTermVectorHandler(currentIndex);
    private final GetStoredValueHandler getStoredValue = new GetStoredValueHandler(currentIndex);

    @Test
    void readsADeletedDocument() throws Exception {
        openIndex();

        assertThat(getDocument.handle(new GetDocumentParams(1)))
                .isEqualTo(new GetDocumentResult(
                        1,
                        true,
                        List.of(
                                new DocumentField(
                                        "id",
                                        OptionalLong.empty(),
                                        List.of(new StoredValue(StoredType.STRING, "2", false)),
                                        Optional.empty(),
                                        false,
                                        Optional.empty()),
                                new DocumentField(
                                        "body",
                                        OptionalLong.of(2),
                                        List.of(),
                                        Optional.empty(),
                                        true,
                                        Optional.empty()),
                                new DocumentField(
                                        "tags",
                                        OptionalLong.empty(),
                                        List.of(),
                                        Optional.of(new DocValues(
                                                DocValuesType.SORTED_SET,
                                                List.of(),
                                                List.of(new BytesValue("a", false), new BytesValue("ff", true)))),
                                        false,
                                        Optional.empty()))));
    }

    @Test
    void readsATermVector() throws Exception {
        openIndex();

        assertThat(getTermVector.handle(new GetTermVectorParams(0, "body")))
                .isEqualTo(new GetTermVectorResult(List.of(
                        new TermVectorTerm("blue", false, 1, List.of(position(1, 4, 8))),
                        new TermVectorTerm("red", false, 2, List.of(position(0, 0, 3), position(2, 9, 12))))));
        assertThat(getTermVector.handle(new GetTermVectorParams(0, "id")).terms())
                .isEmpty();
    }

    @Test
    void cutsLongValuesAndFetchesThemWhole() throws Exception {
        int max = GetDocumentResult.MAX_VALUE_LENGTH;
        // "ü" takes 2 bytes, so the cut at an odd byte count falls back by one.
        String text = "a" + "ü".repeat(max);
        byte[] bytes = new byte[max + 10];
        bytes[0] = (byte) 0xff;
        openIndex(doc -> {
            doc.add(new StoredField("big", text));
            doc.add(new StoredField("big", new BytesRef(bytes)));
            doc.add(new StoredField("big", "small"));
        });

        List<StoredValue> stored = getDocument.handle(new GetDocumentParams(0)).fields().stream()
                .filter(field -> field.name().equals("big"))
                .findFirst()
                .orElseThrow()
                .stored();
        assertThat(stored.get(0))
                .isEqualTo(new StoredValue(
                        StoredType.STRING, text.substring(0, max / 2), false, OptionalLong.of(1 + 2L * max)));
        assertThat(stored.get(1).value()).hasSize(2 * max).startsWith("ff00");
        assertThat(stored.get(1).fullLength()).hasValue(max + 10);
        assertThat(stored.get(2)).isEqualTo(new StoredValue(StoredType.STRING, "small", false));

        assertThat(getStoredValue.handle(new GetStoredValueParams(0, "big", 0)))
                .isEqualTo(new GetStoredValueResult(new StoredValue(StoredType.STRING, text, false)));
        assertThat(getStoredValue.handle(new GetStoredValueParams(0, "big", 1)).value())
                .satisfies(value -> assertThat(value.value()).hasSize(2 * (max + 10)))
                .satisfies(value -> assertThat(value.fullLength()).isEmpty());
    }

    @Test
    void cutsTextBetweenCharacters() {
        // A surrogate pair is 4 bytes of UTF-8 and is never split.
        String text = "ab\uD83D\uDE00c";
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.Text(text), 5))
                .isEqualTo(new StoredValue(StoredType.STRING, "ab", false, OptionalLong.of(7)));
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.Text(text), 6))
                .isEqualTo(new StoredValue(StoredType.STRING, "ab\uD83D\uDE00", false, OptionalLong.of(7)));
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.Text(text), 7))
                .isEqualTo(new StoredValue(StoredType.STRING, text, false));
        // Bytes that are not UTF-8 stay hex when cut, even if the cut bytes would be valid UTF-8.
        assertThat(GetDocumentHandler.storedValue(
                        new IndexDocument.StoredValue.Binary(new BytesRef(new byte[] {0x61, 0x62, (byte) 0xff})), 2))
                .isEqualTo(new StoredValue(StoredType.BINARY, "6162", true, OptionalLong.of(3)));
        // Numbers are never cut.
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.LongValue(-123456), 2))
                .isEqualTo(new StoredValue(StoredType.LONG, "-123456", false));
    }

    @Test
    void readsOneStoredValueOfADeletedDocument() throws Exception {
        openIndex();

        assertThat(getStoredValue.handle(new GetStoredValueParams(1, "id", 0)))
                .isEqualTo(new GetStoredValueResult(new StoredValue(StoredType.STRING, "2", false)));
        assertThatThrownBy(() -> getStoredValue.handle(new GetStoredValueParams(1, "id", 1)))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALUE_NOT_FOUND))
                .hasMessage("No stored value 1 in field 'id' of document 1: it has 1");
        assertFails(() -> getStoredValue.handle(new GetStoredValueParams(1, "id", -1)), ErrorCode.VALUE_NOT_FOUND);
        assertFails(() -> getStoredValue.handle(new GetStoredValueParams(1, "body", 0)), ErrorCode.VALUE_NOT_FOUND);
        assertFails(() -> getStoredValue.handle(new GetStoredValueParams(1, "bdy", 0)), ErrorCode.FIELD_NOT_FOUND);
        assertFails(() -> getStoredValue.handle(new GetStoredValueParams(3, "id", 0)), ErrorCode.DOC_NOT_FOUND);
    }

    @Test
    void failsWithoutOpenIndex() {
        assertFails(() -> getDocument.handle(new GetDocumentParams(0)), ErrorCode.NO_INDEX_OPEN);
        assertFails(() -> getTermVector.handle(new GetTermVectorParams(0, "body")), ErrorCode.NO_INDEX_OPEN);
        assertFails(() -> getStoredValue.handle(new GetStoredValueParams(0, "id", 0)), ErrorCode.NO_INDEX_OPEN);
    }

    @Test
    void failsForUnknownDocument() throws Exception {
        openIndex();

        assertThatThrownBy(() -> getDocument.handle(new GetDocumentParams(3)))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DOC_NOT_FOUND))
                .hasMessage("No document 3 in the index: maxDoc is 3");
        assertFails(() -> getDocument.handle(new GetDocumentParams(-1)), ErrorCode.DOC_NOT_FOUND);
        assertFails(() -> getTermVector.handle(new GetTermVectorParams(3, "body")), ErrorCode.DOC_NOT_FOUND);
    }

    @Test
    void failsForUnknownField() throws Exception {
        openIndex();

        assertThatThrownBy(() -> getTermVector.handle(new GetTermVectorParams(0, "bdy")))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_FOUND))
                .hasMessage("No field 'bdy' in the index");
    }

    @Test
    void sendsStoredValuesAsText() {
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.IntValue(-7)))
                .isEqualTo(new StoredValue(StoredType.INT, "-7", false));
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.LongValue(Long.MAX_VALUE)))
                .isEqualTo(new StoredValue(StoredType.LONG, "9223372036854775807", false));
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.FloatValue(0.1f)))
                .isEqualTo(new StoredValue(StoredType.FLOAT, "0.1", false));
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.DoubleValue(1e10)))
                .isEqualTo(new StoredValue(StoredType.DOUBLE, "1.0E10", false));
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.DoubleValue(Double.NEGATIVE_INFINITY)))
                .isEqualTo(new StoredValue(StoredType.DOUBLE, "-Infinity", false));
        assertThat(GetDocumentHandler.storedValue(new IndexDocument.StoredValue.Binary(new BytesRef("grüße"))))
                .isEqualTo(new StoredValue(StoredType.BINARY, "grüße", false));
        assertThat(GetDocumentHandler.storedValue(
                        new IndexDocument.StoredValue.Binary(new BytesRef(new byte[] {0x61, (byte) 0xff, 0}, 1, 2))))
                .isEqualTo(new StoredValue(StoredType.BINARY, "ff00", true));
    }

    private static TermPosition position(int position, int start, int end) {
        return new TermPosition(OptionalInt.of(position), OptionalInt.of(start), OptionalInt.of(end), Optional.empty());
    }

    /**
     * One segment, three documents: "red blue red" (id 1), "green yellow" (id 2, deleted, tags "a" and
     * the bytes ff), "blue" (id 3). The body has term vectors with positions and offsets.
     */
    private void openIndex() throws Exception {
        openIndex(_ -> {});
    }

    /** {@link #openIndex()}, with {@code first} adding more fields to the first document. */
    private void openIndex(Consumer<Document> first) throws Exception {
        Path dir = tmp.resolve("index");
        FieldType body = new FieldType(TextField.TYPE_NOT_STORED);
        body.setStoreTermVectors(true);
        body.setStoreTermVectorPositions(true);
        body.setStoreTermVectorOffsets(true);
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            int id = 1;
            for (String text : List.of("red blue red", "green yellow", "blue")) {
                Document doc = new Document();
                doc.add(new StringField("id", Integer.toString(id++), Field.Store.YES));
                doc.add(new Field("body", text, body));
                if (id == 2) {
                    first.accept(doc);
                }
                if (id == 3) {
                    doc.add(new SortedSetDocValuesField("tags", new BytesRef("a")));
                    doc.add(new SortedSetDocValuesField("tags", new BytesRef(new byte[] {(byte) 0xff})));
                }
                writer.addDocument(doc);
            }
            writer.commit();
            writer.deleteDocuments(new Term("id", "2"));
        }
        currentIndex.replace(ReadOnlyIndex.open(dir));
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
