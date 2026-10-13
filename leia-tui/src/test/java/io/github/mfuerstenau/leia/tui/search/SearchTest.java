package io.github.mfuerstenau.leia.tui.search;

import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.LikeTerm;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.protocol.search.SortValue;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings;
import io.github.mfuerstenau.leia.tui.search.Search.Mode;
import io.github.mfuerstenau.leia.tui.search.Search.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The tests of {@code search.rs}, ported, and what the asynchronous replies add. */
class SearchTest {

    private static final Analyzer STANDARD = AnalyzerSettings.standardAnalyzer();
    private static final int PAGE = Search.SEARCH_PAGE;

    private static Similarity bm25() {
        return new Similarity(Optional.of(new Bm25Similarity(1.2f, 0.75f)), Optional.empty(), Optional.empty());
    }

    private static IndexField field(String name, IndexOptions indexOptions) {
        return new IndexField(
                name,
                0,
                indexOptions,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.of(new TermStats(1, 1, 1)));
    }

    private static IndexField point(String name, int bytes, DocValuesType docValues) {
        return new IndexField(
                name, 0, IndexOptions.NONE, false, false, false, docValues, 1, bytes, 0, Optional.empty());
    }

    private static IndexField docValues(String name, DocValuesType docValues) {
        return new IndexField(name, 0, IndexOptions.NONE, false, false, false, docValues, 0, 0, 0, Optional.empty());
    }

    /**
     * {@code id} (docs only), {@code stored} (no postings), {@code title} and {@code body}
     * (positions), the points {@code price} (8 bytes, with numeric doc values) and {@code quantity} (4
     * bytes), and {@code category} (sorted doc values).
     */
    private static List<IndexField> fields() {
        return List.of(
                field("id", IndexOptions.DOCS),
                field("stored", IndexOptions.NONE),
                field("title", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS),
                field("body", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS),
                point("price", 8, DocValuesType.NUMERIC),
                point("quantity", 4, DocValuesType.NONE),
                docValues("category", DocValuesType.SORTED));
    }

    private static Search search() {
        return new Search(fields(), 500);
    }

    private static Optional<Request> press(Search search, KeyEvent key) {
        return search.handleKey(key);
    }

    private static Optional<Request> press(Search search, KeyCode code) {
        return search.handleKey(key(code));
    }

    private static Optional<Request> press(Search search, char c) {
        return search.handleKey(key(c));
    }

    private static void typed(Search search, String text) {
        text.codePoints()
                .forEach(c -> assertThat(press(search, KeyEvent.ofChar(c))).isEmpty());
    }

    private static SearchQuery classic(String expression, String field, QueryOperator operator) {
        return new SearchQuery(
                Optional.of(new ClassicQuery(
                        expression,
                        field,
                        operator,
                        new ParserOptions(
                                false, 0, 0, true, Optional.empty(), List.of(), Optional.empty(), Optional.empty()),
                        2,
                        false,
                        false,
                        STANDARD)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static StartSearchParams params(SearchQuery query, int offset, int limit) {
        return new StartSearchParams(query, bm25(), List.of(), offset, limit, false, Optional.empty());
    }

    /** The search that {@code request} starts. */
    private static Request.Start started(Optional<Request> request) {
        assertThat(request).containsInstanceOf(Request.Start.class);
        return (Request.Start) request.orElseThrow();
    }

    /** Asserts that {@code request} starts a search with these params, cancelling these jobs. */
    private static Request.Start assertSearch(
            Optional<Request> request, SearchQuery query, int offset, int limit, List<Long> cancel) {
        Request.Start start = started(request);
        assertThat(start.params()).isEqualTo(params(query, offset, limit));
        assertThat(start.cancel()).isEqualTo(cancel);
        return start;
    }

    private static Hit hit(int docId) {
        return new Hit(
                docId,
                1.5f,
                List.of(),
                List.of(new HitField("id", List.of(new StoredValue(StoredType.STRING, "doc-" + docId, false)))));
    }

    private static GetSearchResult results(int from, int to, long totalHits) {
        return new GetSearchResult(
                500,
                Optional.of(new SearchResults(
                        "title:red",
                        "title:red",
                        totalHits,
                        TotalHitsRelation.EQUAL_TO,
                        IntStream.range(from, to).mapToObj(SearchTest::hit).toList(),
                        3,
                        Optional.empty())));
    }

    private static String render(Search search) {
        return render(search, 100, 24);
    }

    private static String render(Search search, int width, int height) {
        return TestScreens.render(frame -> search.render(frame, frame.area()), width, height);
    }

    /** A search for {@code red} whose first page is in: hits 0 to {@code count - 1} of {@code totalHits}. */
    private static Search searched(int count, long totalHits) {
        Search search = search();
        press(search, '/');
        typed(search, "red");
        long id = started(press(search, KeyCode.ENTER)).searchId();
        assertThat(search.searchStarted(id, 1)).isEmpty();
        search.searchProgress(id, results(0, count, totalHits));
        return search;
    }

    /** The id of the running search. */
    private static long runningId(Search search) {
        return search.searchJob().orElseThrow()[0];
    }

    /** Starts the running search as {@code jobId} and returns its id. */
    private static long start(Search search, Optional<Request> request, long jobId) {
        long id = started(request).searchId();
        assertThat(search.searchStarted(id, jobId)).isEmpty();
        return id;
    }

    /** The search screen in regex mode, with {@code pattern} submitted. */
    private static Request.StoredScan regex(Search search, String pattern) {
        for (int i = 0; i < 6; i++) {
            assertThat(press(search, 'm')).isEmpty();
        }
        press(search, '/');
        typed(search, pattern);
        Optional<Request> request = press(search, KeyCode.ENTER);
        assertThat(request).containsInstanceOf(Request.StoredScan.class);
        Request.StoredScan scan = (Request.StoredScan) request.orElseThrow();
        assertThat(scan.cancel()).isEmpty();
        return scan;
    }

    private static StoredScanHit scanHit(int docId, String field) {
        return new StoredScanHit(
                docId, docId == 37, field, 1, 12, 1, List.of(new MatchSpan(4, 7)), new Snippet(0, "the red lamp"));
    }

    private static GetStoredScanResult scanPoll(List<StoredScanHit> hits, boolean done) {
        return new GetStoredScanResult(
                250,
                2,
                2,
                hits,
                done ? Optional.of(new StoredScanResult(500, 2, 2, false, 0, List.of(), 9)) : Optional.empty());
    }

    @Test
    void regexModeScansTheStoredValues() {
        Search search = search();
        Request.StoredScan scan = regex(search, "re+d (x)");

        assertThat(scan.params()).isEqualTo(new StartStoredScanParams("re+d (x)", false, Optional.empty(), false));
        search.storedScanStarted(scan.scanId(), 6);
        assertThat(search.storedScanJob().map(job -> job[1])).hasValue(6L);
        assertThat(search.jobs()).containsExactly(6L);
        search.storedScanProgress(scan.scanId(), scanPoll(List.of(scanHit(3, "body")), false));
        assertThat(search.storedScanFromHit()).isEqualTo(1);
        assertThat(render(search))
                .contains(
                        "Regex over stored values · fields all · match case",
                        "2 values · scanning… 50% of 500 docs",
                        "the red lamp");

        search.storedScanProgress(scan.scanId(), scanPoll(List.of(scanHit(37, "title")), true));
        assertThat(search.storedScanJob()).isEmpty();
        assertThat(render(search)).contains("2 values in 2 docs in 9 ms", "37✗");
    }

    @Test
    void regexModeSettingsScanAgainAndOtherKeysDoNothing() {
        Search search = search();
        Request.StoredScan scan = regex(search, "red");
        search.storedScanStarted(scan.scanId(), 6);

        Request.StoredScan again = (Request.StoredScan) press(search, 'i').orElseThrow();
        assertThat(again.params().caseInsensitive()).isTrue();
        assertThat(again.cancel()).containsExactly(6L);
        Request.StoredScan deleted = (Request.StoredScan) press(search, 'd').orElseThrow();
        assertThat(deleted.params().includeDeleted()).isTrue();
        for (char c : "psfFatx".toCharArray()) {
            assertThat(press(search, c)).as("%s", c).isEmpty();
            assertThat(search.hasForm()).as("%s", c).isFalse();
        }
        assertThat(render(search)).contains("ignore case · deleted docs too");
    }

    @Test
    void regexHitsOpenTheirDocumentAndValue() {
        Search search = search();
        Request.StoredScan scan = regex(search, "red");
        search.storedScanStarted(scan.scanId(), 6);
        search.storedScanProgress(
                scan.scanId(), scanPoll(List.of(scanHit(3, "body"), scanHit(3, "title"), scanHit(8, "body")), true));

        assertThat(press(search, KeyCode.ENTER)).hasValue(new Request.Document(3));
        press(search, KeyCode.DOWN);
        assertThat(press(search, 'v')).hasValue(new Request.View(3, "title", 1, List.of(new MatchSpan(4, 7)), 1));
        assertThat(search.step(true)).hasValue(new Request.Document(8));
        assertThat(search.step(true)).isEmpty();
    }

    @Test
    void leavingRegexModeStopsTheScan() {
        Search search = search();
        Request.StoredScan scan = regex(search, "red");
        search.storedScanStarted(scan.scanId(), 6);

        assertThat(press(search, 'm')).hasValue(new Request.Cancel(List.of(6L)));
        assertThat(search.mode()).isEqualTo(Mode.CLASSIC);
        assertThat(search.hasScan()).isFalse();
        // Back in regex mode, nothing runs by itself.
        for (int i = 0; i < 6; i++) {
            assertThat(press(search, 'm')).isEmpty();
        }
        assertThat(search.hasScan()).isFalse();
    }

    @Test
    void aStoppedScanKeepsItsHits() {
        Search search = search();
        Request.StoredScan scan = regex(search, "red");
        search.storedScanStarted(scan.scanId(), 6);
        search.storedScanProgress(scan.scanId(), scanPoll(List.of(scanHit(3, "body")), false));

        assertThat(search.cancel()).containsExactly(6L);

        assertThat(search.jobs()).isEmpty();
        assertThat(search.running()).isFalse();
        assertThat(render(search)).contains("2 values so far · stopped", "the red lamp");
    }

    @Test
    void typesAQueryForTheFirstTextField() {
        Search search = search();
        assertThat(render(search)).contains("Press / to type a query.", "default field title · OR · count to 1000");

        assertThat(press(search, '/')).isEmpty();
        assertThat(search.prompting()).isTrue();
        typed(search, "red qx");
        press(search, ctrl('w'));
        typed(search, "fox");

        assertSearch(press(search, KeyCode.ENTER), classic("red fox", "title", QueryOperator.OR), 0, PAGE, List.of());
        assertThat(search.prompting()).isFalse();
        // Esc leaves the line without searching; a blank line searches nothing.
        press(search, '/');
        assertThat(press(search, KeyCode.ESCAPE)).isEmpty();
        assertThat(search.prompting()).isFalse();
    }

    @Test
    void aBlankQuerySearchesNothing() {
        Search search = search();
        press(search, '/');
        typed(search, "  ");

        assertThat(press(search, KeyCode.ENTER)).isEmpty();
        assertThat(press(search, 't')).isEmpty();
    }

    @Test
    void showsTheProgressThenTheHits() {
        Search search = search();
        press(search, '/');
        typed(search, "red");
        long id = start(search, press(search, KeyCode.ENTER), 1);
        assertThat(search.searchJob())
                .hasValueSatisfying(job -> assertThat(job[1]).isEqualTo(1));
        search.searchProgress(id, new GetSearchResult(200, Optional.empty()));
        assertThat(render(search)).contains("searching… 40%");

        assertThat(search.searchProgress(id, results(0, 2, 2))).isEmpty();
        assertThat(search.searchJob()).isEmpty();
        assertThat(render(search)).contains("2 hits in 3 ms", "id=doc-1", "Parsed     title:red");
        assertThat(press(search, KeyCode.DOWN)).isEmpty();
        assertThat(press(search, KeyCode.ENTER)).hasValue(new Request.Document(1));
    }

    @Test
    void fetchesTheNextPageAtTheLastRow() {
        Search search = searched(PAGE, 150);
        SearchQuery query = classic("red", "title", QueryOperator.OR);

        long id = assertSearch(press(search, KeyCode.END), query, PAGE, PAGE, List.of())
                .searchId();
        search.searchStarted(id, 2);
        // No second request while the page is on its way.
        assertThat(press(search, KeyCode.END)).isEmpty();
        assertThat(search.searchProgress(id, results(100, 150, 150))).isEmpty();
        assertThat(render(search)).contains("150 hits");

        // That was the last page.
        assertThat(press(search, KeyCode.END)).isEmpty();
        assertThat(render(search)).doesNotContain("…");
    }

    @Test
    void aFailedPageKeepsTheHits() {
        Search search = searched(PAGE, 150);
        long id = start(search, press(search, KeyCode.END), 2);

        search.searchFailed(id, "Cannot search the index: gone");

        assertThat(render(search)).contains("150 hits in 3 ms · cannot read more");
        assertThat(press(search, KeyCode.UP)).isEmpty();
        assertThat(press(search, KeyCode.END)).isEmpty();
    }

    @Test
    void showsWhyASearchFailed() {
        Search search = search();
        press(search, '/');
        typed(search, "title:(red");
        long id = started(press(search, KeyCode.ENTER)).searchId();

        search.searchFailed(id, "Invalid query: Cannot parse 'title:(red'");

        assertThat(render(search)).contains("Search failed", "Cannot parse 'title:(red'");
        assertThat(search.searchJob()).isEmpty();
    }

    @Test
    void aChangedSettingSearchesAgainAndStopsTheRunningSearch() {
        Search search = searched(2, 2);
        press(search, '/');
        start(search, press(search, KeyCode.ENTER), 7);

        assertSearch(press(search, 'a'), classic("red", "title", QueryOperator.AND), 0, PAGE, List.of(7L));
        assertSearch(press(search, 'f'), classic("red", "body", QueryOperator.AND), 0, PAGE, List.of());
        // f wraps around, and F steps back.
        press(search, 'f');
        assertSearch(press(search, 'F'), classic("red", "body", QueryOperator.AND), 0, PAGE, List.of());
        assertThat(started(press(search, 't')).params().exactTotalHits()).isTrue();
        assertThat(render(search)).contains("AND · exact count");
    }

    @Test
    void termModeTakesTheLineAsOneTerm() {
        Search search = searched(2, 2);

        press(search, 'm');
        StartSearchParams params = started(press(search, 'm')).params();
        assertThat(params.query().term()).hasValue(new TermQuery("title", new BytesValue("red", false)));
        assertThat(render(search)).contains("Term · field title");
        // There is no operator and there are no parser options for a term.
        assertThat(press(search, 'a')).isEmpty();
        assertThat(press(search, 'p')).isEmpty();
        assertThat(search.prompting()).isFalse();
    }

    private static DocValuesQuery docValuesOf(Optional<Request> request) {
        return started(request).params().query().docValues().orElseThrow();
    }

    private static BytesValue text(String value) {
        return new BytesValue(value, false);
    }

    /** The search screen in doc values mode, on {@code price}. */
    private static Search docValuesMode() {
        Search search = search();
        for (int i = 0; i < 3; i++) {
            assertThat(press(search, 'm')).isEmpty();
        }
        assertThat(search.mode()).isEqualTo(Mode.DOC_VALUES);
        return search;
    }

    @Test
    void docValuesModeSearchesTheValuesOfAField() {
        Search search = docValuesMode();
        assertThat(render(search))
                .contains(
                        "Doc values · field price (NUMERIC) · long · count to 1000",
                        "Press / to type a value, several values, a range, or a prefix.");
        assertThat(search.keys()).startsWith("/ values");

        press(search, '/');
        typed(search, "{1 TO *]");
        assertThat(docValuesOf(press(search, KeyCode.ENTER)))
                .isEqualTo(new DocValuesQuery(
                        "price",
                        Optional.of(DocValuesNumber.LONG),
                        Optional.empty(),
                        Optional.of(new DocValuesRange(Optional.of(text("1")), Optional.empty(), false, true)),
                        Optional.empty()));

        // n steps the encoding of the numbers, and searches again.
        assertThat(docValuesOf(press(search, 'n')).number()).hasValue(DocValuesNumber.DOUBLE);
        assertThat(render(search)).contains("price (NUMERIC) · double bits");
        // There is no operator and there are no parser options; h is for bytes only.
        assertThat(press(search, 'a')).isEmpty();
        assertThat(press(search, 'p')).isEmpty();
        assertThat(press(search, 'h')).isEmpty();
        assertThat(search.prompting()).isFalse();
    }

    @Test
    void docValuesModeStepsThroughTheFieldsWithDocValues() {
        Search search = docValuesMode();
        press(search, '/');
        typed(search, "books \"new york\"");
        press(search, KeyCode.ENTER);

        DocValuesQuery query = docValuesOf(press(search, 'f'));
        assertThat(query.field()).isEqualTo("category");
        assertThat(query.number()).isEmpty();
        assertThat(query.values()).hasValue(List.of(text("books"), text("new york")));
        assertThat(render(search)).contains("Doc values · field category (SORTED) · text");
        // n is for numbers only.
        assertThat(press(search, 'n')).isEmpty();
        // f goes round to price again.
        assertThat(docValuesOf(press(search, 'f')).field()).isEqualTo("price");
    }

    @Test
    void docValuesModeSearchesByAPrefix() {
        Search search = docValuesMode();
        press(search, '/');
        typed(search, "1*");
        assertThat(press(search, KeyCode.ENTER)).isEmpty();
        assertThat(render(search)).contains("A prefix is for doc values of bytes; numbers take a range.");

        DocValuesQuery query = docValuesOf(press(search, 'f'));
        assertThat(query.field()).isEqualTo("category");
        assertThat(query.prefix()).hasValue(text("1"));
        assertThat(query.values()).isEmpty();
        assertThat(query.range()).isEmpty();
    }

    @Test
    void aDocValuesLineThatDoesntReadSaysWhy() {
        Search search = docValuesMode();
        press(search, 'f');
        press(search, '/');
        typed(search, "80ff");
        start(search, press(search, KeyCode.ENTER), 4);

        // In hex, "80ff" reads; "8" doesn't, and the running search stops.
        Optional<Request> hex = press(search, 'h');
        assertThat(docValuesOf(hex).values()).hasValue(List.of(new BytesValue("80ff", true)));
        start(search, hex, 5);
        press(search, '/');
        press(search, KeyCode.BACKSPACE);
        press(search, KeyCode.BACKSPACE);
        press(search, KeyCode.BACKSPACE);
        assertThat(press(search, KeyCode.ENTER)).hasValue(new Request.Cancel(List.of(5L)));
        assertThat(render(search)).contains("Cannot read the values", "'8' is not hex", "category (SORTED) · hex");

        press(search, '/');
        typed(search, "0");
        assertThat(docValuesOf(press(search, KeyCode.ENTER)).values()).isPresent();
        assertThat(render(search)).doesNotContain("Cannot read the values");
    }

    @Test
    void theDocumentsScreenSearchesDocValues() {
        Search search = search();

        DocValuesQuery query =
                docValuesOf(search.docValuesQuery("category", DocValuesNumber.FLOAT, false, List.of("new york", "*")));

        assertThat(query.number()).isEmpty();
        assertThat(query.values()).hasValue(List.of(text("new york"), text("*")));
        assertThat(search.inputText()).isEqualTo("\"new york\" \"*\"");
        // A value ending in * is quoted, so it is not a prefix.
        assertThat(docValuesOf(search.docValuesQuery("category", DocValuesNumber.LONG, false, List.of("kit*")))
                        .values())
                .hasValue(List.of(text("kit*")));
        assertThat(search.inputText()).isEqualTo("\"kit*\"");
        query = docValuesOf(search.docValuesQuery("price", DocValuesNumber.FLOAT, false, List.of("1.5")));
        assertThat(query.number()).hasValue(DocValuesNumber.FLOAT);
        assertThat(query.values()).hasValue(List.of(text("1.5")));
        assertThat(search.docValuesQuery("title", DocValuesNumber.LONG, false, List.of()))
                .isEmpty();
    }

    @Test
    void docValuesBrowseTheDocumentsAndSurviveAReopen() {
        Search search = docValuesMode();
        search.docValuesQuery("category", DocValuesNumber.LONG, true, List.of("00"));

        Request.Filter filter = (Request.Filter) press(search, 'b').orElseThrow();
        assertThat(filter.label()).isEqualTo("category:00");
        assertThat(filter.params().query().flatMap(SearchQuery::docValues).map(DocValuesQuery::field))
                .hasValue("category");

        search.setNumber(DocValuesNumber.SORTABLE_FLOAT);
        Search reopened = search.reopened(
                List.of(
                        docValues("category", DocValuesType.SORTED),
                        field("title", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS)),
                500,
                false);
        assertThat(reopened.mode()).isEqualTo(Mode.DOC_VALUES);
        assertThat(reopened.docValuesFieldName()).hasValue("category");
        assertThat(reopened.number()).isEqualTo(DocValuesNumber.SORTABLE_FLOAT);
        assertThat(reopened.hex()).isTrue();
    }

    @Test
    void docValuesModeWithoutDocValuesSaysSo() {
        Search search = new Search(List.of(field("title", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS)), 5);
        for (int i = 0; i < 3; i++) {
            press(search, 'm');
        }
        assertThat(render(search)).contains("No field has doc values");
        press(search, '/');
        assertThat(search.prompting()).isFalse();
    }

    private static MoreLikeThisQuery like(OptionalInt docId, Optional<String> text, boolean boost) {
        return new MoreLikeThisQuery(
                docId,
                text,
                List.of("title", "body"),
                2,
                5,
                OptionalInt.empty(),
                25,
                boost,
                STANDARD,
                0,
                OptionalInt.empty(),
                List.of());
    }

    private static MoreLikeThisQuery like(int docId) {
        return like(OptionalInt.of(docId), Optional.empty(), false);
    }

    private static LikeTerm likeTerm(String field, String term, int tf, int docFreq, float score) {
        return new LikeTerm(field, term, tf, docFreq, score / tf, score);
    }

    @Test
    void likeTextModeTakesTheLineAsATextAndShowsTheChosenTerms() {
        Search search = search();
        for (int i = 0; i < 5; i++) {
            assertThat(press(search, 'm')).isEmpty();
        }
        assertThat(render(search))
                .contains("More like this text · fields title · body · count to 1000", "Press / to type a text");
        assertThat(search.keys()).startsWith("/ text");

        press(search, '/');
        // Anything goes into the line, unlike a document id.
        typed(search, "Red lamp 42");
        Request.Start start = started(press(search, KeyCode.ENTER));
        assertThat(start.params().query().moreLikeThis())
                .hasValue(like(OptionalInt.empty(), Optional.of("Red lamp 42"), false));
        // The popup is the same as for a document; f/F don't step a field.
        assertThat(press(search, 'f')).isEmpty();
        assertThat(press(search, 'p')).isEmpty();
        assertThat(search.keys()).contains("a/n all/no fields");
        press(search, KeyCode.ESCAPE);

        search.searchStarted(start.searchId(), 1);
        SearchResults found = results(2, 4, 2).results().orElseThrow();
        search.searchProgress(
                start.searchId(),
                new GetSearchResult(
                        500,
                        Optional.of(new SearchResults(
                                "like(text \"Red lamp 42\", …)",
                                found.rewrittenQuery(),
                                found.totalHits(),
                                found.totalHitsRelation(),
                                found.hits(),
                                found.tookMillis(),
                                Optional.of(List.of(
                                        likeTerm("title", "lamp", 1, 19, 4.218876f),
                                        likeTerm("body", "red\u0007", 12, 1234, 2.5f)))))));
        String screen = render(search, 100, 40);
        assertThat(screen)
                .contains(
                        "Terms          score    tf    df  field  term",
                        "              4.2189     1    19  title  lamp",
                        "              2.5000    12  1234  body   red\\u{7}");
        // A text has no source among the hits.
        assertThat(screen).doesNotContain("◆");

        // m goes on to the regex mode, which runs nothing by itself: the search is done.
        assertThat(press(search, 'm')).isEmpty();
        assertThat(search.mode()).isEqualTo(Mode.REGEX);
    }

    @Test
    void moreLikeThisModeTakesTheLineAsADocumentId() {
        Search search = search();
        for (int i = 0; i < 4; i++) {
            assertThat(press(search, 'm')).isEmpty();
        }
        assertThat(render(search))
                .contains(
                        "More like this document · fields title · body · count to 1000",
                        "Press / to type a document id");

        press(search, '/');
        // Only digits go into the line.
        typed(search, "4x2 ");
        assertThat(started(press(search, KeyCode.ENTER)).params().query().moreLikeThis())
                .hasValue(like(42));
        // The fields are the popup's, and there is no operator.
        assertThat(press(search, 'f')).isEmpty();
        assertThat(press(search, 'a')).isEmpty();

        assertThat(press(search, 'p')).isEmpty();
        assertThat(search.prompting()).isTrue();
        assertThat(search.keys()).contains("a/n all/no fields");
        for (int i = 0; i < 4; i++) {
            press(search, KeyCode.DOWN);
        }
        press(search, ' ');
        assertThat(started(press(search, KeyCode.ENTER)).params().query().moreLikeThis())
                .hasValue(like(OptionalInt.of(42), Optional.empty(), true));
    }

    @Test
    void marksTheSourceAmongTheHits() {
        Search search = search();
        Request.Start start = started(search.moreLikeThis(3));
        assertThat(start.cancel()).isEmpty();
        assertThat(start.params().query().moreLikeThis()).hasValue(like(3));
        search.searchStarted(start.searchId(), 1);
        search.searchProgress(start.searchId(), results(2, 5, 3));
        assertThat(render(search)).contains("◆ id=doc-3", "◆ marks document 3, the source.");

        long id = started(search.moreLikeThis(4)).searchId();
        search.searchStarted(id, 2);
        search.searchProgress(
                id,
                new GetSearchResult(
                        500,
                        Optional.of(new SearchResults(
                                "like(doc 4, …)",
                                "MatchNoDocsQuery(\"empty BooleanQuery\")",
                                0,
                                TotalHitsRelation.EQUAL_TO,
                                List.of(),
                                1,
                                Optional.of(List.of())))));
        assertThat(render(search)).contains("No term of the document passed the limits");
    }

    @Test
    void aLineThatIsNoDocumentIdForgetsTheHits() {
        Search search = searched(2, 2);
        press(search, 'm');
        start(search, press(search, 'm'), 6);
        // The doc values mode takes "red" as a value too.
        Optional<Request> running = press(search, 'm');
        assertThat(started(running).cancel()).containsExactly(6L);
        start(search, running, 7);

        // "red" is no document id: the doc values search's hits and job go.
        assertThat(press(search, 'm')).hasValue(new Request.Cancel(List.of(7L)));
        assertThat(search.searchJob()).isEmpty();
        assertThat(render(search)).doesNotContain("Search failed").contains("More like this document");
        assertThat(render(search)).doesNotContain("doc-0");
    }

    @Test
    void standardModeSendsThePointTypes() {
        Search search = searched(2, 2);

        Request.Start start = started(press(search, 'm'));
        assertThat(start.params().query().standard())
                .hasValue(new StandardQuery(
                        "red",
                        "title",
                        QueryOperator.OR,
                        new ParserOptions(
                                false, 0, 0, true, Optional.empty(), List.of(), Optional.empty(), Optional.empty()),
                        List.of(new PointField("price", PointType.LONG), new PointField("quantity", PointType.INT)),
                        STANDARD));
        search.searchStarted(start.searchId(), 2);
        search.searchProgress(start.searchId(), results(0, 2, 2));
        assertThat(render(search))
                .contains("Standard query · default field title", "Points     price LONG · quantity INT");
    }

    @Test
    void theOptionsPopupSearchesAgainOnceApplied() {
        Search search = searched(2, 2);

        assertThat(press(search, 'p')).isEmpty();
        assertThat(search.prompting()).isTrue();
        assertThat(render(search)).contains("Classic parser options");
        // Esc cancels: nothing changes, nothing is searched.
        press(search, ' ');
        assertThat(press(search, KeyCode.ESCAPE)).isEmpty();
        assertThat(search.prompting()).isFalse();
        // Enter without a change searches nothing either.
        press(search, 'p');
        assertThat(press(search, KeyCode.ENTER)).isEmpty();

        // Leading wildcards on, phrase slop 2.
        press(search, 'p');
        press(search, ' ');
        press(search, KeyCode.DOWN);
        press(search, '2');
        Request.Start start = started(press(search, KeyCode.ENTER));
        assertThat(start.cancel()).isEmpty();
        ClassicQuery classic = start.params().query().classic().orElseThrow();
        assertThat(classic.options().allowLeadingWildcard()).isTrue();
        assertThat(classic.options().phraseSlop()).isEqualTo(2);
        assertThat(search.prompting()).isFalse();
        search.searchStarted(start.searchId(), 3);
        search.searchProgress(start.searchId(), results(0, 2, 2));
        assertThat(render(search)).contains("Options    leading wildcards · phrase slop 2");
    }

    @Test
    void theOptionsPopupListsThePointTypesForTheStandardParser() {
        Search search = searched(2, 2);
        long id = start(search, press(search, 'm'), 2);
        search.searchProgress(id, results(0, 2, 2));

        press(search, 'p');
        String screen = render(search);
        assertThat(screen).contains("Standard parser options", "price                  LONG");
        assertThat(screen).doesNotContain("Fuzzy max edits");
        // The last row is quantity: INT becomes FLOAT.
        press(search, KeyCode.END);
        press(search, KeyCode.RIGHT);
        assertThat(started(press(search, KeyCode.ENTER))
                        .params()
                        .query()
                        .standard()
                        .orElseThrow()
                        .points()
                        .get(1))
                .isEqualTo(new PointField("quantity", PointType.FLOAT));
    }

    @Test
    void searchesATopTermUntilItIsEdited() {
        Search search = search();
        BytesValue binary = new BytesValue("ff00", true);

        assertThat(started(search.termQuery("body", binary))
                        .params()
                        .query()
                        .term()
                        .orElseThrow()
                        .term())
                .isEqualTo(binary);
        assertThat(render(search)).contains("Term · field body · hex");

        press(search, '/');
        typed(search, "1");
        assertThat(started(press(search, KeyCode.ENTER))
                        .params()
                        .query()
                        .term()
                        .orElseThrow()
                        .term())
                .isEqualTo(new BytesValue("ff001", false));
        assertThat(search.termQuery("stored", binary)).isEmpty();
    }

    @Test
    void explainsTheSelectedHit() {
        Search search = searched(2, 2);
        SearchQuery query = classic("red", "title", QueryOperator.OR);

        Optional<Request> request = press(search, 'x');
        assertThat(request).containsInstanceOf(Request.Explain.class);
        Request.Explain explain = (Request.Explain) request.orElseThrow();
        assertThat(explain.params()).isEqualTo(new StartExplainParams(query, bm25(), 0));
        assertThat(explain.cancel()).isEmpty();
        assertThat(search.explainStarted(explain.explainId(), 4)).isEmpty();
        assertThat(search.explainJob().map(job -> job[1])).hasValue(4L);
        assertThat(search.jobs()).containsExactly(4L);
        assertThat(render(search)).contains("Explaining…");
        // Asked for already.
        assertThat(press(search, 'x')).isEmpty();

        search.explainProgress(
                explain.explainId(),
                new GetExplainResult(Optional.of(new Explanation(
                        true,
                        "1.5",
                        "sum of:",
                        List.of(new Explanation(false, "0.0", "no matching term", List.of()))))));
        assertThat(search.explainJob()).isEmpty();
        assertThat(render(search)).contains("Explanation of doc 0", "1.5  sum of:", "  0.0  no matching term");

        // Another hit shows the query until it is explained too.
        press(search, KeyCode.DOWN);
        assertThat(render(search)).contains("x explains the selected hit.");
        Request.Explain next = (Request.Explain) press(search, 'x').orElseThrow();
        assertThat(next.params().docId()).isEqualTo(1);
    }

    @Test
    void theSortPopupSearchesAgainAndTheHitsShowTheirSortValues() {
        Search search = searched(2, 2);

        assertThat(press(search, 's')).isEmpty();
        assertThat(search.prompting()).isTrue();
        assertThat(render(search)).contains("Similarity and sort");
        // Similarity, k1, b, then "Sort by": past the score and the document id to price, read as LONG.
        for (KeyCode code :
                List.of(KeyCode.DOWN, KeyCode.DOWN, KeyCode.DOWN, KeyCode.RIGHT, KeyCode.RIGHT, KeyCode.RIGHT)) {
            assertThat(press(search, code)).isEmpty();
        }
        Request.Start start = started(press(search, KeyCode.ENTER));
        assertThat(start.params().sort())
                .containsExactly(new SortKey(
                        Optional.of(
                                new FieldSort("price", SortType.LONG, false, SortSelector.MIN, SortMissing.DEFAULT)),
                        Optional.empty(),
                        Optional.empty()));
        assertThat(start.params().similarity()).isEqualTo(bm25());
        search.searchStarted(start.searchId(), 2);
        List<Hit> hits =
                new ArrayList<>(results(0, 2, 2).results().orElseThrow().hits());
        hits.set(
                0,
                new Hit(
                        0,
                        1.5f,
                        List.of(new SortValue(Optional.of("4621819117588971520"), Optional.empty())),
                        hits.get(0).fields()));
        hits.set(
                1,
                new Hit(
                        1,
                        1.5f,
                        List.of(new SortValue(Optional.empty(), Optional.empty())),
                        hits.get(1).fields()));
        search.searchProgress(
                start.searchId(),
                new GetSearchResult(
                        500,
                        Optional.of(new SearchResults(
                                "title:red", "title:red", 2, TotalHitsRelation.EQUAL_TO, hits, 3, Optional.empty()))));

        assertThat(render(search)).contains("sorted by price ↑", "Sorted by", "4621819117588971520", "–");
    }

    @Test
    void theSimilarityGoesToTheSearchAndItsExplanations() {
        Search search = searched(2, 2);

        press(search, 's');
        press(search, ' ');
        Request.Start start = started(press(search, KeyCode.ENTER));
        Similarity classic = new Similarity(Optional.empty(), Optional.of(new ClassicSimilarity()), Optional.empty());
        assertThat(start.params().similarity()).isEqualTo(classic);
        assertThat(start.params().sort()).isEmpty();
        search.searchStarted(start.searchId(), 2);
        search.searchProgress(start.searchId(), results(0, 2, 2));
        assertThat(render(search)).contains("Similarity classic TF-IDF");
        assertThat(((Request.Explain) press(search, 'x').orElseThrow()).params().similarity())
                .isEqualTo(classic);
        // Esc in the popup changes nothing.
        press(search, 's');
        press(search, ' ');
        assertThat(press(search, KeyCode.ESCAPE)).isEmpty();
        assertThat(search.prompting()).isFalse();
    }

    @Test
    void theFieldsPopupChoosesTheStoredFieldsPerHit() {
        Search search = searched(2, 2);

        assertThat(press(search, 'l')).isEmpty();
        assertThat(render(search)).contains("Stored fields per hit");
        press(search, 'n');
        press(search, ' ');
        Request.Start start = started(press(search, KeyCode.ENTER));
        assertThat(start.params().fields()).hasValue(List.of("id"));
        search.searchStarted(start.searchId(), 2);
        search.searchProgress(start.searchId(), results(0, 2, 2));
        assertThat(render(search)).contains("Fields     id");

        // The next page asks for the same fields.
        search = searched(PAGE, 150);
        press(search, 'l');
        press(search, ' ');
        long id = start(search, press(search, KeyCode.ENTER), 2);
        search.searchProgress(id, results(0, PAGE, 150));
        StartSearchParams next = started(press(search, KeyCode.END)).params();
        assertThat(next.offset()).isEqualTo(PAGE);
        assertThat(next.fields().orElseThrow()).hasSize(6);
    }

    @Test
    void browsesTheDocumentsThatMatchFromTheSelectedHit() {
        Search search = searched(3, 3);
        press(search, KeyCode.DOWN);

        assertThat(press(search, 'b'))
                .hasValue(new Request.Filter(
                        new StartFilterParams(
                                Optional.of(classic("red", "title", QueryOperator.OR)), Optional.empty(), false),
                        "red",
                        1));
        Request.Filter withDeleted = (Request.Filter) press(search, 'B').orElseThrow();
        assertThat(withDeleted.params().includeDeleted()).isTrue();

        // Without a query, there is nothing to browse.
        assertThat(press(search(), 'b')).isEmpty();
    }

    @Test
    void browsesTheDocumentsWithAValueThatTheRegexFinds() {
        Search search = search();
        regex(search, "re[d]");
        Request.StoredScan scan = (Request.StoredScan) press(search, 'i').orElseThrow();
        search.storedScanStarted(scan.scanId(), 6);
        search.storedScanProgress(scan.scanId(), scanPoll(List.of(scanHit(3, "body"), scanHit(8, "title")), true));
        press(search, KeyCode.DOWN);

        assertThat(press(search, 'B'))
                .hasValue(new Request.Filter(
                        new StartFilterParams(
                                Optional.empty(),
                                Optional.of(new FilterPattern("re[d]", true, Optional.empty())),
                                true),
                        "/re[d]/i",
                        8));
    }

    @Test
    void stepsThroughTheHitsAndIntoTheNextPage() {
        Search search = searched(PAGE, 150);

        assertThat(search.step(false)).isEmpty();
        assertThat(search.step(true)).hasValue(new Request.Document(1));
        for (int docId = 2; docId < PAGE; docId++) {
            assertThat(search.step(true)).hasValue(new Request.Document(docId));
        }
        // Past the last hit: the next page first, then its first hit.
        long id = assertSearch(search.step(true), classic("red", "title", QueryOperator.OR), PAGE, PAGE, List.of())
                .searchId();
        assertThat(search.step(true)).isEmpty();
        search.searchStarted(id, 2);
        assertThat(search.searchProgress(id, results(100, 150, 150))).hasValue(new Request.Document(100));
        assertThat(search.step(false)).hasValue(new Request.Document(99));
        assertThat(render(search)).contains("150 hits");
    }

    @Test
    void cancellingTurnsAMissingFirstPageIntoAnError() {
        Search search = search();
        press(search, '/');
        typed(search, "red");
        start(search, press(search, KeyCode.ENTER), 1);
        assertThat(search.jobs()).containsExactly(1L);

        assertThat(search.cancel()).containsExactly(1L);

        assertThat(search.jobs()).isEmpty();
        assertThat(render(search)).contains("Cancelled.");
    }

    @Test
    void marksCutValuesAndApproximateCounts() {
        Search search = search();
        press(search, '/');
        typed(search, "red");
        long id = started(press(search, KeyCode.ENTER)).searchId();
        Hit hit = new Hit(
                0,
                1.5f,
                List.of(),
                List.of(
                        new HitField("id", List.of(new StoredValue(StoredType.STRING, "doc-0", false))),
                        new HitField(
                                "key",
                                List.of(new StoredValue(StoredType.BINARY, "ff00", true, OptionalLong.of(300))))));

        search.searchProgress(
                id,
                new GetSearchResult(
                        500,
                        Optional.of(new SearchResults(
                                "title:red",
                                "title:red",
                                1000,
                                TotalHitsRelation.GREATER_THAN_OR_EQUAL_TO,
                                List.of(hit),
                                3,
                                Optional.empty()))));

        assertThat(render(search)).contains("at least 1000 hits", "id=doc-0  key=0xff00…");
    }

    @Test
    void moreHitsFollowAFullPageWithinTheWindow() {
        TotalHitsRelation equal = TotalHitsRelation.EQUAL_TO;
        TotalHitsRelation atLeast = TotalHitsRelation.GREATER_THAN_OR_EQUAL_TO;
        assertThat(Search.moreHits(true, 100, 150, equal)).isTrue();
        assertThat(Search.moreHits(true, 100, 100, equal)).isFalse();
        assertThat(Search.moreHits(false, 50, 150, equal)).isFalse();
        assertThat(Search.moreHits(true, 1000, 1000, atLeast)).isTrue();
        assertThat(Search.moreHits(true, StartSearchParams.MAX_WINDOW, 20_000, atLeast))
                .isFalse();
    }

    @Test
    void anIndexWithoutPostingsHasNothingToSearch() {
        Search search = new Search(List.of(field("stored", IndexOptions.NONE)), 1);

        assertThat(press(search, '/')).isEmpty();
        assertThat(search.prompting()).isFalse();
        assertThat(render(search)).contains("nothing to search");
    }

    // What the asynchronous replies add (ADR 0034 §10).

    @Test
    void aLateReplyOfAReplacedSearchIsDroppedAndItsJobCancelled() {
        Search search = search();
        press(search, '/');
        typed(search, "red");
        long first = started(press(search, KeyCode.ENTER)).searchId();
        // A changed setting replaces the search before its job has started.
        long second = started(press(search, 't')).searchId();

        assertThat(search.searchStarted(first, 3)).hasValue(3);
        assertThat(search.searchStarted(second, 4)).isEmpty();
        search.searchProgress(first, results(0, 1, 1));
        assertThat(render(search)).doesNotContain("doc-0");
        search.searchFailed(first, "late");
        assertThat(render(search)).doesNotContain("late");
        search.searchProgress(second, results(0, 2, 2));
        assertThat(render(search)).contains("2 hits");
    }

    @Test
    void escBeforeTheJobStartedCancelsItOnceItStarts() {
        Search search = search();
        press(search, '/');
        typed(search, "red");
        long id = started(press(search, KeyCode.ENTER)).searchId();
        assertThat(search.running()).isTrue();

        assertThat(search.cancel()).isEmpty();

        assertThat(search.running()).isFalse();
        assertThat(search.searchStarted(id, 9)).hasValue(9);
        assertThat(render(search)).contains("Cancelled.");
    }

    @Test
    void aReopenedSearchKeepsTheLineAndRunsNothing() {
        Search search = searched(2, 2);
        Search reopened = search.reopened(fields(), 500, true);

        assertThat(reopened.running()).isFalse();
        assertThat(reopened.inputText()).isEqualTo("red");
        assertThat(render(reopened)).doesNotContain("doc-0");
        reopened.handleKey(key('/'));
        assertThat(started(reopened.handleKey(key(KeyCode.ENTER))).params().query())
                .isEqualTo(classic("red", "title", QueryOperator.OR));
    }
}
