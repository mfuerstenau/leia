package io.github.mfuerstenau.leia.tui.documents;

import static io.github.mfuerstenau.leia.tui.TestFilters.filterParams;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.FieldMatches;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.OffsetSource;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.search.ValueHighlight;
import io.github.mfuerstenau.leia.tui.TestFilters;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.analysis.AnalysisSettings;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.FilterStep;
import io.github.mfuerstenau.leia.tui.documents.Documents.Request;
import io.github.mfuerstenau.leia.tui.points.PointFormat;
import io.github.mfuerstenau.leia.tui.points.PointFormats;
import io.github.mfuerstenau.leia.tui.terms.Terms.TermHit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The tests of {@code documents.rs}, ported, and what the asynchronous calls add. */
class DocumentsTest {

    private static IndexField schema(String name, int number) {
        return schema(name, number, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, false);
    }

    private static IndexField schema(String name, int number, IndexOptions options, boolean termVectors) {
        return new IndexField(
                name,
                number,
                options,
                true,
                false,
                termVectors,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TermStats(1, 1, 1)));
    }

    private static DocumentField field(
            String name, OptionalLong norm, List<StoredValue> stored, Optional<DocValues> docValues, boolean vector) {
        return new DocumentField(name, norm, stored, docValues, vector, Optional.empty());
    }

    private static DocumentField empty(String name) {
        return field(name, OptionalLong.empty(), List.of(), Optional.empty(), false);
    }

    private static StoredValue string(String value) {
        return new StoredValue(StoredType.STRING, value, false);
    }

    private static DocValues rankValues() {
        return new DocValues(DocValuesType.SORTED_NUMERIC, List.of(-1L, 4612811918334230528L), List.of());
    }

    /** Fields {@code id} (stored), {@code body} (norm, stored, term vector), {@code rank} (doc values) and {@code extra} (nothing). */
    private static GetDocumentResult document(int docId, boolean deleted) {
        return document(
                docId,
                deleted,
                List.of(string("doc-" + docId)),
                List.of(string("red blue\nred"), string("second")),
                Optional.of(rankValues()));
    }

    private static GetDocumentResult document(
            int docId, boolean deleted, List<StoredValue> id, List<StoredValue> body, Optional<DocValues> rank) {
        return new GetDocumentResult(
                docId,
                deleted,
                List.of(
                        field("id", OptionalLong.empty(), id, Optional.empty(), false),
                        field("body", OptionalLong.of(3), body, Optional.empty(), true),
                        field("rank", OptionalLong.empty(), List.of(), rank, false),
                        empty("extra")));
    }

    private static GetDocumentResult document(int docId) {
        return document(docId, false);
    }

    private static Documents documents() {
        return new Documents(
                List.of(
                        schema("id", 0),
                        schema("body", 1, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, true),
                        schema("rank", 2),
                        schema("extra", 3)),
                5);
    }

    private static String render(Documents documents, int width, int height) {
        return TestScreens.render(frame -> documents.render(frame, frame.area()), width, height);
    }

    private static String selected(Documents documents) {
        return documents.selectedField().orElseThrow().name();
    }

    private static Optional<Request> load(int docId) {
        return Optional.of(new Request.Document(docId));
    }

    @Test
    void asksForTheFirstDocumentOnce() {
        Documents documents = documents();
        assertThat(documents.initialRequest()).isEqualTo(load(0));

        documents.loaded(document(0));
        assertThat(documents.initialRequest()).isEmpty();
        assertThat(new Documents(List.of(), 0).initialRequest()).isEmpty();
    }

    @Test
    void showsTheFieldsAndTheSelectedOne() {
        Documents documents = documents();
        documents.loaded(document(0));

        String screen = render(documents, 140, 30);
        assertThat(screen)
                .contains(
                        "#0 of 0–4",
                        "id       I--S----     -  doc-0",
                        "body     IN-SV---     3",
                        "extra    I-------",
                        "red blue\\nred (+1 more)",
                        "dv: -1, 4612811918334230528",
                        // The details of the first field.
                        "Flags       I--S----  indexed, stored",
                        "string  doc-0",
                        "Term vector -");
    }

    @Test
    void spellsOutTheFlags() {
        assertThat(Documents.flagWords("IN-SV---")).isEqualTo("indexed, norm, stored, term vector");
        assertThat(Documents.flagWords("--P--DTK")).isEqualTo("payloads, doc values, points, KNN vector");
        assertThat(Documents.flagWords("--------")).isEqualTo("none");
    }

    @Test
    void marksADeletedDocument() {
        Documents documents = documents();
        documents.loaded(document(1, true));

        assertThat(render(documents, 100, 20)).contains("#1 of 0–4   deleted");
    }

    @Test
    void stepsThroughTheDocuments() {
        Documents documents = documents();
        documents.loaded(document(0));

        assertThat(documents.handleKey(key(KeyCode.LEFT))).isEmpty();
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(1));
        documents.loaded(document(4));
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEmpty();
        assertThat(documents.handleKey(key('p'))).isEqualTo(load(3));
    }

    @Test
    void goesToADocumentId() {
        Documents documents = documents();
        documents.loaded(document(0));

        assertThat(documents.handleKey(key('i'))).isEmpty();
        assertThat(documents.prompting()).isTrue();
        assertThat(documents.handleKey(key('9'))).isEmpty();
        assertThat(documents.handleKey(key('x'))).isEmpty();
        assertThat(documents.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(render(documents, 100, 20)).contains("Enter a number from 0 to 4.");

        documents.handleKey(key(KeyCode.BACKSPACE));
        documents.handleKey(key('3'));
        assertThat(documents.handleKey(key(KeyCode.ENTER))).isEqualTo(load(3));
        assertThat(documents.prompting()).isFalse();

        documents.handleKey(key('i'));
        assertThat(documents.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(documents.prompting()).isFalse();
    }

    @Test
    void theSelectionStaysOnItsField() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key(KeyCode.DOWN));

        documents.loaded(document(1));

        assertThat(selected(documents)).isEqualTo("rank");
    }

    @Test
    void seeksAFieldWhileTyping() {
        Documents documents = documents();
        assertThat(documents.handleKey(key('/'))).isEmpty();
        assertThat(documents.prompting()).as("no fields to seek yet").isFalse();
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.TAB));

        documents.handleKey(key('/'));
        assertThat(documents.seeking()).isTrue();
        documents.handleKey(key('R'));
        assertThat(selected(documents)).isEqualTo("rank");
        assertThat(render(documents, 100, 20)).contains("Fields (4) /R▏");

        // A text that finds nothing keeps the selection.
        documents.handleKey(key('x'));
        assertThat(selected(documents)).isEqualTo("rank");
        assertThat(render(documents, 100, 20)).contains("/Rx▏ no match");

        // Without a field that starts with it, the first that contains it.
        documents.handleKey(key(KeyCode.BACKSPACE));
        documents.handleKey(key(KeyCode.BACKSPACE));
        documents.handleKey(key('t'));
        documents.handleKey(key('r'));
        assertThat(selected(documents)).isEqualTo("extra");

        // Keys are typed, not run, until Enter or Esc.
        documents.handleKey(key('e'));
        assertThat(documents.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(documents.prompting()).isFalse();
        assertThat(render(documents, 100, 20)).contains("Fields (4, / seeks)");
        assertThat(selected(documents)).isEqualTo("extra");
        // The seek moved the focus to the fields.
        documents.handleKey(key(KeyCode.UP));
        assertThat(selected(documents)).isEqualTo("rank");
    }

    @Test
    void hidesAndShowsEmptyFields() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.END));
        assertThat(selected(documents)).isEqualTo("extra");

        documents.handleKey(key('e'));

        String screen = render(documents, 100, 20);
        assertThat(screen).contains("Fields (3 of 4, e shows all, / seeks)").doesNotContain("extra");
        // The hidden field's selection falls back to the first row.
        assertThat(selected(documents)).isEqualTo("id");
    }

    /** The names of the rows from the first on, which moves the selection. */
    private static List<String> order(Documents documents) {
        documents.handleKey(key(KeyCode.HOME));
        List<String> names = new java.util.ArrayList<>();
        int rows = 0;
        String last = null;
        do {
            names.add(selected(documents));
            last = selected(documents);
            documents.handleKey(key(KeyCode.DOWN));
            rows++;
        } while (!selected(documents).equals(last) && rows < 10);
        return names;
    }

    @Test
    void sortsTheFieldsByNumberAndName() {
        Documents documents = documents();
        documents.loaded(document(0));
        assertThat(render(documents, 100, 20)).contains("# ▲");
        assertThat(documents.keys().toString()).contains("z Z sort");
        assertThat(order(documents)).containsExactly("id", "body", "rank", "extra");

        documents.handleKey(key('z'));
        assertThat(render(documents, 100, 20)).contains("Field ▲").doesNotContain("# ▲");
        assertThat(order(documents)).containsExactly("body", "extra", "id", "rank");

        documents.handleKey(key('Z'));
        assertThat(render(documents, 100, 20)).contains("Field ▼");
        assertThat(order(documents)).containsExactly("rank", "id", "extra", "body");

        // The next key starts ascending again.
        documents.handleKey(key('z'));
        assertThat(render(documents, 100, 20)).contains("# ▲");
        assertThat(order(documents)).containsExactly("id", "body", "rank", "extra");

        documents.handleKey(key('Z'));
        assertThat(render(documents, 100, 20)).contains("# ▼");
        assertThat(order(documents)).containsExactly("extra", "rank", "body", "id");
    }

    @Test
    void theSelectionStaysOnItsFieldWhenTheSortChanges() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(selected(documents)).isEqualTo("rank");

        documents.handleKey(key('z'));
        assertThat(selected(documents)).isEqualTo("rank");
        documents.handleKey(key('Z'));
        assertThat(selected(documents)).isEqualTo("rank");

        documents.handleKey(key('e'));
        assertThat(selected(documents)).isEqualTo("rank");
        assertThat(order(documents)).containsExactly("rank", "id", "body");
    }

    @Test
    void theSortSurvivesAnotherDocumentAndAReopen() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key('z'));
        documents.handleKey(key('Z'));

        documents.loaded(document(1));
        assertThat(order(documents)).containsExactly("rank", "id", "extra", "body");

        Documents reopened = documents.reopened(
                List.of(
                        schema("id", 0),
                        schema("body", 1, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, true),
                        schema("rank", 2),
                        schema("extra", 3)),
                5,
                List.of());
        reopened.loaded(document(1));
        assertThat(render(reopened, 100, 20)).contains("Field ▼");
        assertThat(order(reopened)).containsExactly("rank", "id", "extra", "body");
    }

    @Test
    void aFieldTheSchemaLacksComesLastInEitherDirection() {
        Documents documents = new Documents(List.of(schema("id", 0), schema("body", 1), schema("rank", 2)), 5);
        documents.loaded(document(0));
        assertThat(order(documents)).containsExactly("id", "body", "rank", "extra");
        assertThat(render(documents, 100, 20)).contains("extra");

        documents.handleKey(key('Z'));
        assertThat(order(documents)).containsExactly("rank", "body", "id", "extra");
    }

    @Test
    void zTypedIntoTheSeekIsSeekText() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key('/'));
        documents.handleKey(key('z'));

        assertThat(render(documents, 100, 20)).contains("/z▏").doesNotContain("Field ▲");
        documents.handleKey(key(KeyCode.ESCAPE));
        assertThat(order(documents)).containsExactly("id", "body", "rank", "extra");
    }

    @Test
    void exportsTheShownDocument() {
        Documents documents = documents();
        assertThat(documents.handleKey(key('E'))).as("no document yet").isEmpty();

        documents.want(2);
        documents.loaded(document(2));

        assertThat(documents.handleKey(key('E'))).contains(new Request.Export(2));
    }

    private static Optional<Request> search(DocValuesNumber number, boolean hex, String... values) {
        return Optional.of(new Request.SearchDocValues("rank", number, hex, List.of(values)));
    }

    @Test
    void searchesTheDocValuesOfTheSelectedField() {
        Documents documents = documents();
        documents.loaded(document(0));
        assertThat(documents.handleKey(key('s'))).as("id has no doc values").isEmpty();
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key(KeyCode.DOWN));

        assertThat(documents.handleKey(key('s')))
                .isEqualTo(search(DocValuesNumber.LONG, false, "-1", "4612811918334230528"));
        documents.handleKey(key('x'));
        assertThat(documents.handleKey(key('s'))).isEqualTo(search(DocValuesNumber.DOUBLE, false, "NaN", "2.5"));
    }

    @Test
    void searchesBytesAsHexIfOneOfThemIsBinary() {
        Documents documents = documents();
        documents.loaded(document(
                0,
                false,
                List.of(string("doc-0")),
                List.of(),
                Optional.of(new DocValues(
                        DocValuesType.SORTED_SET,
                        List.of(),
                        List.of(new BytesValue("ab", false), new BytesValue("ff00", true))))));
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key(KeyCode.DOWN));

        assertThat(documents.handleKey(key('s'))).isEqualTo(search(DocValuesNumber.LONG, true, "6162", "ff00"));
    }

    /** A document with a float vector in {@code vec}, and none in {@code nothing}. */
    private static Documents withVector() {
        Documents documents = new Documents(
                List.of(
                        schema("id", 0),
                        new IndexField(
                                "vec",
                                1,
                                IndexOptions.NONE,
                                false,
                                false,
                                false,
                                DocValuesType.NONE,
                                0,
                                0,
                                3,
                                Optional.of(VectorEncoding.FLOAT32),
                                Optional.of(VectorSimilarity.DOT_PRODUCT),
                                Optional.empty())),
                5);
        documents.loaded(new GetDocumentResult(
                4,
                false,
                List.of(
                        field("id", OptionalLong.empty(), List.of(string("doc-4")), Optional.empty(), false),
                        new DocumentField(
                                "vec",
                                OptionalLong.empty(),
                                List.of(),
                                Optional.empty(),
                                false,
                                Optional.of(
                                        new KnnVector(VectorEncoding.FLOAT32, List.of(0.6f, -0.8f, 0f), List.of()))))));
        return documents;
    }

    @Test
    void showsAVectorAndSearchesItsNearest() {
        Documents documents = withVector();
        assertThat(documents.handleKey(key('N'))).as("id has no vector").isEmpty();
        documents.handleKey(key(KeyCode.DOWN));

        String screen = render(documents, 120, 30);
        assertThat(screen)
                .contains(
                        "-------K  ",
                        "[0.6, -0.8, 0.0] 3d",
                        "vectors 3d float dot product",
                        "Vector      3d float, length 1.00000, N finds the nearest, T its graph node",
                        "0  0.6  -0.8  0.0");
        assertThat(documents.handleKey(key('N'))).hasValue(new Request.SearchKnn("vec", 4));

        assertThat(documents.handleKey(key('v'))).isEmpty();
        assertThat(render(documents, 100, 20)).contains("vec · 1 of 1 · float vector · 12 B", "│0.6", "│-0.8");
    }

    @Test
    void tShowsAVectorsNodeInTheHnswGraph() {
        Documents documents = withVector();
        documents.handleKey(key(KeyCode.DOWN));

        assertThat(documents.handleKey(key('T'))).hasValue(new Request.BrowseGraph("vec", 4));

        Documents withoutGraph =
                withVector().withFeatures(Set.of(Feature.DOCUMENTS, Feature.TERMS, Feature.KNN_VECTORS));
        withoutGraph.handleKey(key(KeyCode.DOWN));
        assertThat(withoutGraph.handleKey(key('T'))).isEmpty();
        assertThat(render(withoutGraph, 100, 20)).contains("HNSW graph not supported");
    }

    @Test
    void vectorLinesNumberTheirFirstComponent() {
        KnnVector bytes = new KnnVector(
                VectorEncoding.BYTE, List.of(), List.of((byte) 1, (byte) -2, (byte) 3, (byte) 4, (byte) 5));

        assertThat(Documents.vectorLines(bytes))
                .extracting(line ->
                        line.spans().stream().map(span -> span.content()).collect(Collectors.joining()))
                .containsExactly("  0  1  -2  3  4", "  4  5");
        assertThat(Documents.vectorLength(bytes)).isCloseTo(Math.sqrt(55), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Documents.vectorSummary(bytes)).isEqualTo("[1, -2, 3, 4, 5] 5d");
    }

    @Test
    void reopeningKeepsTheDocumentItsSelectedFieldAndHowValuesShow() {
        Documents documents = documents();
        documents.loaded(document(3));
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key('e'));
        documents.handleKey(key('x'));
        assertThat(selected(documents)).isEqualTo("rank");

        Documents reopened = documents.reopened(List.of(schema("id", 0), schema("rank", 1)), 10, List.of());

        assertThat(reopened.initialRequest()).isEqualTo(load(3));
        reopened.loaded(document(3));
        assertThat(selected(reopened)).isEqualTo("rank");
        // Empty fields stay hidden, and numbers show as doubles.
        String screen = render(reopened, 140, 30);
        assertThat(screen).contains("Fields (3 of 4, e shows all", "as double bits (x)");
        assertThat(documents.reopened(List.of(schema("id", 0)), 2, List.of()).initialRequest())
                .isEqualTo(load(1));
    }

    private static TermPosition position(Integer at, Integer start, Integer end, String payload) {
        return new TermPosition(
                at == null ? OptionalInt.empty() : OptionalInt.of(at),
                start == null ? OptionalInt.empty() : OptionalInt.of(start),
                end == null ? OptionalInt.empty() : OptionalInt.of(end),
                Optional.ofNullable(payload));
    }

    @Test
    void loadsTheTermVectorOnEnter() {
        Documents documents = documents();
        documents.loaded(document(2));
        // id has no term vector.
        assertThat(documents.handleKey(key(KeyCode.ENTER))).isEmpty();
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(render(documents, 140, 30)).contains("Term vector ⏎ loads it");

        assertThat(documents.handleKey(key(KeyCode.ENTER))).hasValue(new Request.TermVector(2, "body"));
        documents.termVectorLoaded(
                2,
                "body",
                List.of(
                        new TermVectorTerm(
                                "red", false, 2, List.of(position(0, 0, 3, "0a0b"), position(2, null, null, null))),
                        new TermVectorTerm("ff", true, 1, List.of())));

        String screen = render(documents, 140, 30);
        assertThat(screen).contains("Term vector 2 terms", "red ×2  0 [0–3] ⟨0a0b⟩, 2", "0xff ×1");
        // Loaded once.
        assertThat(documents.handleKey(key(KeyCode.ENTER))).isEmpty();
        // A late answer for another document is dropped.
        documents.loaded(document(3));
        documents.termVectorFailed(2, "body", "gone");
        assertThat(render(documents, 140, 30)).doesNotContain("gone");
    }

    @Test
    void showsMultiLineStoredValuesAndDecodedNumbers() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(render(documents, 140, 30)).contains("string  red blue", "          red ", "string  second");

        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key('x'));
        assertThat(render(documents, 140, 30)).contains("sorted numeric, as double bits (x)", "  2.5", "dv: NaN, 2.5");
    }

    @Test
    void decodesNormsToFieldLengths() {
        // From Lucene's SmallFloat.intToByte4 and byte4ToInt.
        assertThat(Documents.normLength(0, 10)).hasValue(new Documents.NormLength(0, 0));
        assertThat(Documents.normLength(23, 10)).hasValue(new Documents.NormLength(23, 23));
        assertThat(Documents.normLength(39, 10)).hasValue(new Documents.NormLength(39, 39));
        assertThat(Documents.normLength(40, 10)).hasValue(new Documents.NormLength(40, 41));
        assertThat(Documents.normLength(57, 10)).hasValue(new Documents.NormLength(96, 103));
        assertThat(Documents.normLength(88, 10)).hasValue(new Documents.NormLength(1048, 1175));
        assertThat(Documents.normLength(-89, 10)).hasValue(new Documents.NormLength(983_064, 1_048_599));
        assertThat(Documents.normLength(-1, 10)).hasValue(new Documents.NormLength(2_013_265_944, 2_013_265_944));
        assertThat(Documents.normLength(128, 10)).isEmpty();
        assertThat(Documents.normText(88, 10)).isEqualTo("88 → length 1048–1175");
        assertThat(Documents.normText(1000, 10)).isEqualTo("1000");

        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(render(documents, 140, 30)).contains("Norm        3 → length 3");
    }

    @Test
    void decodesNormsBeforeLucene7AsBoostOverTheRootOfTheLength() {
        // From Lucene's SmallFloat.floatToByte315 and byte315ToFloat: 118 is 0.375, 1/√6 to 1/√7.
        assertThat(Documents.normLength(118, 5)).hasValue(new Documents.NormLength(6, 7));
        assertThat(Documents.normLength(120, 4)).hasValue(new Documents.NormLength(3, 4));
        assertThat(Documents.normLength(124, 3)).hasValue(new Documents.NormLength(1, 1));
        assertThat(Documents.normLength(-1, 6)).hasValue(new Documents.NormLength(0, 0));
        assertThat(Documents.normLength(0, 5)).isEmpty();
        assertThat(Documents.normLength(125, 5)).isEmpty();
        assertThat(Documents.normLength(-2, 5)).isEmpty();
        assertThat(Documents.normLength(128, 5)).isEmpty();
        assertThat(Documents.normText(118, 4)).isEqualTo("118 → length 6–7 at boost 1");
        assertThat(Documents.normText(124, 2)).isEqualTo("124 → length 1 at boost 1");
        assertThat(Documents.normText(125, 4)).isEqualTo("125");

        Documents documents = documents().withIndexes(List.of(new Documents.IndexStart(0, "lucene4", 4)));
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.DOWN));
        // Byte 3 is far below 1/√Integer.MAX_VALUE: no length gives it.
        assertThat(render(documents, 140, 30)).contains("Norm        3").doesNotContain("→ length");
    }

    @Test
    void analyzesTheFirstStoredTextOfTheSelectedField() {
        Documents documents = documents();
        // A number and a hex binary are passed over.
        documents.loaded(document(
                2,
                false,
                List.of(string("doc-2")),
                List.of(
                        new StoredValue(StoredType.LONG, "7", false),
                        new StoredValue(StoredType.BINARY, "ff00", true),
                        string("red blue\nred")),
                Optional.of(rankValues())));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(documents.handleKey(key('a'))).hasValue(new Request.Analyze(2, "body", "red blue\nred"));
        // rank has no stored value; the viewer analyzes its doc values.
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(documents.handleKey(key('a'))).isEmpty();
        documents.handleKey(key('v'));
        assertThat(documents.handleKey(key('a'))).hasValue(new Request.Analyze(2, "rank", "-1"));
    }

    @Test
    void viewsTheValuesOfTheSelectedField() {
        Documents documents = documents();
        documents.loaded(document(0));
        // extra has no values.
        documents.handleKey(key(KeyCode.END));
        assertThat(documents.handleKey(key('v'))).isEmpty();
        assertThat(documents.viewer()).isEmpty();

        documents.handleKey(key(KeyCode.UP));
        assertThat(documents.handleKey(key('v'))).isEmpty();
        assertThat(documents.viewer().map(Viewer::mode)).hasValue(Viewer.Mode.TEXT);
        assertThat(documents.prompting()).isTrue();
        assertThat(render(documents, 100, 20)).contains("rank · 1 of 2 · sorted numeric doc value · 8 B", "│-1");
        // ← and → step through the values now, not the documents.
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEmpty();
        assertThat(render(documents, 100, 20)).contains("│4612811918334230528");
        assertThat(documents.handleKey(key('y'))).hasValue(new Request.Copy("4612811918334230528"));

        assertThat(documents.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(documents.viewer()).isEmpty();
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(1));
    }

    @Test
    void theNextViewerSplitsAtTheLastDelimiters() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key('v'));
        documents.handleKey(key('d'));
        documents.handleKey(key(KeyCode.BACKSPACE));
        documents.handleKey(key('-'));
        documents.handleKey(key(KeyCode.ENTER));
        documents.handleKey(key(KeyCode.ESCAPE));

        documents.handleKey(key('v'));
        documents.handleKey(key('d'));
        assertThat(render(documents, 100, 20)).contains("Items      -▏");
        documents.handleKey(key(KeyCode.ENTER));
        assertThat(render(documents, 100, 20)).contains("│2  0");

        // Also when another document closes the viewer.
        documents.handleKey(key('d'));
        documents.handleKey(key('c'));
        documents.handleKey(key(KeyCode.ENTER));
        documents.loaded(document(1));
        documents.handleKey(key('v'));
        documents.handleKey(key('d'));
        assertThat(render(documents, 100, 20)).contains("Items      -c▏");
    }

    private static GetDocumentResult withCutSecond(int docId) {
        return document(
                docId,
                false,
                List.of(string("doc-" + docId)),
                List.of(
                        string("red blue\nred"),
                        new StoredValue(
                                StoredType.STRING, "second", false, OptionalLong.of(70_000), OptionalLong.of(70_000))),
                Optional.of(rankValues()));
    }

    @Test
    void viewsAValueWithItsMatchesOnceTheDocumentIsIn() {
        Documents documents = documents();
        documents.loaded(document(0));
        List<MatchSpan> spans = List.of(new MatchSpan(0, 3));

        assertThat(documents.viewMatches(2, "body", 1, spans, 1)).isEqualTo(load(2));
        assertThat(documents.viewer()).isEmpty();
        documents.loaded(withCutSecond(2));
        // The cut value is fetched whole.
        assertThat(documents.openPendingView()).hasValue(new Request.StoredValue(2, "body", 1));
        assertThat(render(documents, 100, 20)).contains("body · 2 of 2");
        assertThat(documents.viewer().orElseThrow().keys()).contains("m/M");
        // Only once.
        assertThat(documents.openPendingView()).isEmpty();
    }

    @Test
    void marksTheMatchesOfASearchHit() {
        Documents documents = documents();
        Documents.MatchQuery query = new Documents.MatchQuery(
                new SearchQuery(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new TermQuery("body", new BytesValue("red", false))),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                new HighlightOptions(3, true, new AnalysisSettings().analyzer()));
        documents.showSearchHit(0, Optional.of(query));
        List<Long> cancel = new ArrayList<>();
        Documents.MatchesStart start = documents.matchesFor(0, cancel).orElseThrow();
        assertThat(start.params())
                .isEqualTo(new StartHighlightParams(query.query(), 0, Optional.empty(), query.options()));
        assertThat(cancel).isEmpty();
        // Asked for once.
        assertThat(documents.matchesFor(0, cancel)).isEmpty();
        documents.loaded(document(0));
        assertThat(render(documents, 140, 30)).contains("Fields (4, finding matches…, / seeks)");

        assertThat(documents.matchesStarted(start.matchesId(), 7)).isEmpty();
        assertThat(documents.matchesJob())
                .hasValueSatisfying(job -> assertThat(job).containsExactly(start.matchesId(), 7));
        documents.matchesProgress(
                start.matchesId(),
                new GetHighlightResult(Optional.of(new DocumentHighlight(List.of(
                        new FieldMatches("id", OffsetSource.ANALYSIS, List.of(), false),
                        new FieldMatches(
                                "body",
                                OffsetSource.POSTINGS,
                                List.of(new ValueHighlight(0, 2, List.of(new MatchSpan(0, 3), new MatchSpan(9, 12)))),
                                true))))));
        assertThat(documents.matchesJob()).isEmpty();
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(render(documents, 140, 30))
                .contains(
                        "Fields (4, matches in 1, / seeks)",
                        "Matches     2 in 1 value, offsets from the postings, v views",
                        "them (cut at 1.0 MiB)",
                        "string  red blue",
                        "          red");

        documents.handleKey(key('v'));
        assertThat(render(documents, 100, 20)).contains("body · 1 of 2", "match 1 of 2");
        documents.handleKey(key(KeyCode.ESCAPE));

        // Another document shows none, and they are back with the hit.
        documents.loaded(document(1));
        assertThat(render(documents, 140, 30)).doesNotContain("matches");
        documents.loaded(document(0));
        assertThat(render(documents, 140, 30)).contains("matches in 1");

        // A term hit forgets them.
        documents.showHit(new TermHit("id", new BytesValue("doc-0", false), 0, 0, 1, OptionalInt.empty()));
        assertThat(render(documents, 140, 30)).doesNotContain("matches");
    }

    @Test
    void viewsAValueOfTheShownDocumentAtOnce() {
        Documents documents = documents();
        documents.loaded(document(2));

        assertThat(documents.viewMatches(2, "body", 0, List.of(new MatchSpan(4, 8)), 1))
                .isEmpty();

        assertThat(render(documents, 100, 20)).contains("body · 1 of 2", "match 1 of 1");
    }

    @Test
    void fetchesACutValueForTheViewer() {
        Documents documents = documents();
        documents.loaded(withCutSecond(2));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(render(documents, 140, 30)).contains("cut at 64.0 KiB of 68.4 KiB, v views it whole");

        assertThat(documents.handleKey(key('v'))).isEmpty();
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).hasValue(new Request.StoredValue(2, "body", 1));
        documents.storedValueLoaded(2, "body", 1, string("second, whole"));
        assertThat(render(documents, 100, 20)).contains("│second, whole");
        // Another document closes the viewer.
        documents.loaded(document(3));
        assertThat(documents.viewer()).isEmpty();
    }

    @Test
    void endsAValueCutAfterItsFirstCharactersWithAnEllipsis() {
        Documents documents = documents();
        documents.loaded(document(0, false, List.of(string("x".repeat(1999) + "yz")), List.of(), Optional.empty()));
        documents.handleKey(key(KeyCode.TAB));
        documents.handleKey(key(KeyCode.END));

        assertThat(render(documents, 140, 40))
                .contains("xxy…")
                .doesNotContain("yz")
                .contains("          … v views it whole");
    }

    @Test
    void showsTheBeginningOfALongValue() {
        Documents documents = documents();
        String lines = IntStream.rangeClosed(1, 30).mapToObj(n -> "line " + n).collect(Collectors.joining("\n"));
        documents.loaded(document(0, false, List.of(string(lines)), List.of(), Optional.empty()));
        documents.handleKey(key(KeyCode.TAB));
        documents.handleKey(key(KeyCode.PAGE_DOWN));

        String screen = render(documents, 140, 40);
        assertThat(screen)
                .contains("          line 20", "          … v views it whole")
                .doesNotContain("line 21");
    }

    @Test
    void showsBinaryValuesAsHex() {
        Documents documents = documents();
        documents.loaded(document(
                0,
                false,
                List.of(new StoredValue(StoredType.BINARY, "ff00", true)),
                List.of(),
                Optional.of(
                        new DocValues(DocValuesType.SORTED_SET, List.of(), List.of(new BytesValue("a\tb", false))))));

        assertThat(render(documents, 140, 30)).contains("binary  0xff00", "dv: a\\tb");
    }

    @Test
    void showsAFailedDocument() {
        Documents documents = documents();
        documents.failed(3, "No document 3 (DOC_NOT_FOUND -32008)");

        assertThat(render(documents, 100, 20)).contains("#3 of 0–4   Cannot read it: No document 3");
        assertThat(documents.initialRequest()).isEmpty();
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(4));
    }

    @Test
    void scrollsTheDetails() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.TAB));
        documents.handleKey(key(KeyCode.END));
        String screen = render(documents, 140, 12);
        // Scrolled to the last line, at the bottom of the panel.
        assertThat(screen)
                .doesNotContain("Indexed")
                .contains("Doc values  -", "Term vector -", "Rebuilt     r rebuilds");

        documents.handleKey(key(KeyCode.HOME));
        assertThat(render(documents, 140, 12)).contains("Indexed");
    }

    /** A runtime that reads vectors finds the nearest with its classic search (ADR 0049). */
    @Test
    void findsTheNearestWithAClassicOnlySearch() {
        Documents documents = withVector()
                .withFeatures(
                        Set.of(Feature.DOCUMENTS, Feature.STORED_VALUES, Feature.CLASSIC_SEARCH, Feature.KNN_VECTORS));
        documents.handleKey(key(KeyCode.DOWN));

        assertThat(documents.keys()).contains("N nearest").doesNotContain("m similar", "s search value");
        assertThat(documents.handleKey(key('N'))).hasValue(new Request.SearchKnn("vec", 4));
    }

    /** A runtime with doc values and More-Like-This queries searches a value and similar documents (ADR 0066). */
    @Test
    void searchesValuesAndSimilarDocumentsWithTheirParts() {
        Set<Feature> classic =
                Set.of(Feature.DOCUMENTS, Feature.STORED_VALUES, Feature.CLASSIC_SEARCH, Feature.DOC_VALUES);
        Documents without = documents().withFeatures(classic);
        without.loaded(document(0));
        assertThat(without.keys()).doesNotContain("m similar", "s search value");
        assertThat(without.handleKey(key('m'))).hasValue(new Request.Unsupported("Similar"));

        Set<Feature> parts = new HashSet<>(classic);
        parts.addAll(List.of(Feature.DOC_VALUES_SEARCH, Feature.MORE_LIKE_THIS));
        Documents documents = documents().withFeatures(parts);
        documents.loaded(document(0));
        assertThat(documents.keys()).contains("m similar", "s search value");
        assertThat(documents.handleKey(key('m'))).hasValue(new Request.MoreLikeThis(0));
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(documents.handleKey(key('s')))
                .isEqualTo(search(DocValuesNumber.LONG, false, "-1", "4612811918334230528"));

        // Doc values queries need doc values.
        parts.remove(Feature.DOC_VALUES);
        assertThat(documents().withFeatures(parts).keys()).doesNotContain("s search value");
    }

    @Test
    void refusesAndLeavesOutTheKeysTheIndexLacks() {
        Documents documents =
                documents().withFeatures(Set.of(Feature.DOCUMENTS, Feature.STORED_VALUES, Feature.TERM_VECTORS));
        documents.loaded(document(0));

        assertThat(documents.keys())
                .contains("⏎ term vector", "v view", "E export")
                .doesNotContain("r rebuild", "m similar", "N nearest", "s search value", "t points");
        assertThat(documents.handleKey(key('r'))).hasValue(new Request.Unsupported("Rebuild"));
        assertThat(documents.handleKey(key('m'))).hasValue(new Request.Unsupported("Similar"));
        assertThat(documents.handleKey(key('s'))).hasValue(new Request.Unsupported("Search value"));
        assertThat(render(documents, 140, 30)).doesNotContain("r rebuilds");

        // Without positions, a hit's are not fetched.
        documents.showHit(hit("body", 2, 1));
        documents.loaded(document(2));
        assertThat(documents.positionsRequest()).isEmpty();

        assertThat(documents.reopened(List.of(schema("id", 0)), 5, List.of()).keys())
                .doesNotContain("r rebuild");
    }

    private static TermHit hit(String field, int docId, int index) {
        return new TermHit(field, new BytesValue("red", false), docId, index, 3, OptionalInt.of(2));
    }

    @Test
    void showsWhereTheTermOfAHitOccurs() {
        Documents documents = documents();
        documents.loaded(document(0));
        assertThat(documents.handleKey(key(']'))).isEmpty();

        documents.showHit(hit("body", 2, 1));
        assertThat(documents.positionsRequest()).isEmpty();
        documents.loaded(document(2));

        assertThat(selected(documents)).isEqualTo("body");
        BytesValue red = new BytesValue("red", false);
        assertThat(documents.positionsRequest()).hasValue(new Request.Positions(2, "body", red));
        documents.positionsLoaded(2, "body", red, List.of(position(0, 0, 3, null), position(2, null, null, null)));
        assertThat(documents.positionsRequest()).isEmpty();

        String screen = render(documents, 140, 30);
        assertThat(screen)
                .contains(
                        "#2 of 0–4   body:red  2 of 3  [ ] steps",
                        "Term        red ×2, doc 2 of 3 ([ ])",
                        "  0 [0–3], 2");
        assertThat(documents.handleKey(key('['))).hasValue(new Request.Step(Documents.Walk.TERM_DOCS, false));
        assertThat(documents.handleKey(key(']'))).hasValue(new Request.Step(Documents.Walk.TERM_DOCS, true));

        // Another document has no positions of the term.
        documents.loaded(document(3));
        assertThat(documents.positionsRequest()).isEmpty();
        assertThat(render(documents, 140, 30)).doesNotContain("Term        red");
    }

    @Test
    void keepsTheFieldOfAHitAmongTheEmptyOnes() {
        Documents documents = documents();
        documents.handleKey(key('e'));
        documents.showHit(hit("extra", 1, 0));
        documents.loaded(document(1));

        assertThat(selected(documents)).isEqualTo("extra");
        documents.positionsFailed(1, "extra", new BytesValue("red", false), "broken");
        assertThat(render(documents, 140, 30)).contains("Fields (4, / seeks)", "cannot read the positions: broken");
    }

    @Test
    void aReplyForADocumentNotWantedIsDropped() {
        Documents documents = documents();
        documents.want(1);
        documents.want(3);
        documents.showHit(hit("body", 3, 0));

        // The earlier reply leaves the hit's field to the document it belongs to.
        assertThat(documents.loaded(document(1))).isFalse();
        documents.failed(1, "late");
        assertThat(render(documents, 100, 20)).doesNotContain("late");
        assertThat(documents.loaded(document(3))).isTrue();
        assertThat(selected(documents)).isEqualTo("body");
        assertThat(documents.positionsRequest()).isPresent();
    }

    @Test
    void theInitialDocumentIsAskedForOnlyOnce() {
        Documents documents = documents();
        documents.want(0);
        assertThat(documents.initialRequest()).isEmpty();
    }

    private static Optional<Request> step(long filterId, int fromDoc, boolean forward, FilterStep step) {
        return Optional.of(new Request.StepFilter(1, new StepFilterParams(filterId, fromDoc, forward), step));
    }

    private static GetFilterResult built(long filterId, int matchCount) {
        return TestFilters.built(filterId, matchCount, 5);
    }

    @Test
    void stepsThroughTheMatchesOfAFilter() {
        Documents documents = documents();
        documents.loaded(document(0));
        assertThat(documents.startFilter("red", filterParams(false), 2))
                .isEqualTo(new Request.StartFilter(1, filterParams(false), OptionalLong.empty(), OptionalLong.empty()));
        assertThat(documents.filterStarted(1, 9)).isTrue();
        assertThat(documents.filterJob()).hasValue(9);

        // While the filter is built, ←/→ wait for it.
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEmpty();
        assertThat(render(documents, 100, 20)).contains("filter red: finding matches… 0%");

        assertThat(documents.filterProgress(1, built(9, 2))).isEqualTo(step(9, 2, true, FilterStep.NEAREST));
        assertThat(documents.filterJob()).isEmpty();
        StepFilterParams params = new StepFilterParams(9, 2, true);
        assertThat(documents.filterStepped(1, params, FilterStep.NEAREST, Optional.of(new FilterMatch(3, 1))))
                .isEqualTo(load(3));
        documents.loaded(document(3));
        assertThat(documents.filterToLocate(3)).isEmpty();
        assertThat(render(documents, 100, 20)).contains("filter red: 2 of 2");

        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(step(9, 4, true, FilterStep.MOVE));
        // Queued behind the step under way.
        assertThat(documents.handleKey(key('p'))).isEmpty();
        documents.handleKey(key('i'));
        documents.handleKey(key('1'));
        assertThat(documents.handleKey(key(KeyCode.ENTER))).isEqualTo(step(9, 1, true, FilterStep.NEAREST));

        // A document shown some other way is located.
        assertThat(documents.filterToLocate(4)).contains(new Documents.Locate(1, 9));

        assertThat(documents.handleKey(key('b')))
                .contains(new Request.ClearFilter(OptionalLong.empty(), OptionalLong.of(9)));
        assertThat(documents.filtered()).isFalse();
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(4));
    }

    @Test
    void stepsPressedWhileAStepIsUnderWayGoOnFromWhereItLands() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.startFilter("red", filterParams(false), 0);
        documents.filterStarted(1, 9);
        documents.filterProgress(1, built(9, 5));
        documents.filterStepped(
                1, new StepFilterParams(9, 0, true), FilterStep.NEAREST, Optional.of(new FilterMatch(1, 1)));
        documents.loaded(document(1));

        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(step(9, 2, true, FilterStep.MOVE));
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEmpty();
        assertThat(documents.handleKey(key(KeyCode.LEFT))).isEmpty();
        assertThat(documents.filterStepped(
                        1, new StepFilterParams(9, 2, true), FilterStep.MOVE, Optional.of(new FilterMatch(3, 2))))
                .isEqualTo(load(3));
        assertThat(documents.queuedStep()).isEmpty();
        documents.loaded(document(3));
        assertThat(documents.queuedStep()).isEqualTo(step(9, 4, true, FilterStep.MOVE));
        // Nothing further on: the next queued step goes on from the same document.
        assertThat(documents.filterStepped(1, new StepFilterParams(9, 4, true), FilterStep.MOVE, Optional.empty()))
                .isEqualTo(step(9, 2, false, FilterStep.MOVE));
        documents.filterStepped(
                1, new StepFilterParams(9, 2, false), FilterStep.MOVE, Optional.of(new FilterMatch(1, 1)));
        documents.loaded(document(1));
        assertThat(documents.queuedStep()).isEmpty();
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(step(9, 2, true, FilterStep.MOVE));
    }

    @Test
    void docsPressedBeforeTheLastOneIsInGoOnFromIt() {
        Documents documents = documents();
        documents.loaded(document(1));
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(2));
        documents.want(2);
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(3));
        documents.want(3);
        assertThat(documents.handleKey(key(KeyCode.LEFT))).isEqualTo(load(2));
    }

    @Test
    void aNewFilterReplacesTheOldOneAndAFailedOneStepsThroughEveryDocument() {
        Documents documents = documents();
        documents.loaded(document(1));
        documents.startFilter("red", filterParams(false), 0);
        documents.filterStarted(1, 9);
        assertThat(documents.startFilter("blue", filterParams(true), 0))
                .isEqualTo(new Request.StartFilter(2, filterParams(true), OptionalLong.of(9), OptionalLong.empty()));
        // The job of the replaced filter is not wanted any more, nor is what it reports.
        assertThat(documents.filterStarted(1, 11)).isFalse();
        documents.filterFailed(1, "too late");
        assertThat(documents.filterStarted(2, 10)).isTrue();

        documents.filterFailed(2, "Invalid query: oops");
        assertThat(render(documents, 100, 20)).contains("filter blue, deleted too: Invalid query: oops");
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(2));
        // What the replaced filter found is ignored.
        assertThat(documents.filterStepped(1, new StepFilterParams(9, 0, true), FilterStep.MOVE, Optional.empty()))
                .isEmpty();
        assertThat(documents.handleKey(key('b')))
                .contains(new Request.ClearFilter(OptionalLong.empty(), OptionalLong.empty()));
    }

    @Test
    void aReopenBuildsTheFilterAgainUnderANewSerial() {
        Documents documents = documents();
        documents.loaded(document(1));
        documents.startFilter("red", filterParams(false), 0);
        documents.filterStarted(1, 9);
        documents.filterProgress(1, built(9, 2));
        assertThat(documents.filterToRebuild()).isEmpty();

        Documents reopened = documents.reopened(List.of(schema("id", 0)), 5, List.of());

        assertThat(reopened.filterToRebuild())
                .contains(new Request.StartFilter(2, filterParams(false), OptionalLong.empty(), OptionalLong.empty()));
        // The old index's job is gone.
        assertThat(reopened.filterProgress(1, built(9, 2))).isEmpty();
        assertThat(reopened.filterStarted(2, 12)).isTrue();
        assertThat(reopened.filterToRebuild()).isEmpty();
        reopened.loaded(document(1));
        // Once built, it only locates the document shown.
        assertThat(reopened.filterProgress(2, built(12, 2)))
                .contains(new Request.StepFilter(2, new StepFilterParams(12, 1, true), FilterStep.LOCATE));
        assertThat(reopened.filterStepped(
                        2, new StepFilterParams(12, 1, true), FilterStep.LOCATE, Optional.of(new FilterMatch(1, 0))))
                .isEmpty();
        assertThat(render(reopened, 100, 20)).contains("filter red: 1 of 2");
    }

    /** Two index directories: {@code a} with the segments _0 (0–1) and _1 (2), {@code b} with _0 (3–4). */
    private static Documents segmented() {
        return documents()
                .withIndexes(List.of(new Documents.IndexStart(0, "a", 10), new Documents.IndexStart(3, "b", 10)))
                .withSegments(List.of(
                        new Documents.SegmentStart(0, "_0", 0, 2, 2),
                        new Documents.SegmentStart(0, "_1", 2, 1, 1),
                        new Documents.SegmentStart(1, "_0", 3, 2, 1)));
    }

    @Test
    void showsTheSegmentOfADocument() {
        Documents documents = segmented();
        assertThat(documents.segmentOf(2)).hasValue(new Documents.SegmentStart(0, "_1", 2, 1, 1));
        assertThat(documents.segmentOf(4).map(Documents.SegmentStart::index)).hasValue(1);
        assertThat(documents.segmentOf(5)).isEmpty();
        assertThat(documents.segment(1, "_0").map(Documents.SegmentStart::docBase))
                .hasValue(3);
        assertThat(documents.segment(1, "_1")).isEmpty();

        documents.loaded(document(4));
        assertThat(render(documents, 100, 20)).contains("#4 of 0–4   in b   _0 #1 of 0–1");
        assertThat(documents.keys()).contains("S < > segment");
        // Without segments, as before.
        Documents plain = documents();
        plain.loaded(document(4));
        assertThat(render(plain, 100, 20)).contains("#4 of 0–4  ").doesNotContain("_0");
        assertThat(plain.keys()).doesNotContain("segment");
        assertThat(plain.handleKey(key('>'))).isEmpty();
    }

    @Test
    void cShowsTheSegmentOfTheDocumentOnTheCommitsScreen() {
        Documents documents = segmented();
        documents.loaded(document(1));
        assertThat(documents.keys()).contains("C commit");
        assertThat(documents.handleKey(key('C'))).hasValue(new Request.ShowCommit(0, "_0"));
        // In the second index directory, its own _0.
        documents.loaded(document(4));
        assertThat(documents.handleKey(key('C'))).hasValue(new Request.ShowCommit(1, "_0"));

        // Without segments, there is nothing to show.
        Documents plain = documents();
        plain.loaded(document(1));
        assertThat(plain.keys()).doesNotContain("C commit");
        assertThat(plain.handleKey(key('C'))).isEmpty();

        Documents withoutCommits = segmented().withFeatures(Set.of(Feature.DOCUMENTS, Feature.TERMS));
        withoutCommits.loaded(document(1));
        assertThat(withoutCommits.handleKey(key('C'))).hasValue(new Request.Unsupported("Commits"));
    }

    @Test
    void tShowsTheSelectedFieldOnTheTermsScreen() {
        Documents documents = new Documents(
                List.of(
                        schema("id", 0),
                        schema("body", 1, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, true),
                        new IndexField(
                                "rank",
                                2,
                                IndexOptions.NONE,
                                false,
                                false,
                                false,
                                DocValuesType.SORTED_NUMERIC,
                                1,
                                8,
                                0,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()),
                        schema("extra", 3, IndexOptions.NONE, false)),
                5);
        documents.loaded(document(0));
        assertThat(documents.keys()).contains("T terms");
        assertThat(documents.handleKey(key('T'))).hasValue(new Request.BrowseTerms("id", Optional.empty(), false));

        // From a hit of the terms screen, its field opens at its term; another field doesn't.
        documents.showHit(hit("body", 2, 1));
        documents.loaded(document(2));
        assertThat(documents.handleKey(key('T')))
                .hasValue(new Request.BrowseTerms("body", Optional.of(new BytesValue("red", false)), false));
        documents.handleKey(key(KeyCode.UP));
        assertThat(documents.handleKey(key('T'))).hasValue(new Request.BrowseTerms("id", Optional.empty(), false));

        // A field with points and no terms opens its points; one with neither says so.
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(documents.handleKey(key('T'))).hasValue(new Request.BrowseTerms("rank", Optional.empty(), true));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(documents.handleKey(key('T'))).isEmpty();
        assertThat(render(documents, 100, 20)).contains("extra has no terms");
        documents.handleKey(key(KeyCode.UP));
        assertThat(render(documents, 100, 20)).doesNotContain("has no terms");

        Documents withoutTerms = documents().withFeatures(Set.of(Feature.DOCUMENTS));
        withoutTerms.loaded(document(0));
        assertThat(withoutTerms.keys()).doesNotContain("T terms");
        assertThat(withoutTerms.handleKey(key('T'))).hasValue(new Request.Unsupported("Terms"));
    }

    @Test
    void aPinnedSegmentBoundsTheSteps() {
        Documents documents = segmented();
        documents.loaded(document(0));
        documents.handleKey(key('S'));
        assertThat(render(documents, 100, 20)).contains("Document segment _0 ");

        assertThat(documents.handleKey(key(KeyCode.LEFT))).isEmpty();
        assertThat(render(documents, 100, 20)).contains("segment _0 · first document of _0");
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(1));
        documents.loaded(document(1));
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEmpty();
        assertThat(render(documents, 100, 20)).contains("last document of _0");

        // S again unpins it.
        documents.handleKey(key('S'));
        assertThat(render(documents, 100, 20)).doesNotContain("segment _0");
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(2));
    }

    @Test
    void anglesJumpBetweenSegmentsAcrossIndexes() {
        Documents documents = segmented();
        documents.loaded(document(1));
        assertThat(documents.handleKey(key('>'))).isEqualTo(load(2));
        documents.loaded(document(2));
        assertThat(documents.handleKey(key('>'))).isEqualTo(load(3));
        documents.loaded(document(3));
        assertThat(documents.handleKey(key('>'))).isEmpty();
        assertThat(render(documents, 100, 20)).contains("no next segment");
        assertThat(documents.handleKey(key('<'))).isEqualTo(load(2));

        // The pin moves along.
        documents.handleKey(key('S'));
        assertThat(documents.handleKey(key('<'))).isEqualTo(load(2));
        documents.loaded(document(2));
        assertThat(render(documents, 100, 20)).contains("segment _1 ");
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEmpty();
    }

    @Test
    void aFilterStaysInThePinnedSegment() {
        Documents documents = segmented();
        documents.loaded(document(0));
        documents.startFilter("red", filterParams(false), 0);
        documents.filterStarted(1, 9);
        documents.filterProgress(1, built(9, 3));
        documents.filterStepped(
                1, new StepFilterParams(9, 0, true), FilterStep.NEAREST, Optional.of(new FilterMatch(0, 0)));
        documents.loaded(document(0));
        documents.handleKey(key('S'));

        // The next match is in another segment: none in this one.
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEqualTo(step(9, 1, true, FilterStep.MOVE));
        assertThat(documents.filterStepped(
                        1, new StepFilterParams(9, 1, true), FilterStep.MOVE, Optional.of(new FilterMatch(3, 1))))
                .isEmpty();
        assertThat(render(documents, 100, 20)).contains("filter red: 1 of 3 · no next match in _0");

        // > goes to the next segment's first match; one without any shows its first document.
        assertThat(documents.handleKey(key('>'))).isEqualTo(step(9, 2, true, FilterStep.MOVE));
        assertThat(documents.filterStepped(
                        1, new StepFilterParams(9, 2, true), FilterStep.MOVE, Optional.of(new FilterMatch(3, 1))))
                .isEqualTo(load(2));
        documents.loaded(document(2));
        assertThat(render(documents, 100, 20)).contains("segment _1 ");
        assertThat(documents.handleKey(key('>'))).isEqualTo(step(9, 3, true, FilterStep.MOVE));
        assertThat(documents.filterStepped(
                        1, new StepFilterParams(9, 3, true), FilterStep.MOVE, Optional.of(new FilterMatch(4, 2))))
                .isEqualTo(load(4));
    }

    @Test
    void goingOutsideThePinnedSegmentUnpinsIt() {
        Documents documents = segmented();
        documents.loaded(document(0));
        documents.handleKey(key('S'));
        documents.handleKey(key('i'));
        documents.handleKey(key('4'));
        assertThat(documents.handleKey(key(KeyCode.ENTER))).isEqualTo(load(4));
        documents.loaded(document(4));
        assertThat(render(documents, 100, 20)).contains("Document left segment _0 ");
        assertThat(documents.handleKey(key(KeyCode.RIGHT))).isEmpty();
        assertThat(render(documents, 100, 20)).doesNotContain("segment _0");

        // A hit of the terms screen, here in the pinned segment, keeps it.
        documents.handleKey(key('S'));
        documents.showHit(hit("body", 3, 0));
        documents.loaded(document(3));
        assertThat(render(documents, 100, 20)).contains("segment _0 ");
        documents.showHit(hit("body", 1, 0));
        documents.loaded(document(1));
        assertThat(render(documents, 100, 20)).contains("left segment _0");

        // The commits screen pins the segment it opens.
        Documents.SegmentStart segment = documents.segment(1, "_0").orElseThrow();
        assertThat(documents.showSegment(segment)).isEqualTo(new Request.Document(3));
        assertThat(documents.walk()).isEmpty();
        documents.loaded(document(3));
        assertThat(documents.handleKey(key(KeyCode.LEFT))).isEmpty();
    }

    @Test
    void notesEndWhenAnotherScreenShowsADocument() {
        Documents documents = segmented();
        documents.loaded(document(0));

        // left segment _0, then each way another screen shows a document, or b starts a filter.
        List<Runnable> shows = List.of(
                () -> documents.showSearchHit(1, Optional.empty()),
                () -> documents.showPoint("id"),
                () -> documents.showHit(hit("body", 1, 0)),
                () -> documents.startFilter("red", filterParams(false), 1));
        for (Runnable show : shows) {
            documents.handleKey(key('S'));
            documents.handleKey(key('i'));
            documents.handleKey(key('4'));
            documents.handleKey(key(KeyCode.ENTER));
            documents.loaded(document(4));
            assertThat(render(documents, 100, 20)).contains("left segment _0");
            show.run();
            assertThat(render(documents, 100, 20)).doesNotContain("left segment");
            documents.clearFilter();
            documents.loaded(document(0));
        }

        // The filter's note ends with the commits screen's segment, and with a search hit.
        documents.startFilter("red", filterParams(false), 0);
        documents.filterStarted(2, 9);
        documents.filterProgress(2, built(9, 3));
        documents.filterStepped(
                2, new StepFilterParams(9, 0, true), FilterStep.NEAREST, Optional.of(new FilterMatch(0, 0)));
        documents.loaded(document(0));
        documents.handleKey(key('S'));
        documents.handleKey(key(KeyCode.RIGHT));
        documents.filterStepped(
                2, new StepFilterParams(9, 1, true), FilterStep.MOVE, Optional.of(new FilterMatch(3, 1)));
        assertThat(render(documents, 100, 20)).contains("no next match in _0");
        documents.showSegment(documents.segment(1, "_0").orElseThrow());
        documents.loaded(document(3));
        assertThat(render(documents, 100, 20)).contains("segment _0 ").doesNotContain("no next match");

        documents.handleKey(key(KeyCode.RIGHT));
        documents.filterStepped(
                2, new StepFilterParams(9, 4, true), FilterStep.MOVE, Optional.of(new FilterMatch(5, 2)));
        assertThat(render(documents, 100, 20)).contains("no next match in _0");
        documents.showSearchHit(3, Optional.empty());
        assertThat(render(documents, 100, 20)).doesNotContain("no next match");
    }

    @Test
    void aReopenKeepsThePinIfTheSegmentIsStillThere() {
        Documents documents = segmented();
        documents.loaded(document(2));
        documents.handleKey(key('S'));

        // _0 of a is merged away: _1 stays, now from document 1.
        Documents kept = documents.reopened(
                List.of(schema("id", 0)),
                4,
                List.of(
                        new Documents.SegmentStart(0, "_1", 0, 1, 1),
                        new Documents.SegmentStart(0, "_2", 1, 1, 1),
                        new Documents.SegmentStart(1, "_0", 2, 2, 2)));
        assertThat(kept.initialRequest()).isEqualTo(load(0));
        kept.loaded(document(0));
        assertThat(render(kept, 100, 20)).contains("segment _1 ", "_1 #0 of 0–0");

        Documents dropped =
                documents.reopened(List.of(schema("id", 0)), 2, List.of(new Documents.SegmentStart(0, "_3", 0, 2, 2)));
        assertThat(render(dropped, 100, 20)).contains("segment _1 is gone");
        assertThat(dropped.handleKey(key(KeyCode.LEFT))).isEqualTo(load(0));
    }

    private static GetReconstructResult rebuilt(String field, Map<String, int[]> terms) {
        List<TermVectorTerm> rebuilt = terms.entrySet().stream()
                .map(term -> new TermVectorTerm(
                        term.getKey(),
                        false,
                        term.getValue().length,
                        IntStream.of(term.getValue())
                                .mapToObj(at -> position(at, null, null, null))
                                .toList()))
                .toList();
        return new GetReconstructResult(
                10,
                OptionalLong.of(10),
                Optional.of(new ReconstructResult(
                        List.of(new ReconstructedField(
                                field,
                                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                                ReconstructSource.POSTINGS,
                                rebuilt,
                                List.of())),
                        1)));
    }

    @Test
    void rebuildsOneIndexedFieldAtATime() {
        Documents documents = new Documents(
                List.of(
                        schema("id", 0),
                        schema("body", 1),
                        schema("rank", 2, IndexOptions.NONE, false),
                        schema("extra", 3)),
                5);
        documents.loaded(document(0));

        assertThat(documents.handleKey(key('r'))).contains(new Request.Reconstruct(0, "id", List.of()));
        // Progress of no running job changes nothing.
        documents.reconstructProgress(3, rebuilt("id", Map.of()));
        assertThat(documents.reconstructJob()).isEmpty();
        assertThat(documents.reconstructStarted(0, "id", 3)).isTrue();
        assertThat(documents.reconstructJob()).hasValue(3);

        // Another field stops the running rebuild.
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(documents.handleKey(key('r'))).contains(new Request.Reconstruct(0, "body", List.of(3L)));
        documents.reconstructStarted(0, "body", 4);
        // Progress of the stopped job is dropped.
        documents.reconstructProgress(3, rebuilt("id", Map.of()));
        documents.reconstructProgress(4, rebuilt("body", new TreeMap<>(Map.of("blue", new int[] {1}, "red", new int[] {
            0, 3
        }))));
        assertThat(documents.reconstructJob()).isEmpty();
        String screen = render(documents, 140, 50);
        assertThat(screen).contains("Rebuilt     2 terms, from the postings", "  red blue · red", "  red ×2  0, 3");
        assertThat(documents.handleKey(key('r'))).isEmpty();

        // A field that isn't indexed has nothing to rebuild.
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(documents.handleKey(key('r'))).isEmpty();
        assertThat(render(documents, 140, 50)).doesNotContain("Rebuilt");
    }

    @Test
    void rebuildsThePointsOfAPointFieldAndCyclesTheirFormat() {
        IndexField price = new IndexField(
                "price",
                1,
                IndexOptions.NONE,
                false,
                false,
                false,
                DocValuesType.NONE,
                1,
                8,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        PointFormats formats = new PointFormats();
        Documents documents = new Documents(List.of(schema("id", 0), price), 5).withPointFormats(formats);
        documents.loaded(new GetDocumentResult(
                0,
                false,
                List.of(
                        field("id", OptionalLong.empty(), List.of(string("doc-0")), Optional.empty(), false),
                        field("price", OptionalLong.empty(), List.of(string("12.5")), Optional.empty(), false))));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(render(documents, 140, 50)).contains("Rebuilt     r rebuilds the points it was indexed with");

        assertThat(documents.handleKey(key('r'))).contains(new Request.Reconstruct(0, "price", List.of()));
        documents.reconstructStarted(0, "price", 3);
        documents.reconstructProgress(3, new GetReconstructResult(1, OptionalLong.of(5), Optional.empty()));
        assertThat(render(documents, 140, 50)).contains("rebuilding… 1 of 5 points, Esc stops");
        documents.reconstructProgress(
                3,
                new GetReconstructResult(
                        5,
                        OptionalLong.of(5),
                        Optional.of(new ReconstructResult(
                                List.of(new ReconstructedField(
                                        "price",
                                        IndexOptions.NONE,
                                        ReconstructSource.POSTINGS,
                                        List.of(),
                                        List.of(new PointValue(List.of("c029000000000000"))))),
                                1))));

        // Guessed as a long; t shows it as the double it is, for the terms screen too.
        assertThat(render(documents, 140, 50)).contains("Rebuilt     1 point, as long (t)", "  4623226492472524800");
        documents.handleKey(key('t'));
        assertThat(render(documents, 140, 50)).contains("Rebuilt     1 point, as double (t)", "  12.5");
        assertThat(formats.of(price)).isEqualTo(PointFormat.DOUBLE);
    }

    @Test
    void aRebuildStoppedBeforeItsJobStartedIsNotWanted() {
        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key('r'));
        assertThat(documents.rebuilding()).isTrue();

        // Its job is not known yet: the start that comes later is to be cancelled.
        assertThat(documents.stopRebuild()).isEmpty();
        assertThat(documents.rebuilding()).isFalse();
        assertThat(documents.reconstructStarted(0, "id", 5)).isFalse();
        documents.reconstructStartFailed(0, "id", "late");
        assertThat(render(documents, 140, 50)).contains("Rebuilt     r rebuilds");

        // Nor is one for a document no longer shown.
        documents.handleKey(key('r'));
        documents.loaded(document(1));
        assertThat(documents.reconstructStarted(0, "id", 6)).isFalse();

        // A failed rebuild can start again.
        documents.handleKey(key('r'));
        documents.reconstructStarted(1, "id", 7);
        documents.reconstructFailed(7, "Job cancelled");
        assertThat(render(documents, 140, 50)).contains("cannot rebuild it: Job cancelled");
        assertThat(documents.handleKey(key('r'))).contains(new Request.Reconstruct(1, "id", List.of()));
    }
}
