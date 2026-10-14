package io.github.mfuerstenau.leia.tui.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport.DocumentData;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport.ExportedDocument;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport.ExportedField;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The tests of {@code doc_export.rs}, ported. */
class DocumentExportTest {

    @TempDir
    Path dir;

    private static IndexField schemaField(String name, int number, IndexOptions indexOptions) {
        return new IndexField(
                name,
                number,
                indexOptions,
                indexOptions != IndexOptions.NONE,
                false,
                name.equals("body"),
                name.equals("year") ? DocValuesType.NUMERIC : DocValuesType.NONE,
                name.equals("year") ? 1 : 0,
                name.equals("year") ? 4 : 0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Function<String, Optional<IndexField>> schema() {
        Map<String, IndexField> fields = Map.of(
                "id",
                schemaField("id", 0, IndexOptions.DOCS),
                "body",
                schemaField("body", 1, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS),
                "labels",
                schemaField("labels", 2, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS),
                "year",
                schemaField("year", 3, IndexOptions.NONE),
                "empty",
                schemaField("empty", 4, IndexOptions.NONE),
                "embedding",
                new IndexField(
                        "embedding",
                        5,
                        IndexOptions.NONE,
                        false,
                        false,
                        false,
                        DocValuesType.NONE,
                        0,
                        0,
                        2,
                        Optional.of(VectorEncoding.FLOAT32),
                        Optional.of(VectorSimilarity.COSINE),
                        Optional.empty()));
        return name -> Optional.ofNullable(fields.get(name));
    }

    private static StoredValue string(String value) {
        return new StoredValue(StoredType.STRING, value, false);
    }

    private static DocumentField field(String name) {
        return new DocumentField(name, OptionalLong.empty(), List.of(), Optional.empty(), false, Optional.empty());
    }

    private static TermVectorTerm term(String text, int position, int start, int end) {
        return new TermVectorTerm(
                text,
                false,
                1,
                List.of(new TermPosition(
                        OptionalInt.of(position), OptionalInt.of(start), OptionalInt.of(end), Optional.empty())));
    }

    /**
     * Document 3: a stored id, a body with a term vector, unstored labels, doc values, and an empty
     * field; {@code body} is cut.
     */
    private static GetDocumentResult document() {
        return new GetDocumentResult(
                3,
                false,
                List.of(
                        new DocumentField(
                                "id",
                                OptionalLong.empty(),
                                List.of(string("doc-3")),
                                Optional.empty(),
                                false,
                                Optional.empty()),
                        new DocumentField(
                                "body",
                                OptionalLong.of(2),
                                List.of(new StoredValue(StoredType.STRING, "red b", false, OptionalLong.of(9))),
                                Optional.empty(),
                                true,
                                Optional.empty()),
                        new DocumentField(
                                "labels", OptionalLong.of(1), List.of(), Optional.empty(), false, Optional.empty()),
                        new DocumentField(
                                "year",
                                OptionalLong.empty(),
                                List.of(),
                                Optional.of(new DocValues(DocValuesType.NUMERIC, List.of(2024L), List.of())),
                                false,
                                Optional.empty()),
                        field("empty"),
                        new DocumentField(
                                "embedding",
                                OptionalLong.empty(),
                                List.of(),
                                Optional.empty(),
                                false,
                                Optional.of(new KnnVector(VectorEncoding.FLOAT32, List.of(0.5f, -1.25f), List.of())))));
    }

    private static List<TermVectorTerm> bodyTerms() {
        return List.of(term("blue", 1, 4, 8), term("red", 0, 0, 3));
    }

    private static List<ReconstructedField> rebuilt() {
        return List.of(
                new ReconstructedField(
                        "id",
                        IndexOptions.DOCS,
                        ReconstructSource.POSTINGS,
                        List.of(new TermVectorTerm("c3282d33", true, 1, List.of())),
                        List.of()),
                new ReconstructedField(
                        "body",
                        IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS,
                        ReconstructSource.TERM_VECTOR,
                        bodyTerms(),
                        List.of()),
                new ReconstructedField(
                        "labels",
                        IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                        ReconstructSource.POSTINGS,
                        List.of(new TermVectorTerm(
                                "new",
                                false,
                                1,
                                List.of(new TermPosition(
                                        OptionalInt.of(0),
                                        OptionalInt.empty(),
                                        OptionalInt.empty(),
                                        Optional.empty())))),
                        List.of()),
                // 2024 as an int point.
                new ReconstructedField(
                        "year",
                        IndexOptions.NONE,
                        ReconstructSource.POSTINGS,
                        List.of(),
                        List.of(new PointValue(List.of("800007e8")))));
    }

    /** Document 3, or a failure on the first whole stored value if {@code failing}; records the calls. */
    private static final class Fake implements DocumentExport.Fetch {

        final boolean failing;
        final List<String> calls = new ArrayList<>();

        Fake(boolean failing) {
            this.failing = failing;
        }

        @Override
        public CompletionStage<GetDocumentResult> document(int docId) {
            calls.add("document " + docId);
            return CompletableFuture.completedFuture(DocumentExportTest.document());
        }

        @Override
        public CompletionStage<StoredValue> storedValue(int docId, String field, int index) {
            calls.add("stored " + docId + " " + field + " " + index);
            if (failing) {
                return CompletableFuture.failedFuture(new IllegalStateException("gone"));
            }
            return CompletableFuture.completedFuture(string("red blue."));
        }

        @Override
        public CompletionStage<List<TermVectorTerm>> termVector(int docId, String field) {
            calls.add("term vector " + docId + " " + field);
            return CompletableFuture.completedFuture(bodyTerms());
        }
    }

    private static DocumentData loaded() {
        return DocumentExport.load(new Fake(false), 3).toCompletableFuture().join();
    }

    private static String golden() throws IOException {
        try (InputStream in = DocumentExportTest.class.getResourceAsStream("/document-export.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void loadsCutValuesWholeAndTheTermVectors() {
        Fake fake = new Fake(false);
        DocumentData data = DocumentExport.load(fake, 3).toCompletableFuture().join();

        assertThat(fake.calls).containsExactly("document 3", "stored 3 body 0", "term vector 3 body");
        assertThat(data.document().fields().get(1).stored()).containsExactly(string("red blue."));
        assertThat(data.document().fields().get(0).stored()).containsExactly(string("doc-3"));
        assertThat(data.termVectors()).containsOnlyKeys("body").containsEntry("body", bodyTerms());
    }

    @Test
    void loadingStopsAtTheFirstError() {
        Fake fake = new Fake(true);

        assertThatThrownBy(
                        () -> DocumentExport.load(fake, 3).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("gone");
        assertThat(fake.calls).containsExactly("document 3", "stored 3 body 0");
    }

    @Test
    void writesTheGoldenFile() throws IOException {
        ExportedDocument export =
                DocumentExport.assemble(loaded(), Optional.of(rebuilt()), schema(), "/i/demo", Optional.empty());

        assertThat(DocumentExport.toJson(export)).isEqualTo(golden());
        assertThat(DocumentExport.fromJson(golden())).isEqualTo(export);
    }

    @Test
    void exportsThePointsOfAFieldWithoutPostingsWithoutIndexedTerms() {
        ExportedDocument export =
                DocumentExport.assemble(loaded(), Optional.of(rebuilt()), schema(), "/i", Optional.empty());

        ExportedField year = export.fields().stream()
                .filter(field -> field.name().equals("year"))
                .findFirst()
                .orElseThrow();
        assertThat(year.indexed()).isEmpty();
        assertThat(year.points()).contains(List.of(new PointValue(List.of("800007e8"))));
        assertThat(export.fields().getFirst().points()).isEmpty();
    }

    @Test
    void readsVersionsOneAndTwoWithoutPoints() throws IOException {
        // The golden file as version 2 wrote it: without the points of "year".
        String v2 = golden().replace("\"version\": 3", "\"version\": 2")
                .replaceAll(
                        ",\\s*\"points\": \\[\\s*\\{\\s*\"dimensions\": \\[\\s*\"800007e8\"\\s*\\]\\s*\\}\\s*\\]", "");
        assertThat(v2).doesNotContain("points");

        ExportedDocument read = DocumentExport.fromJson(v2);

        assertThat(read.version()).isEqualTo(2);
        assertThat(read.fields()).allSatisfy(field -> assertThat(field.points()).isEmpty());
        String v1 = v2.replace("\"version\": 2", "\"version\": 1").replaceAll(",\\s*\"vector\": \\{[^}]*\\}", "");
        assertThat(DocumentExport.fromJson(v1).fields())
                .allSatisfy(field -> assertThat(field.vector()).isEmpty());
    }

    @Test
    void escapesControlCharactersInLowercaseHexAsSerdeDid() {
        ExportedDocument export =
                DocumentExport.assemble(loaded(), Optional.empty(), schema(), "/i/a\u001eb", Optional.empty());

        assertThat(DocumentExport.toJson(export)).contains("\"index\": \"/i/a\\u001eb\"");
    }

    @Test
    void leavesOutEmptyFieldsAndSaysWhetherTermsWereRebuilt() {
        ExportedDocument export = DocumentExport.assemble(loaded(), Optional.empty(), schema(), "/i", Optional.of("b"));

        assertThat(export.indexedTerms()).isFalse();
        assertThat(export.indexDirectory()).contains("b");
        assertThat(export.fields())
                .extracting(ExportedField::name)
                .containsExactly("id", "body", "labels", "year", "embedding");
        assertThat(export.fields()).allMatch(field -> field.indexed().isEmpty());
        String json = DocumentExport.toJson(export);
        assertThat(json).contains("\"indexDirectory\": \"b\"").contains("\"indexedTerms\": false");
    }

    @Test
    void keepsAFieldThatOnlyHasIndexedTerms() {
        DocumentData data = loaded();
        List<DocumentField> fields = new ArrayList<>(data.document().fields());
        fields.set(2, field("labels"));
        DocumentData unnormed =
                new DocumentData(new GetDocumentResult(3, false, List.copyOf(fields)), data.termVectors());

        ExportedDocument without =
                DocumentExport.assemble(unnormed, Optional.empty(), schema(), "/i", Optional.empty());
        assertThat(without.fields()).extracting(ExportedField::name).doesNotContain("labels");

        ExportedDocument with =
                DocumentExport.assemble(unnormed, Optional.of(rebuilt()), schema(), "/i", Optional.empty());
        ExportedField labels = with.fields().stream()
                .filter(field -> field.name().equals("labels"))
                .findFirst()
                .orElseThrow();
        assertThat(labels.flags()).isEqualTo("I-------");
        assertThat(labels.indexed().orElseThrow().source()).isEqualTo(ReconstructSource.POSTINGS);
    }

    @Test
    void hasNoNumberForAFieldTheIndexDoesNotKnow() {
        ExportedDocument export =
                DocumentExport.assemble(loaded(), Optional.empty(), _ -> Optional.empty(), "/i", Optional.empty());

        ExportedField id = export.fields().getFirst();
        assertThat(id.number()).isEmpty();
        assertThat(id.indexOptions()).isEqualTo(IndexOptions.NONE);
        assertThat(id.flags()).isEqualTo("---S----");
    }

    @Test
    void rejectsUnknownFormatsOfItsOwn() throws IOException {
        String json = golden().replaceFirst("\"deleted\"", "\"extra\": 1, \"deleted\"");

        assertThatThrownBy(() -> DocumentExport.fromJson(json)).isNotNull();
    }

    @Test
    void checksTheTarget() throws IOException {
        Path index = dir.resolve("index");
        Files.createDirectories(index.resolve("sub"));
        List<String> indexes = List.of(index.toString());
        Path out = dir.resolve("doc-3.json");

        assertThat(DocumentExport.checkTarget(out, false, indexes)).isEmpty();
        assertThat(DocumentExport.checkTarget(Path.of("doc-3.json"), false, indexes))
                .contains("The path must be absolute: doc-3.json");
        Path missing = dir.resolve("missing").resolve("doc.json");
        assertThat(DocumentExport.checkTarget(missing, false, indexes))
                .contains("No such directory: " + dir.resolve("missing"));
        String refused = "Not writing into the index directory " + index + ": leia never changes an index";
        for (Path inside :
                List.of(index.resolve("doc.json"), index.resolve("sub").resolve("doc.json"))) {
            assertThat(DocumentExport.checkTarget(inside, true, indexes)).contains(refused);
        }
        assertThat(DocumentExport.checkTarget(index, true, List.of())).contains("Is a directory: " + index);

        Files.writeString(out, "old");
        assertThat(DocumentExport.checkTarget(out, false, indexes)).contains("File exists: " + out);
        assertThat(DocumentExport.checkTarget(out, true, indexes)).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void catchesSymbolicLinks() throws IOException {
        Path index = Files.createDirectories(dir.resolve("index"));
        List<String> indexes = List.of(index.toString());
        Path intoIndex = Files.createSymbolicLink(dir.resolve("into"), index);
        assertThat(DocumentExport.checkTarget(intoIndex.resolve("doc.json"), false, indexes))
                .hasValueSatisfying(error -> assertThat(error).startsWith("Not writing into the index directory"));

        Path link = Files.createSymbolicLink(dir.resolve("link.json"), dir.resolve("elsewhere.json"));
        assertThat(DocumentExport.checkTarget(link, true, indexes))
                .contains("Not writing through a symbolic link: " + link);
    }

    @Test
    void writesAndOverwritesAFile() throws IOException {
        Path out = dir.resolve("doc.json");

        assertThat(DocumentExport.writeFile(out, "one", false, List.of())).isEmpty();
        assertThat(Files.readString(out)).isEqualTo("one");
        assertThat(DocumentExport.writeFile(out, "two", false, List.of())).contains("File exists: " + out);
        assertThat(DocumentExport.writeFile(out, "2", true, List.of())).isEmpty();
        assertThat(Files.readString(out)).isEqualTo("2");
    }

    @Test
    void namesTheFileAfterTheDocument() {
        assertThat(DocumentExport.defaultFile(42)).isEqualTo("doc-42.json");
    }
}
