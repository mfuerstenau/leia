package io.github.mfuerstenau.leia.server.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.IndexDocument;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Response;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.SortedSetDocValuesField;
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

/** {@code getDocument} and {@code getTermVector}. */
class DocumentHandlersTest {

    @TempDir
    Path tmp;

    private final CurrentIndex currentIndex = new CurrentIndex(new Jobs());
    private final GetDocumentHandler getDocument = new GetDocumentHandler(currentIndex);
    private final GetTermVectorHandler getTermVector = new GetTermVectorHandler(currentIndex);

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
                                        false),
                                new DocumentField("body", OptionalLong.of(2), List.of(), Optional.empty(), true),
                                new DocumentField(
                                        "tags",
                                        OptionalLong.empty(),
                                        List.of(),
                                        Optional.of(new DocValues(
                                                DocValuesType.SORTED_SET,
                                                List.of(),
                                                List.of(new BytesValue("a", false), new BytesValue("ff", true)))),
                                        false))));
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
    void failsWithoutOpenIndex() {
        assertFails(() -> getDocument.handle(new GetDocumentParams(0)), ErrorCode.NO_INDEX_OPEN);
        assertFails(() -> getTermVector.handle(new GetTermVectorParams(0, "body")), ErrorCode.NO_INDEX_OPEN);
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
        assertThat(errorCodeOf("getDocument.error-doc-not-found.json")).isEqualTo(ErrorCode.DOC_NOT_FOUND);
    }

    @Test
    void failsForUnknownField() throws Exception {
        openIndex();

        assertThatThrownBy(() -> getTermVector.handle(new GetTermVectorParams(0, "bdy")))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_FOUND))
                .hasMessage("No field 'bdy' in the index");
        assertThat(errorCodeOf("getTermVector.error-field-not-found.json")).isEqualTo(ErrorCode.FIELD_NOT_FOUND);
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

    private static ErrorCode errorCodeOf(String fixture) throws IOException {
        String json = Files.readString(Path.of(System.getProperty("leia.protocol.dir"))
                .resolve("fixtures")
                .resolve(fixture));
        Response<?> response = ProtocolJson.mapper().readValue(json, ProtocolJson.responseType(Object.class));
        return response.error().orElseThrow().code();
    }
}
