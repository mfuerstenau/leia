package io.github.mfuerstenau.leia.tui.documents;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.documents.Documents.Request;
import io.github.mfuerstenau.leia.tui.terms.Terms.TermHit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
                Optional.of(new TermStats(1, 1, 1)));
    }

    private static DocumentField field(
            String name, OptionalLong norm, List<StoredValue> stored, Optional<DocValues> docValues, boolean vector) {
        return new DocumentField(name, norm, stored, docValues, vector);
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
                        "id     I--S----     -  doc-0",
                        "body   IN-SV---     3",
                        "extra  I-------",
                        "red blue\\nred (+1 more)",
                        "dv: -1, 4612811918334230528",
                        // The details of the first field.
                        "string  doc-0",
                        "Term vector -");
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

    @Test
    void reopeningKeepsTheDocumentItsSelectedFieldAndHowValuesShow() {
        Documents documents = documents();
        documents.loaded(document(3));
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key(KeyCode.DOWN));
        documents.handleKey(key('e'));
        documents.handleKey(key('x'));
        assertThat(selected(documents)).isEqualTo("rank");

        Documents reopened = documents.reopened(List.of(schema("id", 0), schema("rank", 1)), 10);

        assertThat(reopened.initialRequest()).isEqualTo(load(3));
        reopened.loaded(document(3));
        assertThat(selected(reopened)).isEqualTo("rank");
        // Empty fields stay hidden, and numbers show as doubles.
        String screen = render(reopened, 140, 30);
        assertThat(screen).contains("Fields (3 of 4, e shows all", "as double bits (x)");
        assertThat(documents.reopened(List.of(schema("id", 0)), 2).initialRequest())
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
        assertThat(Documents.normLength(0)).hasValue(new Documents.NormLength(0, 0));
        assertThat(Documents.normLength(23)).hasValue(new Documents.NormLength(23, 23));
        assertThat(Documents.normLength(39)).hasValue(new Documents.NormLength(39, 39));
        assertThat(Documents.normLength(40)).hasValue(new Documents.NormLength(40, 41));
        assertThat(Documents.normLength(57)).hasValue(new Documents.NormLength(96, 103));
        assertThat(Documents.normLength(88)).hasValue(new Documents.NormLength(1048, 1175));
        assertThat(Documents.normLength(-89)).hasValue(new Documents.NormLength(983_064, 1_048_599));
        assertThat(Documents.normLength(-1)).hasValue(new Documents.NormLength(2_013_265_944, 2_013_265_944));
        assertThat(Documents.normLength(128)).isEmpty();
        assertThat(Documents.normText(88)).isEqualTo("88 → length 1048–1175");
        assertThat(Documents.normText(1000)).isEqualTo("1000");

        Documents documents = documents();
        documents.loaded(document(0));
        documents.handleKey(key(KeyCode.DOWN));
        assertThat(render(documents, 140, 30)).contains("Norm        3 → length 3");
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
                        new StoredValue(StoredType.STRING, "second", false, OptionalLong.of(70_000))),
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
        assertThat(screen).doesNotContain("Indexed").contains("Doc values  -", "Term vector -");

        documents.handleKey(key(KeyCode.HOME));
        assertThat(render(documents, 140, 12)).contains("Indexed");
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
}
