package io.github.mfuerstenau.leia.tui.app;

import static io.github.mfuerstenau.leia.tui.TestFilters.built;
import static io.github.mfuerstenau.leia.tui.TestFilters.filterParams;
import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.ReaderSegment;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsResult;
import io.github.mfuerstenau.leia.protocol.jars.JarComponents;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.FieldMatches;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.OffsetSource;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.protocol.search.ValueHighlight;
import io.github.mfuerstenau.leia.protocol.terms.ExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import io.github.mfuerstenau.leia.protocol.terms.StartExportTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.TermDelimiter;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.FilterStep;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport.DocumentData;
import io.github.mfuerstenau.leia.tui.documents.Documents;
import io.github.mfuerstenau.leia.tui.jars.DefaultJars;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shell of {@code app.rs}: its tests, ported, and what the asynchronous calls add. */
class AppTest {

    private static final VersionResult VERSION =
            new VersionResult("leia-server", "1", "abc", "t", new JavaRuntime("25", "v", "/h"));

    private static App app() {
        return app(Optional.empty());
    }

    private static App app(Optional<Path> home) {
        return new App(VERSION, home, History.inMemory(), DirectoryImpl.FS);
    }

    private static IndexCommitStats index(String path, int docBase, int maxDoc, String segmentsFile) {
        return new IndexCommitStats(
                path,
                docBase,
                segmentsFile,
                1,
                1,
                maxDoc == 0 ? 0 : 1,
                maxDoc,
                maxDoc,
                10,
                "10.5.2",
                Optional.empty(),
                Map.of(),
                maxDoc == 0 ? List.of() : List.of(new ReaderSegment("_0", docBase, maxDoc, maxDoc)));
    }

    private static GetIndexStatsResult stats(String path) {
        return new GetIndexStatsResult(
                path, "MMapDirectory", 0, 0, 0, List.of(index(path, 0, 0, "segments_1")), List.of());
    }

    private static IndexField field(String name, int number) {
        return field(name, number, true);
    }

    private static IndexField field(String name, int number, boolean indexed) {
        return new IndexField(
                name,
                number,
                indexed ? IndexOptions.DOCS : IndexOptions.NONE,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                indexed ? Optional.of(new TermStats(1, 1, 1)) : Optional.empty());
    }

    private static GetIndexStatsResult withFields(GetIndexStatsResult stats, IndexField... fields) {
        return new GetIndexStatsResult(
                stats.path(),
                stats.directoryImpl(),
                stats.segmentCount(),
                stats.numDocs(),
                stats.maxDoc(),
                stats.indexes(),
                List.of(fields));
    }

    /** An index with the fields {@code id} and {@code body}, and {@code rank} without postings. */
    private static App appWithFields() {
        App app = app();
        app.opened(
                withFields(stats("/data/idx"), field("id", 0), field("body", 1), field("rank", 2, false)), List.of());
        return app;
    }

    private static GetFieldTermScanResult running(long termsScanned) {
        return new GetFieldTermScanResult(termsScanned, Optional.empty());
    }

    private static GetFieldTermScanResult done(String field, long uniqueTerms) {
        return new GetFieldTermScanResult(uniqueTerms, Optional.of(new FieldTerms(field, uniqueTerms, List.of())));
    }

    private static Optional<Action> start(long scanId, String field) {
        return Optional.of(new Action.StartScan(scanId, field, Overview.DEFAULT_TOP_TERMS));
    }

    private static Optional<Action> open(String path) {
        return Optional.of(new Action.Open(path, DirectoryImpl.FS, OpenMode.READER));
    }

    private static void typed(App app, String text) {
        text.codePoints()
                .forEach(c -> assertThat(app.handleKey(KeyEvent.ofChar(c))).isEmpty());
    }

    private static String render(App app) {
        return TestScreens.render(app::render, 100, 20);
    }

    @Test
    void theNextPromptStartsFromAPathThatFailedToOpen() {
        App app = app();
        app.handleKey(key('o'));
        typed(app, "/data/es");
        app.handleKey(key(KeyCode.ENTER));
        app.openFailed("Missing codec", false);
        app.handleKey(key(KeyCode.ESCAPE));

        app.handleKey(key('o'));
        assertThat(render(app)).contains("/data/es");
        // Only once, and not after a prompt that was cancelled without a failure.
        app.handleKey(key(KeyCode.ESCAPE));
        app.handleKey(key('o'));
        assertThat(render(app)).doesNotContain("/data/es");
    }

    @Test
    void theAnalysisScreenNeedsNoIndex() {
        App app = app();
        assertThat(render(app)).contains("6 analysis");

        assertThat(app.handleKey(key('6'))).hasValue(new Action.LoadAnalysisComponents());
        app.componentsFailed("down");
        String screen = render(app);
        assertThat(screen).contains("6 Analysis", "standard analyzer", "q quit   o open   / text");
        // The other screens need an index.
        assertThat(app.handleKey(key('5'))).isEmpty();
        assertThat(render(app)).contains("standard analyzer");

        // b builds a custom analyzer, which the backend tries first.
        assertThat(app.handleKey(key('b'))).isEmpty();
        assertThat(app.handleKey(key(KeyCode.LEFT))).isEmpty();
        Optional<Action> applied = app.handleKey(key(KeyCode.ENTER));
        assertThat(applied).containsInstanceOf(Action.Analyze.class);
        AnalyzeParams params = ((Action.Analyze) applied.orElseThrow()).params();
        assertThat(params.analyzer().custom()).isPresent();
        assertThat(app.analyzed(params.text(), new AnalyzeResult(List.of(), List.of())))
                .isEmpty();
        assertThat(render(app)).contains("standard › lowercase");

        // An index opened now keeps the analysis screen shown.
        assertThat(app.opened(stats("/data/idx"), List.of())).isEmpty();
        assertThat(render(app)).contains("1-7 screen", "standard › lowercase");
    }

    @Test
    void analyzesAStoredValueOnTheAnalysisScreen() {
        App app = appWithDocuments();
        app.handleKey(key('2'));
        // The Rust test answered with another document, which the port drops (ADR 0034 §10).
        app.documentLoaded(document(0));
        assertThat(wide(app)).contains("a analyze");

        Optional<Action> analyze = app.handleKey(key('a'));
        assertThat(analyze)
                .hasValue(new Action.Analyze(
                        new AnalyzeParams(AnalyzerSettings.standardAnalyzer(), "doc-0", Optional.empty())));
        // The screen came without its components, which follow the analysis.
        assertThat(app.analyzed("doc-0", new AnalyzeResult(List.of(), List.of())))
                .hasValue(new Action.LoadAnalysisComponents());
        String screen = render(app);
        assertThat(screen).contains("6 Analysis", "Text · doc 0 · id");

        // So does the viewer, for the value it shows.
        assertThat(app.handleKey(key('2'))).isEmpty();
        assertThat(app.handleKey(key('v'))).isEmpty();
        assertThat(wide(app)).contains("a analyze");
        assertThat(app.handleKey(key('a')))
                .hasValue(new Action.Analyze(
                        new AnalyzeParams(AnalyzerSettings.standardAnalyzer(), "doc-0", Optional.empty())));
        assertThat(app.handleKey(key('c'))).hasValue(new Action.Close());
        app.closed();
        assertThat(app.hasIndex()).isFalse();
    }

    /** The search screen of an index of three documents with {@code id} and {@code body}, after {@code query} was submitted and its job started. */
    private App searching(String query) {
        App app = app();
        app.opened(withDocs(stats("/data/idx"), 3, field("id", 0), field("body", 1)), List.of());
        assertThat(app.handleKey(key('5'))).isEmpty();
        app.handleKey(key('/'));
        typed(app, query);
        Optional<Action> start = app.handleKey(key(KeyCode.ENTER));
        assertThat(start).containsInstanceOf(Action.StartSearch.class);
        searchId = ((Action.StartSearch) start.orElseThrow()).searchId();
        assertThat(((Action.StartSearch) start.orElseThrow()).cancel()).isEmpty();
        assertThat(app.searchStarted(searchId, 1)).isEmpty();
        return app;
    }

    /** The id of the search that {@link #searching} started. */
    private long searchId;

    private static GetSearchResult page(int... docIds) {
        return new GetSearchResult(
                3,
                Optional.of(new SearchResults(
                        "body:q",
                        "body:q",
                        docIds.length,
                        TotalHitsRelation.EQUAL_TO,
                        Arrays.stream(docIds)
                                .mapToObj(docId -> new Hit(docId, 1f, List.of(), List.of(), List.of()))
                                .toList(),
                        1,
                        Optional.empty())));
    }

    @Test
    void theQueryLineTakesEveryKey() {
        // q and 1 are typed, not handled as quit and screen keys.
        App app = searching("q1");

        assertThat(render(app)).contains("5 Search", "│q1", "Esc cancel");
        assertThat(app.polls()).containsExactly(new Poll.Search(searchId, 1));
    }

    @Test
    void escStopsTheSearchJobsBeforeItQuits() {
        App app = searching("q");
        app.searchProgress(searchId, page(2));
        Optional<Action> explain = app.handleKey(key('x'));
        assertThat(explain).containsInstanceOf(Action.StartExplain.class);
        long explainId = ((Action.StartExplain) explain.orElseThrow()).explainId();
        assertThat(app.explainStarted(explainId, 2)).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.Explain(explainId, 2));

        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.CancelSearch(List.of(2L)));
        assertThat(app.polls()).isEmpty();
        assertThat(render(app)).contains("Cancelled.");
        // A late reply changes nothing.
        app.explainProgress(explainId, new GetExplainResult(Optional.empty()));
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.Quit());
    }

    @Test
    void aSearchJobThatStartsAfterEscIsCancelled() {
        App app = app();
        app.opened(withDocs(stats("/data/idx"), 3, field("id", 0)), List.of());
        app.handleKey(key('5'));
        app.handleKey(key('/'));
        typed(app, "q");
        long id = ((Action.StartSearch) app.handleKey(key(KeyCode.ENTER)).orElseThrow()).searchId();

        // Esc before the job has started: nothing to cancel yet, but nothing quits either.
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(app.searchStarted(id, 7)).hasValue(new Action.CancelSearch(List.of(7L)));
        assertThat(app.polls()).isEmpty();
    }

    @Test
    void enterOnAHitOpensItsDocumentAndTheBracketsStepThroughTheHits() {
        App app = searching("q");
        assertThat(app.searchProgress(searchId, page(2, 0))).isEmpty();

        Action.LoadDocument open = loadWithMatches(app.handleKey(key(KeyCode.ENTER)), 2);
        app.documentLoaded(document(2));
        assertThat(wide(app)).contains("2 Documents", "[ ] hits").doesNotContain("[ ] term docs");

        Action.LoadDocument next = loadWithMatches(app.handleKey(key(']')), 0);
        app.documentLoaded(document(0));
        assertThat(app.handleKey(key(']'))).isEmpty();
        Action.LoadDocument back = loadWithMatches(app.handleKey(key('[')), 2);
        assertThat(List.of(open, next, back))
                .allSatisfy(load -> assertThat(load.cancel()).isEmpty());
    }

    @Test
    void aHitOpenedFromASearchMarksItsMatches() {
        App app = searching("q");
        app.searchProgress(searchId, page(2, 0));

        Action.LoadDocument open = loadWithMatches(app.handleKey(key(KeyCode.ENTER)), 2);
        Documents.MatchesStart matches = open.matches().orElseThrow();
        assertThat(matches.params().field()).isEmpty();
        app.documentLoaded(document(2));
        assertThat(app.documentMatchesStarted(matches.matchesId(), 4)).isEmpty();
        assertThat(app.polls()).contains(new Poll.DocumentMatches(matches.matchesId(), 4));
        assertThat(wide(app)).contains("Fields (1, finding matches…, / seeks)");

        app.documentMatchesProgress(
                matches.matchesId(),
                new GetHighlightResult(Optional.of(new DocumentHighlight(List.of(new FieldMatches(
                        "id",
                        OffsetSource.ANALYSIS,
                        List.of(new ValueHighlight(0, 1, List.of(new MatchSpan(4, 5)))),
                        false))))));
        assertThat(app.polls()).doesNotContain(new Poll.DocumentMatches(matches.matchesId(), 4));
        assertThat(TestScreens.render(app::render, 140, 24))
                .contains("Fields (1, matches in 1, / seeks)", "Matches     1 in 1 value, offsets from analysis");

        // The next hit's matches still run when → steps on to a document that isn't a hit: they
        // stay for when it comes back, but a new hit cancels their job.
        Action.LoadDocument next = loadWithMatches(app.handleKey(key(']')), 0);
        app.documentLoaded(document(0));
        assertThat(app.documentMatchesStarted(next.matches().orElseThrow().matchesId(), 5))
                .isEmpty();
        assertThat(app.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(1));
        app.documentLoaded(document(1));
        assertThat(wide(app)).doesNotContain("matches");
        assertThat(app.handleKey(key('[')))
                .hasValueSatisfying(action ->
                        assertThat(((Action.LoadDocument) action).cancel()).containsExactly(5L));
    }

    /** A load of {@code docId} that finds the matches of the search in it. */
    private static Action.LoadDocument loadWithMatches(Optional<Action> action, int docId) {
        assertThat(action).containsInstanceOf(Action.LoadDocument.class);
        Action.LoadDocument load = (Action.LoadDocument) action.orElseThrow();
        assertThat(load.docId()).isEqualTo(docId);
        assertThat(load.matches())
                .hasValueSatisfying(
                        matches -> assertThat(matches.params().docId()).isEqualTo(docId));
        return load;
    }

    @Test
    void bBrowsesTheDocumentsThroughTheQuery() {
        App app = searching("q");
        app.searchProgress(searchId, page(2, 0));

        Optional<Action> filter = app.handleKey(key('b'));
        assertThat(filter).containsInstanceOf(Action.StartFilter.class);
        assertThat(((Action.StartFilter) filter.orElseThrow()).params().includeDeleted())
                .isFalse();
        assertThat(render(app)).contains("2 Documents");
    }

    @Test
    void aRegexScanIsPolledFromItsLastHitAndOpensAValue() {
        App app = app();
        app.opened(withDocs(stats("/data/idx"), 3, field("id", 0), field("body", 1)), List.of());
        app.handleKey(key('5'));
        for (int i = 0; i < 7; i++) {
            assertThat(app.handleKey(key('m'))).isEmpty();
        }
        app.handleKey(key('/'));
        typed(app, "q+");
        Optional<Action> start = app.handleKey(key(KeyCode.ENTER));
        assertThat(start).containsInstanceOf(Action.StartStoredScan.class);
        Action.StartStoredScan scan = (Action.StartStoredScan) start.orElseThrow();
        assertThat(scan.params().pattern()).isEqualTo("q+");
        assertThat(scan.cancel()).isEmpty();
        app.storedScanStarted(scan.scanId(), 3);
        assertThat(app.polls()).containsExactly(new Poll.StoredScan(scan.scanId(), 3, 0));

        StoredScanHit hit =
                new StoredScanHit(2, false, "body", 0, 2, 1, List.of(new MatchSpan(0, 2)), new Snippet(0, "qq"));
        app.storedScanProgress(scan.scanId(), new GetStoredScanResult(1, 1, 1, List.of(hit), Optional.empty()));
        assertThat(app.polls()).containsExactly(new Poll.StoredScan(scan.scanId(), 3, 1));
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.CancelSearch(List.of(3L)));
        assertThat(app.polls()).isEmpty();

        assertThat(app.handleKey(key('v'))).isEqualTo(load(2));
        assertThat(render(app)).contains("2 Documents");
    }

    @Test
    void reopeningKeepsTheQueryOfTheSearchScreenWithoutRunningIt() {
        App app = searching("red");
        app.handleKey(key('R'));

        // id would be the default field of a new search screen, as it is the first with postings.
        app.reopened(withDocs(stats("/data/idx"), 3, field("title", 0), field("id", 1)));

        assertThat(app.polls()).isEmpty();
        app.handleKey(key('/'));
        Optional<Action> start = app.handleKey(key(KeyCode.ENTER));
        ClassicQuery classic = ((Action.StartSearch) start.orElseThrow())
                .params()
                .query()
                .classic()
                .orElseThrow();
        assertThat(classic.expression()).isEqualTo("red");
        assertThat(classic.defaultField()).isEqualTo("id");
    }

    @Test
    void mOnADocumentSearchesForDocumentsLikeIt() {
        App app = app();
        app.opened(withDocs(stats("/data/idx"), 3, field("id", 0), field("body", 1)), List.of());
        assertThat(app.handleKey(key('2'))).isEqualTo(load(0));
        app.documentLoaded(document(0));

        Optional<Action> start = app.handleKey(key('m'));
        assertThat(start).containsInstanceOf(Action.StartSearch.class);
        assertThat(((Action.StartSearch) start.orElseThrow()).cancel()).isEmpty();
        MoreLikeThisQuery like = ((Action.StartSearch) start.orElseThrow())
                .params()
                .query()
                .moreLikeThis()
                .orElseThrow();
        assertThat(like.docId()).hasValue(0);
        // No field has positions: the first indexed one.
        assertThat(like.fields()).containsExactly("id");
        assertThat(render(app)).contains("More like this document · field id");
    }

    @Test
    void sOnADocValueSearchesForTheDocumentsWithIt() {
        App app = app();
        IndexField id = new IndexField(
                "id",
                0,
                IndexOptions.DOCS,
                false,
                false,
                false,
                DocValuesType.SORTED,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TermStats(1, 1, 1)));
        app.opened(withDocs(stats("/data/idx"), 3, id), List.of());
        assertThat(app.handleKey(key('2'))).isEqualTo(load(0));
        app.documentLoaded(new GetDocumentResult(
                0,
                false,
                List.of(new DocumentField(
                        "id",
                        OptionalLong.empty(),
                        List.of(new StoredValue(StoredType.STRING, "doc-0", false)),
                        Optional.of(
                                new DocValues(DocValuesType.SORTED, List.of(), List.of(new BytesValue("a b", false)))),
                        false,
                        Optional.empty()))));

        Optional<Action> start = app.handleKey(key('s'));
        assertThat(start).containsInstanceOf(Action.StartSearch.class);
        DocValuesQuery query = ((Action.StartSearch) start.orElseThrow())
                .params()
                .query()
                .docValues()
                .orElseThrow();
        assertThat(query.field()).isEqualTo("id");
        assertThat(query.values()).hasValue(List.of(new BytesValue("a b", false)));
        assertThat(render(app)).contains("Doc values · field id (SORTED) · text");
    }

    @Test
    void slashOnATopTermSearchesForIt() {
        App app = appWithFields();
        app.handleKey(key(KeyCode.DOWN));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(start(1, "body"));
        app.scanStarted(1, 1);
        app.scanProgress(
                1,
                new GetFieldTermScanResult(
                        1, Optional.of(new FieldTerms("body", 1, List.of(new TopTerm("ff", true, 1, 1))))));
        app.handleKey(key(KeyCode.TAB));
        assertThat(wide(app)).contains("/ search term");

        Optional<Action> start = app.handleKey(key('/'));
        assertThat(start).containsInstanceOf(Action.StartSearch.class);
        assertThat(((Action.StartSearch) start.orElseThrow()).params().query().term())
                .hasValue(new TermQuery("body", new BytesValue("ff", true)));
        assertThat(render(app)).contains("Term · field body · hex");
    }

    @Test
    void anAnalyzerAppliedOnTheAnalysisScreenParsesTheQueries() {
        App app = searching("q");
        app.handleKey(key('6'));
        app.componentsFailed("down");
        app.handleKey(key('b'));
        app.handleKey(key(KeyCode.LEFT));
        Action.Analyze analyze =
                (Action.Analyze) app.handleKey(key(KeyCode.ENTER)).orElseThrow();
        app.analyzed(analyze.params().text(), new AnalyzeResult(List.of(), List.of()));

        app.handleKey(key('5'));
        app.handleKey(key('/'));
        Optional<Action> start = app.handleKey(key(KeyCode.ENTER));
        assertThat(((Action.StartSearch) start.orElseThrow())
                        .params()
                        .query()
                        .classic()
                        .orElseThrow()
                        .analyzer()
                        .custom())
                .isPresent();
    }

    @Test
    void thePromptOffersTheOpenedPaths() {
        App app = app();
        app.opened(stats("/data/a"), List.of());
        app.opened(stats("/data/b"), List.of());

        app.handleKey(key('o'));
        app.handleKey(key(KeyCode.UP));

        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(open("/data/a"));
    }

    @Test
    void opensWithTheDirectoryChosenLast() {
        App app = new App(VERSION, Optional.empty(), History.inMemory(), DirectoryImpl.NIO);
        app.handleKey(key('o'));
        typed(app, "/data/idx");

        app.handleKey(ctrl('d'));
        app.handleKey(ctrl('d'));
        assertThat(app.handleKey(key(KeyCode.ENTER)))
                .hasValue(new Action.Open("/data/idx", DirectoryImpl.MMAP, OpenMode.READER));
        app.openFailed("No such file or directory: /data/idx", false);
        assertThat(render(app)).contains("directory: MMap");
        app.handleKey(key(KeyCode.ESCAPE));
        assertThat(app.directory()).isEqualTo(DirectoryImpl.MMAP);
        app.handleKey(key('o'));
        assertThat(render(app)).contains("directory: MMap");
    }

    @Test
    void opensAnIndexFromThePrompt() {
        App app = app();
        assertThat(render(app)).contains("No index open");

        app.handleKey(key('o'));
        typed(app, "/data/idx");
        assertThat(render(app)).contains("Open index");

        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(open("/data/idx"));
        assertThat(render(app)).contains("Opening…");
        app.opened(stats("/data/idx"), List.of());

        String screen = render(app);
        assertThat(app.hasIndex()).isTrue();
        assertThat(screen).contains("Path       /data/idx").doesNotContain("Open index");
    }

    @Test
    void showsTheLogoWithoutAnIndexWhereItFits() {
        App app = app();
        String screen = render(app);
        assertThat(screen).contains("▀", "leia 1", "build abc", "Java 25 (v) in /h");

        assertThat(TestScreens.render(app::render, 50, 20)).doesNotContain("▀").contains("leia 1");
    }

    @Test
    void theKeyLegendWrapsOnANarrowTerminal() {
        App app = app();
        List<String> rows = TestScreens.render(app::render, 30, 20).lines().toList();
        assertThat(rows.subList(18, 20))
                .containsExactly(" q quit   o open   6 analysis ", " X jars                       ");
        // The screen ends above the footer.
        assertThat(rows.get(17)).startsWith("└").endsWith("┘");

        app = appWithDocuments();
        app.handleKey(key('2'));
        app.documentLoaded(document(0));
        String screen = TestScreens.render(app::render, 60, 30);
        assertThat(screen).contains(" q quit   o open   c close   1-7 screen   ←→ doc   i go to");
        assertThat(screen.lines().toList().getLast())
                .isEqualTo(" r rebuild   E export   T terms   Tab panel   ↑↓" + " ".repeat(12));
    }

    @Test
    void aLegendThatWrapsTakesAThirdOfTheScreenAtMost() {
        App app = appWithDocuments();
        app.handleKey(key('2'));
        app.documentLoaded(document(0));
        List<String> rows = TestScreens.render(app::render, 30, 12).lines().toList();
        assertThat(rows.get(7)).startsWith("└").endsWith("┘");
        assertThat(rows.get(8)).isEqualTo(" q quit   o open   c close    ");
    }

    @Test
    void aFailedOpenKeepsThePromptAndThePreviousIndex() {
        App app = app();
        app.opened(stats("/data/first"), List.of());
        app.handleKey(key('o'));
        typed(app, "x");

        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(open("/data/firstx"));
        app.openFailed("No such file or directory: /data/firstx", false);

        String screen = render(app);
        assertThat(screen).contains("No such file or directory", "Path       /data/first");
        // Esc closes the prompt, not leia.
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(render(app)).doesNotContain("Open index");
        assertThat(app.hasIndex()).isTrue();
    }

    @Test
    void aFailedOpenFromTheCommandLineShowsThePath() {
        App app = app();

        app.opening("/data/missing", OpenMode.READER);
        app.openFailed("No such file or directory: /data/missing", false);

        assertThat(render(app)).contains("│/data/missing", "No such file or directory: /data/missing");
    }

    @Test
    void expandsTheHomeDirectoryOnSubmit() {
        App app = app(Optional.of(Path.of("/home/me")));
        app.handleKey(key('o'));
        typed(app, "~/idx");

        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(open(Path.of("/home/me") + "/idx"));
    }

    @Test
    void closesAndQuits() {
        App app = app();
        assertThat(app.handleKey(key('c'))).isEmpty();

        app.opened(stats("/data/idx"), List.of());
        assertThat(app.handleKey(key('c'))).hasValue(new Action.Close());
        app.closed();
        assertThat(app.hasIndex()).isFalse();
        assertThat(render(app)).contains("No index open");

        assertThat(app.handleKey(key('q'))).hasValue(new Action.Quit());
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.Quit());
    }

    @Test
    void typingQInThePromptDoesNotQuitButCtrlCDoes() {
        App app = app();
        app.handleKey(key('o'));

        typed(app, "q");

        assertThat(app.handleKey(ctrl('c'))).hasValue(new Action.Quit());
    }

    @Test
    void thePromptStartsFromTheOpenIndex() {
        App app = app();
        app.opened(stats("/data/idx"), List.of());
        app.handleKey(key('o'));

        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(open("/data/idx"));
    }

    @Test
    void whileACallRunsOnlyCtrlCDoesAnything() {
        App app = app();
        app.handleKey(key('o'));
        typed(app, "/data/idx");
        app.handleKey(key(KeyCode.ENTER));
        assertThat(app.busy()).isTrue();

        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(app.handleKey(key('q'))).isEmpty();
        assertThat(render(app)).contains("Opening…", "/data/idx");
        assertThat(app.handleKey(ctrl('c'))).hasValue(new Action.Quit());

        app.opened(stats("/data/idx"), List.of());
        assertThat(app.busy()).isFalse();
        assertThat(app.handleKey(key('c'))).hasValue(new Action.Close());
        assertThat(app.busy()).isTrue();
        assertThat(app.handleKey(key('q'))).isEmpty();
        app.closed();
        assertThat(app.handleKey(key('q'))).hasValue(new Action.Quit());
    }

    private static ReopenIndexResult reopenResult(int indexes) {
        IndexEntry entry =
                new IndexEntry("/data/idx", Optional.of(new ReaderInfo(0, "segments_1", 0, 0, 10, Optional.empty())));
        return new ReopenIndexResult(
                false,
                "/data/idx",
                OpenMode.READER,
                OptionalInt.of(0),
                OptionalInt.of(0),
                java.util.Collections.nCopies(indexes, entry),
                List.of());
    }

    @Test
    void rReopensAndTheTabLineSaysWhatCameOfIt() {
        assertThat(app().handleKey(key('R'))).isEmpty();
        App app = appWithFields();
        assertThat(render(app)).contains("R reopen");

        assertThat(app.handleKey(key('R'))).hasValue(new Action.Reopen());
        assertThat(render(app)).contains("Reopening…");
        app.reopenUnchanged(reopenResult(1));
        // At the right end of the tab line.
        assertThat(render(app).lines().findFirst().orElseThrow()).endsWith("  Unchanged: segments_1");

        // The next key clears it.
        app.handleKey(key(KeyCode.DOWN));
        assertThat(render(app)).doesNotContain("Unchanged");

        app.handleKey(key('R'));
        GetIndexStatsResult changed = stats("/data/idx");
        app.reopened(new GetIndexStatsResult(
                changed.path(),
                changed.directoryImpl(),
                0,
                0,
                0,
                List.of(index("/data/idx", 0, 0, "segments_2")),
                List.of()));
        assertThat(render(app)).contains("Reopened: segments_2");
    }

    /** Two index directories below {@code /data}, {@code a} with 2 documents and {@code b} with 3. */
    private static GetIndexStatsResult multiStats(String segmentsFileOfB) {
        return new GetIndexStatsResult(
                "/data",
                "MMapDirectory",
                0,
                5,
                5,
                List.of(index("/data/a", 0, 2, "segments_1"), index("/data/b", 2, 3, segmentsFileOfB)),
                List.of(field("id", 0)));
    }

    @Test
    void opensSeveralIndexes() {
        App app = app();
        app.opened(multiStats("segments_1"), List.of(new SkippedIndex("/data/c", "Corrupt index")));

        String screen = render(app);
        assertThat(screen).contains("2 indexes", "Indexes    2 (1 skipped)", "c  skipped: Corrupt index");

        assertThat(app.handleKey(key('4'))).hasValue(new Action.LoadCommits(0));
        assertThat(render(app)).contains("Indexes (2)");
        app.commitsLoaded(0, list(commit(1, true)));
        // From the commits back to the indexes.
        app.handleKey(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
        assertThat(app.handleKey(key(KeyCode.DOWN))).hasValue(new Action.LoadCommits(1));
        assertThat(app.commitsLoaded(1, list(commit(3, true)))).hasValue(new Action.LoadCommit(1, 3));
    }

    @Test
    void reopeningSeveralIndexesSaysHowManyChanged() {
        App app = app();
        app.opened(multiStats("segments_1"), List.of());
        app.handleKey(key('R'));
        app.reopened(multiStats("segments_2"));
        assertThat(render(app)).contains("Reopened: 1 of 2 indexes");

        app.handleKey(key('R'));
        app.reopenUnchanged(reopenResult(2));
        assertThat(render(app)).contains("Unchanged: 2 indexes");
    }

    private static Optional<Action> openedWithoutReader(App app) {
        return app.openedWithoutReader(new OpenIndexResult(
                "/data/broken",
                OpenMode.DIRECTORY_ONLY,
                OptionalInt.empty(),
                OptionalInt.empty(),
                List.of(new IndexEntry("/data/broken", Optional.empty())),
                List.of(),
                Feature.withoutReader(),
                "10.5.2"));
    }

    /** An index written by Lucene 2.4, which Lucene 3.6 reads with fewer features (ADR 0044). */
    private static App oldLuceneApp() {
        App app = app();
        IndexCommitStats index = new IndexCommitStats(
                "/data/old",
                0,
                "segments_2",
                2,
                1,
                1,
                3,
                3,
                2,
                "2.x",
                Optional.of("2.x"),
                Map.of(),
                List.of(new ReaderSegment("_0", 0, 3, 3)));
        GetIndexStatsResult stats = new GetIndexStatsResult(
                "/data/old", "FS", 1, 3, 3, List.of(index), List.of(field("id", 0), field("body", 1)));
        app.opened(
                stats,
                List.of(),
                Set.of(
                        Feature.INDEX_STATS,
                        Feature.DOCUMENTS,
                        Feature.STORED_VALUES,
                        Feature.TERM_VECTORS,
                        Feature.TERMS,
                        Feature.POSTINGS,
                        Feature.CLASSIC_SEARCH),
                "3.6.2");
        return app;
    }

    @Test
    void anOldLuceneIndexRefusesTheScreensItLacks() {
        App app = oldLuceneApp();
        String overview = render(app);
        assertThat(overview).contains("Lucene 2.x, read with 3.6.2").doesNotContain("R reopen", "a all terms");

        assertThat(app.handleKey(key('4'))).isEmpty();
        assertThat(render(app)).contains("Commits: not for Lucene 2.x");
        app.handleKey(key('7'));
        assertThat(render(app)).contains("Check: not for Lucene 2.x");
        app.handleKey(key('R'));
        assertThat(render(app)).contains("Reopen: not for Lucene 2.x");
        app.handleKey(key('a'));
        assertThat(render(app)).contains("Top terms: not for Lucene 2.x");

        // The screens it has still switch.
        app.handleKey(key('3'));
        assertThat(render(app)).doesNotContain("not for");
        app.handleKey(key('5'));
        assertThat(render(app)).doesNotContain("not for");
    }

    @Test
    void anOldLuceneIndexOpensOnTheOverviewFromAScreenItLacks() {
        App app = appWithFields();
        app.handleKey(key('4'));
        App old = oldLuceneApp();
        assertThat(render(old)).contains("Lucene 2.x");
        // The commits screen was shown: the overview is shown instead.
        app.opened(stats("/data/old"), List.of(), Set.of(Feature.INDEX_STATS), "3.6.2");
        assertThat(render(app)).doesNotContain("r read again");
    }

    private static String legend(App app) {
        return TestScreens.render(app::render, 240, 30);
    }

    @Test
    void anOldLuceneIndexLeavesOutTheDocumentKeysItLacks() {
        App app = oldLuceneApp();
        assertThat(app.handleKey(key('2'))).isEqualTo(load(0));
        assertThat(app.documentLoaded(document(0))).isEmpty();
        assertThat(legend(app))
                .contains("⏎ term vector", "v view", "E export")
                .doesNotContain("r rebuild", "N nearest", "m similar", "t points", "s search value");

        assertThat(app.handleKey(key('r'))).isEmpty();
        assertThat(render(app)).contains("Rebuild: not for Lucene 2.x");
        assertThat(app.handleKey(key('m'))).isEmpty();
        assertThat(render(app)).contains("Similar: not for Lucene 2.x");
    }

    @Test
    void anOldLuceneIndexFetchesNoPositionsForAPosting() {
        App app = oldLuceneApp();
        app.handleKey(key('3'));
        app.termsLoaded(loadTerms("id", null), termPage("1", "2"));
        app.postingsLoaded(loadPostings("id", "1"), postingsOf());
        assertThat(legend(app)).contains("/ seek", "⏎ open doc").doesNotContain("e export");
        assertThat(app.handleKey(key('e'))).isEmpty();
        assertThat(render(app)).contains("Export: not for Lucene 2.x");

        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(0));
        assertThat(app.documentLoaded(document(0))).isEmpty();
    }

    @Test
    void anOldLuceneIndexSearchesWithAClassicQueryOnly() {
        App app = oldLuceneApp();
        assertThat(app.handleKey(key('5'))).isEmpty();
        assertThat(legend(app))
                .contains("/ query", "p/l settings", "⏎ open doc")
                .doesNotContain("m mode", "p/s/l settings", "H highlight", "x explain", "b/B browse");
        assertThat(app.handleKey(key('m'))).isEmpty();
        assertThat(render(app)).contains("Other modes: not for Lucene 2.x");

        app.handleKey(key('/'));
        typed(app, "q");
        Action.StartSearch start =
                (Action.StartSearch) app.handleKey(key(KeyCode.ENTER)).orElseThrow();
        assertThat(start.params().query().classic()).isPresent();
        assertThat(start.params().highlight()).isEmpty();
        app.searchStarted(start.searchId(), 1);
        app.searchProgress(start.searchId(), page(0, 1));

        assertThat(app.handleKey(key('x'))).isEmpty();
        assertThat(render(app)).contains("Explain: not for Lucene 2.x");
        assertThat(app.handleKey(key('s'))).isEmpty();
        assertThat(legend(app)).contains("Similarity and sort: not for Lucene 2.x");
    }

    @Test
    void withoutAReaderOnlyTheCommitsScreenWorks() {
        App app = appWithFields();
        app.handleKey(key('2'));

        assertThat(openedWithoutReader(app)).hasValue(new Action.LoadCommits(0));
        String screen = render(app);
        assertThat(screen)
                .contains("no reader", "4 Commits", "Reading the commits…", "r read again")
                .doesNotContain("R reopen");

        assertThat(app.handleKey(key('2'))).isEmpty();
        assertThat(render(app)).contains("Documents: needs a reader", "r read again");
        assertThat(app.handleKey(key('5'))).isEmpty();
        assertThat(render(app)).contains("Search: needs a reader");
        assertThat(app.handleKey(key('R'))).isEmpty();
        assertThat(render(app)).contains("Reopen: needs a reader");
        app.handleKey(key('1'));
        assertThat(render(app)).contains("Reader     none").doesNotContain("needs a reader");

        assertThat(app.handleKey(key('c'))).hasValue(new Action.Close());
        app.closed();
        assertThat(app.hasIndex()).isFalse();
    }

    @Test
    void openingWithoutAReaderKeepsTheAnalysisScreen() {
        App app = app();
        app.handleKey(key('6'));
        openedWithoutReader(app);
        assertThat(render(app)).contains("Analyzer");
    }

    @Test
    void opensWithoutAReaderFromThePrompt() {
        App app = app();
        app.handleKey(key('o'));
        typed(app, "/data/broken");
        app.handleKey(ctrl('n'));

        assertThat(app.handleKey(key(KeyCode.ENTER)))
                .hasValue(new Action.Open("/data/broken", DirectoryImpl.FS, OpenMode.DIRECTORY_ONLY));
    }

    @Test
    void aCorruptIndexOffersToOpenItWithoutAReader() {
        App app = app();
        app.opening("/data/broken", OpenMode.READER);
        app.openFailed("Corrupt index", true);

        assertThat(render(app)).contains("^N and Enter open it without a reader.");
    }

    @Test
    void aFailedReopenKeepsTheIndexAndSaysWhy() {
        App app = appWithFields();
        app.handleKey(key('R'));

        app.reopenFailed("Corrupt index");

        assertThat(render(app)).contains("Reopen failed: Corrupt index", "Path       /data/idx");
    }

    @Test
    void theTabsMakeRoomForALongNotice() {
        App app = appWithFields();
        assertThat(render(app)).contains("6 Analysis │ 7 Check");
        app.reopenFailed("Corrupt index");

        assertThat(render(app)).contains("6 Analysis│7 Check", "Reopen failed: Corrupt index");
    }

    @Test
    void theTabsMarkTheSelectedScreen() {
        App app = appWithFields();
        app.handleKey(key('5'));

        var buffer = TestScreens.buffer(app::render, 100, 20);
        String tabs = TestScreens.text(buffer).lines().findFirst().orElseThrow();
        int search = tabs.indexOf("5 Search");
        assertThat(buffer.get(search, 0).style().addModifiers()).contains(dev.tamboui.style.Modifier.REVERSED);
        assertThat(buffer.get(tabs.indexOf("1 Overview"), 0).style().addModifiers())
                .doesNotContain(dev.tamboui.style.Modifier.REVERSED);
        assertThat(render(app)).contains("Classic query · default field");
    }

    @Test
    void anIndexOpenedAgainIsAddedToTheHistory() {
        History history = History.inMemory();
        App app = new App(VERSION, Optional.empty(), history, DirectoryImpl.FS);

        app.opened(stats("/data/a"), List.of());
        openedWithoutReader(app);

        assertThat(history.paths()).containsExactly("/data/broken", "/data/a");
    }

    @Test
    void scansTheSelectedFieldAndPollsIt() {
        App app = appWithFields();

        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(start(1, "id"));
        assertThat(app.polls()).isEmpty();
        assertThat(app.scanStarted(1, 7)).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.Scan(1, 7));

        assertThat(app.scanProgress(1, running(1024))).isEmpty();
        assertThat(render(app)).contains("1024…", "Esc cancel scan");
        // A running scan is not started again.
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEmpty();

        assertThat(app.scanProgress(1, done("id", 3))).isEmpty();
        assertThat(app.polls()).isEmpty();
        assertThat(render(app)).contains("id: 3 terms, top 0");
    }

    @Test
    void scansAllFieldsOneAfterAnother() {
        App app = appWithFields();

        assertThat(app.handleKey(key('a'))).isEqualTo(start(1, "id"));
        app.scanStarted(1, 1);
        assertThat(render(app)).contains("queued");

        assertThat(app.scanProgress(1, done("id", 3))).isEqualTo(start(2, "body"));
        app.scanStarted(2, 2);
        assertThat(app.scanFailed(2, "Cannot read")).isEmpty();
        assertThat(app.polls()).isEmpty();
        assertThat(wide(app)).contains("failed");

        // A failed field is scanned again; the others are done or have no postings.
        assertThat(app.handleKey(key('a'))).isEqualTo(start(3, "body"));
    }

    @Test
    void aScanTheBackendRefusesStartsTheNext() {
        App app = appWithFields();
        assertThat(app.handleKey(key('a'))).isEqualTo(start(1, "id"));

        assertThat(app.scanFailed(1, "No field 'id'")).isEqualTo(start(2, "body"));
    }

    @Test
    void escCancelsTheScansBeforeItQuits() {
        App app = appWithFields();
        app.handleKey(key('a'));
        app.scanStarted(1, 3);

        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.CancelScan(3));

        assertThat(app.polls()).isEmpty();
        String screen = render(app);
        assertThat(screen).doesNotContain("queued").doesNotContain("…");
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.Quit());
    }

    @Test
    void aScanCancelledBeforeItsJobStartedIsCancelledOnceItHas() {
        App app = appWithFields();
        app.handleKey(key('a'));

        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(app.scanning()).isFalse();
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(start(2, "id"));

        // The job of the cancelled scan, not of the new one.
        assertThat(app.scanStarted(1, 5)).hasValue(new Action.CancelScan(5));
        assertThat(app.scanStarted(2, 6)).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.Scan(2, 6));
    }

    @Test
    void aReplyForAnotherScanIsIgnored() {
        App app = appWithFields();
        app.handleKey(key(KeyCode.ENTER));
        app.scanStarted(1, 4);
        app.handleKey(key(KeyCode.ESCAPE));
        app.handleKey(key(KeyCode.ENTER));
        app.scanStarted(2, 5);

        assertThat(app.scanProgress(1, done("id", 3))).isEmpty();
        assertThat(app.scanFailed(1, "Cancelled")).isEmpty();

        assertThat(app.polls()).containsExactly(new Poll.Scan(2, 5));
        assertThat(render(app)).doesNotContain("id: 3 terms").doesNotContain("failed");
    }

    @Test
    void enterIgnoresAFieldWithoutPostings() {
        App app = appWithFields();
        app.handleKey(key(KeyCode.END));

        assertThat(app.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(app.scanning()).isFalse();
    }

    @Test
    void plusAndMinusChangeTheTopNOfTheNextScans() {
        App app = appWithFields();
        app.handleKey(key('-'));
        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.StartScan(1, "id", 20));
        app.scanStarted(1, 1);
        // id has more terms than the 20 it got.
        app.scanProgress(1, done("id", 30));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEmpty();

        app.handleKey(key('+'));
        assertThat(TestScreens.render(app::render, 140, 30)).contains("id: 30 terms, top 0 (⏎ top 50)");
        // A scan for 20 isn't enough any more.
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(start(2, "id"));

        // The top N is kept for another index.
        app.handleKey(key('+'));
        app.opened(withFields(stats("/data/idx"), field("id", 0)), List.of());
        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.StartScan(3, "id", 100));
    }

    @Test
    void openingAnotherIndexForgetsTheScans() {
        App app = appWithFields();
        app.handleKey(key('a'));
        app.scanStarted(1, 4);

        app.opened(stats("/data/other"), List.of());

        assertThat(app.polls()).isEmpty();
        assertThat(app.scanProgress(1, done("id", 1))).isEmpty();
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.Quit());
    }

    @Test
    void reopeningKeepsTheScanOfAnUnchangedIndexAndForgetsItOtherwise() {
        App app = appWithFields();
        app.handleKey(key('a'));
        app.scanStarted(1, 4);
        app.handleKey(key('R'));

        app.reopenUnchanged(reopenResult(1));
        assertThat(app.polls()).hasSize(1);

        app.handleKey(key('R'));
        app.reopened(stats("/data/idx"));
        assertThat(app.polls()).isEmpty();
    }

    @Test
    void escCancelsAScanFromAnyScreen() {
        App app = appWithFields();
        app.handleKey(key('a'));
        app.scanStarted(1, 2);
        app.handleKey(key('4'));
        assertThat(render(app)).contains("Esc cancel scan");

        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.CancelScan(2));
        // The screens that aren't ported take no overview keys.
        assertThat(app.handleKey(key('a'))).isEmpty();
    }

    private static GetCommitsResult list(Commit... commits) {
        return new GetCommitsResult(List.of(commits), List.of());
    }

    private static Commit commit(long generation, boolean current) {
        return new Commit(generation, "segments_" + generation, current, 1, 1, 1, 0, "10.5.2", Map.of());
    }

    @Test
    void switchesToTheCommitsAndFetchesThem() {
        App app = appWithFields();

        assertThat(app.handleKey(key('4'))).hasValue(new Action.LoadCommits(0));
        assertThat(app.commitsLoaded(0, list(commit(2, true), commit(1, false))))
                .hasValue(new Action.LoadCommit(0, 2));
        app.commitLoaded(0, 2, new GetCommitResult(commit(2, true), List.of(), List.of()));
        String screen = render(app);
        assertThat(screen).contains("4 Commits", "No segments.", "r read again");

        assertThat(app.handleKey(key(KeyCode.DOWN))).hasValue(new Action.LoadCommit(0, 1));
        assertThat(app.handleKey(key('r'))).hasValue(new Action.LoadCommits(0));
    }

    /** A segment of a commit, as {@code getCommit} lists it. */
    private static Segment commitSegment(String name, int docBase, int maxDoc) {
        return new Segment(
                name,
                Optional.empty(),
                maxDoc,
                docBase,
                0,
                0,
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                "10.5.2",
                Optional.empty(),
                Optional.of("Lucene104"),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                OptionalLong.empty(),
                Map.of(),
                Map.of());
    }

    @Test
    void enterOnASegmentShowsItsDocumentsPinnedToIt() {
        App app = app();
        IndexCommitStats index = new IndexCommitStats(
                "/data/idx",
                0,
                "segments_2",
                2,
                1,
                2,
                5,
                5,
                10,
                "10.5.2",
                Optional.empty(),
                Map.of(),
                List.of(new ReaderSegment("_0", 0, 3, 3), new ReaderSegment("_1", 3, 2, 2)));
        app.opened(
                new GetIndexStatsResult("/data/idx", "MMapDirectory", 2, 5, 5, List.of(index), List.of(field("id", 0))),
                List.of());
        app.handleKey(key('4'));
        app.commitsLoaded(0, list(commit(2, true), commit(1, false)));
        app.commitLoaded(
                0,
                2,
                new GetCommitResult(
                        commit(2, true), List.of(commitSegment("_0", 0, 3), commitSegment("_1", 3, 2)), List.of()));
        app.commitLoaded(0, 1, new GetCommitResult(commit(1, false), List.of(commitSegment("_5", 0, 3)), List.of()));

        // A segment of the older commit, merged away since.
        assertThat(app.handleKey(key(KeyCode.DOWN))).isEmpty();
        app.handleKey(key(KeyCode.ENTER));
        assertThat(render(app)).contains("⏎ documents");
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(render(app)).contains("_5 isn't in the opened commit", "4 Commits");

        app.handleKey(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
        app.handleKey(key(KeyCode.UP));
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.DOWN));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(3));
        app.documentLoaded(document(3));
        assertThat(render(app)).contains("Document segment _1 ", "#3 of 0–4   _1 #0 of 0–1");
        // ← stays in the segment.
        assertThat(app.handleKey(key(KeyCode.LEFT))).isEmpty();
    }

    /** On the terms screen of an index of 3 documents in segment _0, with id's terms "1" and "2". */
    private static App appOnSegmentTerms() {
        App app = app();
        app.opened(
                new GetIndexStatsResult(
                        "/data/idx",
                        "MMapDirectory",
                        1,
                        3,
                        3,
                        List.of(index("/data/idx", 0, 3, "segments_1")),
                        List.of(field("id", 0), field("body", 1))),
                List.of());
        assertThat(app.handleKey(key('3'))).hasValue(loadTerms("id", null));
        app.termsLoaded(loadTerms("id", null), termPage("1", "2"));
        app.postingsLoaded(loadPostings("id", "1"), postingsOf());
        return app;
    }

    @Test
    void backspaceRetracesTheJumps() {
        App app = appOnSegmentTerms();
        assertThat(legend(app)).doesNotContain("⌫ back");
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEmpty();

        // Terms ⏎ → documents.
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(0));
        app.documentLoaded(document(0));
        assertThat(legend(app)).contains("⌫ back", "C commit", "T terms");

        // Documents C → the commits screen, on the document's segment of the opened commit.
        assertThat(app.handleKey(key('C'))).hasValue(new Action.LoadCommits(0));
        assertThat(app.commitsLoaded(0, list(commit(2, false), commit(1, true))))
                .hasValue(new Action.LoadCommit(0, 1));
        app.commitLoaded(0, 1, new GetCommitResult(commit(1, true), List.of(commitSegment("_0", 0, 3)), List.of()));
        assertThat(legend(app)).contains("Segments of segments_1", "Segment _0", "⏎ documents", "⌫ back");

        // Back to the document as it was left, then to the terms.
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEmpty();
        assertThat(legend(app)).contains("Document ", "#0 of 0–2");
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEmpty();
        assertThat(legend(app)).contains("id (/ seeks)", "Postings (2)").doesNotContain("⌫ back");
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEmpty();
        assertThat(legend(app)).contains("id (/ seeks)");
    }

    /** Document 0 with the fields id and body, body selected once {@code ↓} is pressed. */
    private static GetDocumentResult documentWithBody(int docId) {
        return new GetDocumentResult(
                docId,
                false,
                List.of(
                        new DocumentField(
                                "id",
                                OptionalLong.empty(),
                                List.of(new StoredValue(StoredType.STRING, "doc-" + docId, false)),
                                Optional.empty(),
                                false,
                                Optional.empty()),
                        new DocumentField(
                                "body",
                                OptionalLong.empty(),
                                List.of(new StoredValue(StoredType.STRING, "text", false)),
                                Optional.empty(),
                                false,
                                Optional.empty())));
    }

    @Test
    void backspaceShowsTheTermsAsTheJumpLeftThem() {
        App app = appOnSegmentTerms();
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(0));
        app.documentLoaded(documentWithBody(0));

        // T on body: the terms screen shows body's terms and postings.
        app.handleKey(key(KeyCode.DOWN));
        assertThat(app.handleKey(key('T'))).hasValue(loadTerms("body", null));
        app.termsLoaded(loadTerms("body", null), termPage("text"));
        app.postingsLoaded(
                loadPostings("body", "text"),
                new GetPostingsResult(
                        1,
                        1,
                        List.of(new Posting(1, false, OptionalInt.empty(), Optional.empty())),
                        OptionalInt.empty()));
        assertThat(legend(app)).contains("body (/ seeks)", "Postings (1)");

        // Back to the document, which fetches nothing, and whose ] steps along id/1's postings.
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEmpty();
        assertThat(legend(app)).contains("Document ", "[ ] term docs");
        assertThat(app.handleKey(key(']'))).isEqualTo(load(2));
        app.documentLoaded(documentWithBody(2));

        // Back to the terms of id at 1, with the posting ] stepped to.
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEmpty();
        assertThat(legend(app)).contains("id (/ seeks)", "Postings (2)").doesNotContain("body (/ seeks)");
    }

    @Test
    void backspaceShowsTheDocumentAsTheJumpLeftIt() {
        App app = appOnSegmentTerms();
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(0));
        app.documentLoaded(document(0));

        // m searches for documents like it; ⏎ on a hit shows document 2.
        Action.StartSearch like = (Action.StartSearch) app.handleKey(key('m')).orElseThrow();
        app.searchStarted(like.searchId(), 1);
        app.searchProgress(like.searchId(), page(2));
        Action.LoadDocument hit =
                (Action.LoadDocument) app.handleKey(key(KeyCode.ENTER)).orElseThrow();
        assertThat(hit.docId()).isEqualTo(2);
        app.documentLoaded(document(2));
        assertThat(legend(app)).contains("[ ] hits");

        // Back to the search, then to document 0 of the term, fetched again.
        app.handleKey(key(KeyCode.BACKSPACE));
        assertThat(legend(app)).contains("5 Search");
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEqualTo(load(0));
        app.documentLoaded(document(0));
        assertThat(legend(app)).contains("Document ", "[ ] term docs").doesNotContain("[ ] hits");
    }

    @Test
    void aReopenForgetsWhatTheJumpsLeft() {
        App app = appOnSegmentTerms();
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        app.documentLoaded(documentWithBody(0));
        app.handleKey(key(KeyCode.DOWN));
        app.handleKey(key('T'));
        app.termsLoaded(loadTerms("body", null), termPage("text"));

        app.handleKey(key('R'));
        Optional<Action> fetch = app.reopened(new GetIndexStatsResult(
                "/data/idx",
                "MMapDirectory",
                1,
                3,
                3,
                List.of(index("/data/idx", 0, 3, "segments_2")),
                List.of(field("id", 0), field("body", 1))));
        assertThat(fetch).hasValue(loadTerms("body", null));

        // Back still goes to the document and then the terms, as they are now: fetched again, on body.
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEqualTo(load(0));
        assertThat(legend(app)).contains("2 Documents", "⌫ back");
        assertThat(app.handleKey(key(KeyCode.BACKSPACE))).isEmpty();
        app.termsLoaded(loadTerms("body", null), termPage("text"));
        assertThat(legend(app)).contains("body (/ seeks)").doesNotContain("⌫ back");
    }

    @Test
    void aNumberKeyForgetsTheJumps() {
        App app = appOnSegmentTerms();
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        app.documentLoaded(document(0));

        app.handleKey(key('2'));
        assertThat(legend(app)).doesNotContain("⌫ back");
        app.handleKey(key(KeyCode.BACKSPACE));
        assertThat(legend(app)).contains("Document ");
    }

    @Test
    void backspaceInAPromptEditsIt() {
        App app = appOnSegmentTerms();
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        app.documentLoaded(document(0));

        app.handleKey(key('i'));
        app.handleKey(key('1'));
        app.handleKey(key(KeyCode.BACKSPACE));
        // Still the documents screen, with the prompt open.
        assertThat(legend(app)).contains("Document ", "⌫ back");
        app.handleKey(key(KeyCode.ESCAPE));
        app.handleKey(key(KeyCode.BACKSPACE));
        assertThat(legend(app)).contains("id (/ seeks)");
    }

    @Test
    void tOpensTheFieldOnTheTermsScreenAndSSearchesTheTerm() {
        App app = appOnSegmentTerms();
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        app.documentLoaded(document(0));
        app.handleKey(key('1'));
        app.handleKey(key('2'));

        // The hit's field opens at the hit's term, whose page is fetched again.
        assertThat(app.handleKey(key('T'))).hasValue(loadTerms("id", "1"));
        assertThat(legend(app)).contains("⌫ back");

        // s on the terms screen searches for the term.
        assertThat(app.handleKey(key('s')))
                .hasValueSatisfying(action -> assertThat(action).isInstanceOf(Action.StartSearch.class));
        assertThat(legend(app)).contains("5 Search");
        app.handleKey(key(KeyCode.BACKSPACE));
        app.handleKey(key(KeyCode.BACKSPACE));
        assertThat(legend(app)).contains("Document ").doesNotContain("⌫ back");
    }

    @Test
    void theJumpsRefuseWhatAnOldLuceneIndexLacks() {
        App app = oldLuceneApp();
        assertThat(app.handleKey(key('2'))).isEqualTo(load(0));
        app.documentLoaded(document(0));
        assertThat(legend(app)).doesNotContain("C commit");
        assertThat(app.handleKey(key('C'))).isEmpty();
        assertThat(render(app)).contains("Commits: not for Lucene 2.x");

        app.handleKey(key('3'));
        app.termsLoaded(loadTerms("id", null), termPage("1", "2"));
        assertThat(app.handleKey(key('s'))).isEmpty();
        assertThat(render(app)).contains("Search term: not for Lucene 2.x");
    }

    @Test
    void openingAnotherIndexOnTheCommitsScreenFetchesItsCommits() {
        App app = appWithFields();
        app.handleKey(key('4'));
        app.commitsFailed(0, "unreadable");
        assertThat(render(app)).contains("Cannot list the commits: unreadable");

        assertThat(app.opened(stats("/data/other"), List.of())).hasValue(new Action.LoadCommits(0));
    }

    @Test
    void reopeningOnTheCommitsScreenFetchesTheCommitsAgain() {
        App app = appWithFields();
        app.handleKey(key('4'));
        app.commitsLoaded(0, list(commit(1, true)));
        app.handleKey(key('R'));

        assertThat(app.reopened(stats("/data/idx"))).hasValue(new Action.LoadCommits(0));
    }

    private static BytesValue text(String value) {
        return new BytesValue(value, false);
    }

    private static Action.LoadTerms loadTerms(String field, String from) {
        return new Action.LoadTerms(field, Optional.ofNullable(from).map(AppTest::text), false);
    }

    private static Action.LoadPostings loadPostings(String field, String term) {
        return new Action.LoadPostings(field, text(term), 0, false, Optional.empty());
    }

    private static GetTermsResult termPage(String... terms) {
        return new GetTermsResult(
                List.of(terms).stream()
                        .map(term -> new TermEntry(term, false, 1, 1))
                        .toList(),
                Optional.empty());
    }

    /** The postings of a term: documents 0 and 2. */
    private static GetPostingsResult postingsOf() {
        return new GetPostingsResult(
                2,
                2,
                List.of(
                        new Posting(0, false, OptionalInt.empty(), Optional.empty()),
                        new Posting(2, false, OptionalInt.empty(), Optional.empty())),
                OptionalInt.empty());
    }

    /** On the terms screen, with the terms "1" and "2" of {@code id} and the postings of "1". */
    private static App appOnTerms() {
        App app = appWithFields();
        assertThat(app.handleKey(key('3'))).hasValue(loadTerms("id", null));
        assertThat(app.loadingTerms()).isTrue();
        assertThat(app.termsLoaded(loadTerms("id", null), termPage("1", "2"))).hasValue(loadPostings("id", "1"));
        assertThat(app.postingsLoaded(loadPostings("id", "1"), postingsOf())).isEmpty();
        assertThat(app.loadingTerms()).isFalse();
        return app;
    }

    @Test
    void showsTheTermsAndPostingsOfTheFirstField() {
        App app = appOnTerms();

        String screen = render(app);
        assertThat(screen)
                .contains("3 Terms", "id (/ seeks)", "Postings (2)", "/ seek", "⏎ open doc", "e export", "←/→ column");
        // The terms screen has everything it shows.
        app.handleKey(key('1'));
        assertThat(app.handleKey(key('3'))).isEmpty();
    }

    @Test
    void theSeekPromptTakesTheKeys() {
        App app = appOnTerms();
        app.handleKey(key('/'));

        assertThat(app.handleKey(key('q'))).hasValue(loadTerms("id", "q"));
        assertThat(app.handleKey(key('1'))).hasValue(loadTerms("id", "q1"));
        assertThat(render(app)).contains("type to seek");
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(render(app)).contains("3 Terms", "/ seek");
        assertThat(app.handleKey(key('q'))).hasValue(new Action.Quit());
    }

    @Test
    void reopeningKeepsTheFieldOfTheTermsScreen() {
        App app = appWithFields();
        app.handleKey(key('3'));
        app.termsFailed(loadTerms("id", null), "unreadable");
        assertThat(app.handleKey(key(KeyCode.DOWN))).hasValue(loadTerms("body", null));
        app.handleKey(key('R'));

        assertThat(app.reopened(withFields(stats("/data/idx"), field("title", 0), field("id", 1), field("body", 2))))
                .hasValue(loadTerms("body", null));
    }

    @Test
    void openingAnotherIndexOnTheTermsScreenFetchesItsTerms() {
        App app = appOnTerms();

        assertThat(app.opened(withFields(stats("/data/other"), field("title", 0)), List.of()))
                .hasValue(loadTerms("title", null));
    }

    @Test
    void theTermsScreenNeedsAReader() {
        App app = app();
        openedWithoutReader(app);

        assertThat(app.handleKey(key('3'))).isEmpty();
        assertThat(render(app)).contains("Terms: needs a reader", "r read again");
    }

    @Test
    void enterOnATopTermBrowsesItsDocuments() {
        App app = appWithFields();
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(start(1, "id"));
        app.scanStarted(1, 1);
        app.scanProgress(
                1,
                new GetFieldTermScanResult(
                        2,
                        Optional.of(new FieldTerms(
                                "id", 2, List.of(new TopTerm("1", false, 1, 1), new TopTerm("2", false, 1, 1))))));
        app.handleKey(key(KeyCode.TAB));
        app.handleKey(key(KeyCode.DOWN));
        assertThat(render(app)).contains("⏎ term's docs");

        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(loadTerms("id", "2"));
        assertThat(app.termsLoaded(loadTerms("id", "2"), termPage("2"))).hasValue(loadPostings("id", "2"));
        app.postingsLoaded(loadPostings("id", "2"), postingsOf());
        assertThat(render(app)).contains("3 Terms", "id from 2 (/ seeks)", "Postings (2)");
    }

    private static Optional<Action> load(int docId) {
        return Optional.of(new Action.LoadDocument(docId, List.of(), Optional.empty(), Optional.empty()));
    }

    private static GetIndexStatsResult withDocs(GetIndexStatsResult stats, int maxDoc, IndexField... fields) {
        return new GetIndexStatsResult(
                stats.path(),
                stats.directoryImpl(),
                stats.segmentCount(),
                maxDoc,
                maxDoc,
                stats.indexes(),
                List.of(fields));
    }

    /** A document with {@code doc-<docId>} stored in {@code id}. */
    private static GetDocumentResult document(int docId) {
        return document(docId, new StoredValue(StoredType.STRING, "doc-" + docId, false));
    }

    private static GetDocumentResult document(int docId, StoredValue id) {
        return new GetDocumentResult(
                docId,
                false,
                List.of(new DocumentField(
                        "id", OptionalLong.empty(), List.of(id), Optional.empty(), false, Optional.empty())));
    }

    /** An index of three documents with the field {@code id}. */
    private static App appWithDocuments() {
        App app = app();
        app.opened(withDocs(stats("/data/idx"), 3, field("id", 0)), List.of());
        return app;
    }

    @Test
    void switchesToTheDocumentsAndFetchesTheFirst() {
        App app = appWithDocuments();
        assertThat(render(app)).contains("1 Overview");

        assertThat(app.handleKey(key('2'))).isEqualTo(load(0));
        assertThat(app.loadingDocuments()).isTrue();
        app.documentLoaded(document(0));
        assertThat(app.loadingDocuments()).isFalse();
        assertThat(render(app)).contains("#0 of 0–2", "doc-0", "←→ doc", "i go to");

        assertThat(app.handleKey(key(KeyCode.RIGHT))).isEqualTo(load(1));
        app.documentLoaded(document(1));
        // Back and forth fetches nothing new.
        assertThat(app.handleKey(key('1'))).isEmpty();
        assertThat(render(app)).contains("Path       /data/idx");
        assertThat(app.handleKey(key('2'))).isEmpty();
    }

    @Test
    void viewsAValueFetchesItWholeAndCopiesIt() {
        App app = appWithDocuments();
        app.handleKey(key('2'));
        app.documentLoaded(document(0, new StoredValue(StoredType.STRING, "doc-0", false, OptionalLong.of(100_000))));

        assertThat(app.handleKey(key('v'))).hasValue(new Action.LoadStoredValue(0, "id", 0));
        assertThat(render(app)).contains("Esc close   ←/→ value   t text   h hex   J json   d delimited   y copy");
        // The viewer takes q and the screen keys.
        assertThat(app.handleKey(key('1'))).isEmpty();
        app.storedValueLoaded(0, "id", 0, new StoredValue(StoredType.STRING, "doc-0, whole", false));
        assertThat(render(app)).contains("doc-0, whole");
        assertThat(app.handleKey(key('y'))).hasValue(new Action.Copy("doc-0, whole"));
        assertThat(app.handleKey(key('q'))).isEmpty();
        assertThat(render(app)).contains("v view");
        app.storedValueFailed(0, "id", 0, "late");
        assertThat(app.handleKey(key('q'))).hasValue(new Action.Quit());
    }

    @Test
    void openingAnotherIndexOnTheDocumentsScreenFetchesItsFirstDocument() {
        App app = appWithDocuments();
        app.handleKey(key('2'));
        app.documentLoaded(document(0));

        assertThat(app.opened(withDocs(stats("/data/other"), 1), List.of())).isEqualTo(load(0));
        assertThat(app.opened(stats("/data/empty"), List.of())).isEmpty();
    }

    @Test
    void theDocumentPromptTakesTheKeys() {
        App app = appWithDocuments();
        app.handleKey(key('2'));
        app.documentLoaded(document(0));
        app.handleKey(key('i'));

        // Digits go to the prompt, not to the screen switch; Esc closes the prompt, not leia.
        assertThat(app.handleKey(key('1'))).isEmpty();
        assertThat(render(app)).contains("Go to document");
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(render(app)).doesNotContain("Go to document");

        app.handleKey(key('i'));
        app.handleKey(key('2'));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(2));
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.Quit());
    }

    @Test
    void aFailedDocumentShowsTheError() {
        App app = appWithDocuments();
        app.handleKey(key('2'));

        app.documentFailed(0, "Cannot read document 0");

        assertThat(render(app)).contains("Cannot read it: Cannot read document 0");
    }

    @Test
    void theDocumentsScreenSaysWhichIndexADocumentIsIn() {
        App app = app();
        app.opened(multiStats("segments_1"), List.of());
        app.handleKey(key('2'));
        app.documentLoaded(document(0));
        assertThat(render(app)).contains("#0 of 0–4   in a");
        app.handleKey(key(KeyCode.RIGHT));
        app.documentLoaded(document(1));
        app.handleKey(key(KeyCode.RIGHT));
        app.documentLoaded(document(2));
        assertThat(render(app)).contains("#2 of 0–4   in b");
    }

    @Test
    void reopeningKeepsTheDocumentShownWithinTheNewMaxDoc() {
        App app = appWithDocuments();
        app.handleKey(key('2'));
        app.documentLoaded(document(0));
        app.handleKey(key('i'));
        app.handleKey(key('2'));
        app.handleKey(key(KeyCode.ENTER));
        app.documentLoaded(document(2));

        assertThat(app.reopened(withDocs(stats("/data/idx"), 5, field("id", 0))))
                .isEqualTo(load(2));
        assertThat(app.reopened(withDocs(stats("/data/idx"), 2, field("id", 0))))
                .isEqualTo(load(1));
        assertThat(app.reopened(withDocs(stats("/data/idx"), 0, field("id", 0))))
                .isEmpty();
    }

    @Test
    void theDocumentsScreenNeedsAReader() {
        App app = app();
        openedWithoutReader(app);

        assertThat(app.handleKey(key('2'))).isEmpty();
        assertThat(render(app)).contains("Documents: needs a reader", "r read again");
    }

    /** On the terms screen of an index of three documents, with the postings 0 and 2 of the term "1". */
    private static App appOnTermsWithDocuments() {
        App app = appWithDocuments();
        app.handleKey(key('3'));
        app.termsLoaded(loadTerms("id", null), termPage("1", "2"));
        app.postingsLoaded(loadPostings("id", "1"), postingsOf());
        return app;
    }

    @Test
    void opensAPostingOnTheDocumentsScreen() {
        App app = appOnTermsWithDocuments();
        assertThat(render(app)).contains("3 Terms");
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));

        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(0));
        assertThat(app.documentLoaded(document(0))).hasValue(new Action.LoadPositions(0, "id", text("1")));
        app.positionsLoaded(0, "id", text("1"), List.of());
        assertThat(TestScreens.render(app::render, 100, 24))
                .contains("id:1  1 of 2", "[ ] term docs", "no positions indexed");

        assertThat(app.handleKey(key(']'))).isEqualTo(load(2));
        assertThat(app.handleKey(key(']'))).isEmpty();
        // The terms screen has everything it shows.
        assertThat(app.handleKey(key('3'))).isEmpty();
    }

    @Test
    void aDocumentThatLoadsLateDoesNotTakeThePostingsField() {
        App app = appOnTermsWithDocuments();
        // The documents screen asks for its first document, and the terms screen for another one.
        app.handleKey(key('2'));
        app.handleKey(key('3'));
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        assertThat(app.handleKey(key(KeyCode.DOWN))).isEmpty();
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(2));

        assertThat(app.documentLoaded(document(0))).isEmpty();
        assertThat(app.loadingDocuments()).isTrue();
        assertThat(app.documentLoaded(document(2))).hasValue(new Action.LoadPositions(2, "id", text("1")));
        assertThat(render(app)).contains("#2 of 0–2", "id:1  2 of 2");
    }

    @Test
    void enterOnATopTermBrowsesItsDocumentsOnTheDocumentsScreen() {
        App app = appWithDocuments();
        app.handleKey(key(KeyCode.ENTER));
        app.scanStarted(1, 1);
        app.scanProgress(
                1,
                new GetFieldTermScanResult(
                        2,
                        Optional.of(new FieldTerms(
                                "id", 2, List.of(new TopTerm("1", false, 1, 1), new TopTerm("2", false, 1, 1))))));
        app.handleKey(key(KeyCode.TAB));
        app.handleKey(key(KeyCode.DOWN));

        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(loadTerms("id", "2"));
        assertThat(app.termsLoaded(loadTerms("id", "2"), termPage("2"))).hasValue(loadPostings("id", "2"));
        app.postingsLoaded(loadPostings("id", "2"), postingsOf());
        app.handleKey(key(KeyCode.RIGHT));
        assertThat(app.handleKey(key(KeyCode.ENTER))).isEqualTo(load(0));
        assertThat(app.documentLoaded(document(0))).hasValue(new Action.LoadPositions(0, "id", text("2")));
    }

    /** On the documents screen of an index of three documents, with document 0 shown. */
    private static App appShowingADocument() {
        App app = appWithDocuments();
        app.handleKey(key('2'));
        app.documentLoaded(document(0));
        return app;
    }

    @Test
    void rebuildsAFieldAndStopsWhenTheDocumentChanges() {
        App app = appShowingADocument();

        assertThat(app.handleKey(key('r'))).hasValue(new Action.StartReconstruct(0, "id", List.of()));
        assertThat(app.reconstructStarted(0, "id", 7)).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.Reconstruct(7));
        app.reconstructProgress(7, new GetReconstructResult(1024, OptionalLong.of(3000), Optional.empty()));
        // The details panel at its end.
        app.handleKey(key(KeyCode.TAB));
        app.handleKey(key(KeyCode.END));
        assertThat(render(app)).contains("Esc stop rebuild", "rebuilding… 1024 of 3000 terms");
        assertThat(TestScreens.render(app::render, 230, 20)).contains("m similar   r rebuild   E export   T terms");

        // Another document stops it.
        assertThat(app.handleKey(key(KeyCode.RIGHT)))
                .hasValue(new Action.LoadDocument(1, List.of(7L), Optional.empty(), Optional.empty()));
        assertThat(app.polls()).isEmpty();
        app.documentLoaded(document(1));

        // So does Esc, before it quits.
        app.handleKey(key('r'));
        app.reconstructStarted(1, "id", 8);
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.CancelReconstruct(8));
        assertThat(app.polls()).isEmpty();
        app.handleKey(key(KeyCode.END));
        assertThat(render(app)).contains("r rebuilds the terms");
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.Quit());
    }

    @Test
    void aRebuildStoppedBeforeItsJobStartedIsCancelledOnceItHas() {
        App app = appShowingADocument();
        app.handleKey(key('r'));

        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(app.reconstructStarted(0, "id", 7)).hasValue(new Action.CancelReconstruct(7));
        assertThat(app.polls()).isEmpty();
    }

    @Test
    void showsTheRebuiltField() {
        App app = appShowingADocument();
        app.handleKey(key('r'));
        app.reconstructStarted(0, "id", 7);

        app.reconstructProgress(
                7,
                new GetReconstructResult(
                        3,
                        OptionalLong.of(3),
                        Optional.of(new ReconstructResult(
                                List.of(new ReconstructedField(
                                        "id",
                                        IndexOptions.DOCS,
                                        ReconstructSource.POSTINGS,
                                        List.of(new TermVectorTerm("doc-0", false, 1, List.of())),
                                        List.of())),
                                2))));

        assertThat(app.polls()).isEmpty();
        app.handleKey(key(KeyCode.TAB));
        app.handleKey(key(KeyCode.END));
        String screen = render(app);
        assertThat(screen).contains("1 term, from the postings", "  doc-0").doesNotContain("×1");
        // Rebuilt once is enough.
        assertThat(app.handleKey(key('r'))).isEmpty();
    }

    @Test
    void aFilterStepsThroughItsMatchesAndTheTermsScreenReadsThePostingsThroughIt() {
        App app = appShowingADocument();
        app.handleKey(key('1'));

        assertThat(app.startFilter("red", filterParams(false), 0))
                .hasValue(new Action.StartFilter(1, filterParams(false), OptionalLong.empty(), OptionalLong.empty()));
        assertThat(render(app)).contains("2 Documents");
        assertThat(app.filterStarted(1, 4)).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.Filter(1, 4));
        assertThat(render(app)).contains("Esc stop filter", "filter red: finding matches… 0%");
        // Esc stops building it.
        assertThat(app.handleKey(key(KeyCode.ESCAPE)))
                .hasValue(new Action.ClearFilter(OptionalLong.of(4), OptionalLong.empty()));
        assertThat(app.polls()).isEmpty();

        app.startFilter("red", filterParams(false), 2);
        app.filterStarted(2, 5);
        StepFilterParams params = new StepFilterParams(5, 2, true);
        // Once it is built, it starts on its document.
        assertThat(app.filterProgress(2, built(5, 2, 3)))
                .hasValue(new Action.StepFilter(2, params, FilterStep.NEAREST));
        assertThat(app.loadingDocuments()).isTrue();
        assertThat(app.filterStepped(2, params, FilterStep.NEAREST, Optional.of(new FilterMatch(2, 1))))
                .isEqualTo(load(2));
        app.documentLoaded(document(2));
        assertThat(app.loadingDocuments()).isFalse();
        assertThat(render(app)).contains("filter red: 2 of 2", "←→ match   b unfilter");

        // The terms screen reads the postings through it.
        assertThat(app.handleKey(key('3'))).hasValue(loadTerms("id", null));
        Action.LoadPostings marked =
                new Action.LoadPostings("id", text("1"), 0, false, Optional.of(new PostingsFilter(5, false)));
        assertThat(app.termsLoaded(loadTerms("id", null), termPage("1", "2"))).hasValue(marked);
        app.postingsLoaded(marked, postingsOf());
        assertThat(render(app)).contains("b filter only");

        // A document shown some other way is located in it.
        app.handleKey(key(KeyCode.ENTER));
        app.handleKey(key(KeyCode.ENTER));
        assertThat(app.handleKey(key(KeyCode.ENTER)))
                .hasValue(new Action.LoadDocument(
                        0, List.of(), Optional.of(new Documents.Locate(2, 5)), Optional.empty()));
        StepFilterParams located = new StepFilterParams(5, 0, true);
        app.filterLocated(2, located, Optional.of(new FilterMatch(2, 1)));
        app.documentLoaded(document(0));
        assertThat(render(app)).contains("filter red: not a match, 2 in all");

        assertThat(app.handleKey(key('b'))).hasValue(new Action.ClearFilter(OptionalLong.empty(), OptionalLong.of(5)));
        // The terms screen shows all postings again.
        app.handleKey(key('3'));
        assertThat(render(app)).doesNotContain("b filter only");
    }

    @Test
    void aFilterClearedBeforeItsJobStartedIsCancelledOnceItHas() {
        App app = appShowingADocument();
        app.startFilter("red", filterParams(false), 0);

        assertThat(app.handleKey(key('b')))
                .hasValue(new Action.ClearFilter(OptionalLong.empty(), OptionalLong.empty()));
        assertThat(app.filterStarted(1, 4)).hasValue(new Action.ClearFilter(OptionalLong.of(4), OptionalLong.empty()));
        // Nor does what the job finds count.
        assertThat(app.filterProgress(1, built(4, 2, 3))).isEmpty();
        app.filterFailed(1, "too late");
        assertThat(render(app)).doesNotContain("filter red");
    }

    @Test
    void aReopenThatFoundANewCommitBuildsTheFilterAgain() {
        App app = appShowingADocument();
        app.startFilter("red", filterParams(false), 1);
        app.filterStarted(1, 5);
        Optional<Action> step = app.filterProgress(1, built(5, 2, 3));
        StepFilterParams params = new StepFilterParams(5, 1, true);
        assertThat(step).hasValue(new Action.StepFilter(1, params, FilterStep.NEAREST));
        assertThat(app.filterStepped(1, params, FilterStep.NEAREST, Optional.of(new FilterMatch(1, 0))))
                .isEqualTo(load(1));
        app.documentLoaded(document(1));
        assertThat(app.rebuildFilter()).isEmpty();

        assertThat(app.reopened(withDocs(stats("/data/idx"), 3, field("id", 0))))
                .isEqualTo(load(1));

        assertThat(render(app)).contains("Reopened: segments_1; rebuilding");
        assertThat(app.rebuildFilter())
                .hasValue(new Action.StartFilter(2, filterParams(false), OptionalLong.empty(), OptionalLong.empty()));
        // A poll of the old job, which the reopen stopped, changes nothing.
        assertThat(app.filterProgress(1, built(5, 2, 3))).isEmpty();
        app.filterStarted(2, 9);
        assertThat(app.rebuildFilter()).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.Filter(2, 9));
        app.documentLoaded(document(1));
        // Once built, it only locates the document shown.
        assertThat(app.filterProgress(2, built(9, 2, 3)))
                .hasValue(new Action.StepFilter(2, new StepFilterParams(9, 1, true), FilterStep.LOCATE));
    }

    /** The documents screen on document 0, with relative export paths against {@code dir}. */
    private static App appExporting(Path dir) {
        return appShowingADocument().withCwd(dir);
    }

    private static DocumentData data(int docId) {
        return new DocumentData(document(docId), Map.of());
    }

    private static GetReconstructResult rebuiltId(int docId) {
        return new GetReconstructResult(
                3,
                OptionalLong.of(3),
                Optional.of(new ReconstructResult(
                        List.of(new ReconstructedField(
                                "id",
                                IndexOptions.DOCS,
                                ReconstructSource.POSTINGS,
                                List.of(new TermVectorTerm("doc-" + docId, false, 1, List.of())),
                                List.of())),
                        2)));
    }

    private static String wide(App app) {
        return TestScreens.render(app::render, 140, 20);
    }

    @Test
    void exportsADocumentWithItsIndexedTerms(@TempDir Path dir) {
        App app = appExporting(dir);
        assertThat(app.handleKey(key('E'))).isEmpty();
        assertThat(render(app)).contains("Export document 0 as JSON", "Tab terms and points: on");

        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.LoadDocumentExport(1, 0, true));
        assertThat(render(app)).contains("Exporting…");
        assertThat(app.documentExportLoaded(1, data(0))).hasValue(new Action.StartDocumentExport(1, 0));
        assertThat(app.exportStarted(1, 7)).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.DocumentExport(1, 7));
        assertThat(app.documentExportProgress(
                        1, new GetReconstructResult(1024, OptionalLong.of(3000), Optional.empty())))
                .isEmpty();
        assertThat(wide(app)).contains("Exporting doc 0: 1024/3000 terms", "Esc cancel export");
        // One export at a time.
        app.handleKey(key('E'));
        assertThat(wide(app)).contains("An export runs; Esc cancels it").doesNotContain("as JSON");

        Optional<Action> write = app.documentExportProgress(1, rebuiltId(0));
        assertThat(write).containsInstanceOf(Action.WriteExport.class);
        Action.WriteExport written = (Action.WriteExport) write.get();
        assertThat(written.exportId()).isEqualTo(1);
        assertThat(written.docId()).isZero();
        assertThat(written.path()).isEqualTo(dir.resolve("doc-0.json").toString());
        assertThat(written.overwrite()).isFalse();
        assertThat(written.indexDirs()).containsExactly("/data/idx");
        assertThat(written.text()).contains("\"indexedTerms\": true", "\"source\": \"POSTINGS\"");
        assertThat(app.polls()).isEmpty();

        app.documentExported(1, 0, written.path());
        assertThat(render(app)).contains(" Exported document 0 to doc-0.json");
        // A late poll of the finished export changes nothing.
        assertThat(app.documentExportProgress(1, rebuiltId(0))).isEmpty();
    }

    @Test
    void copiesADocumentWithoutItsIndexedTerms(@TempDir Path dir) {
        App app = appExporting(dir);
        app.handleKey(key('E'));
        app.handleKey(key(KeyCode.TAB));
        assertThat(render(app)).contains("Tab terms and points: off");

        assertThat(app.handleKey(ctrl('y'))).hasValue(new Action.LoadDocumentExport(1, 0, true));
        Optional<Action> copy = app.documentExportLoaded(1, data(0));
        assertThat(copy).containsInstanceOf(Action.Copy.class);
        assertThat(((Action.Copy) copy.get()).text()).contains("\"indexedTerms\": false", "\"value\": \"doc-0\"");
        assertThat(render(app)).doesNotContain("Export document").contains(" Copied document 0 as JSON, ");
        assertThat(app.polls()).isEmpty();
    }

    @Test
    void aDocumentExportThatFailsSaysWhy(@TempDir Path dir) {
        App app = appExporting(dir);
        // Without indexed terms, the prompt stays open until the file is written.
        app.handleKey(key('E'));
        app.handleKey(key(KeyCode.TAB));
        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.LoadDocumentExport(1, 0, true));
        assertThat(app.documentExportLoaded(1, data(0))).containsInstanceOf(Action.WriteExport.class);
        app.exportFailed(1, "Cannot write doc-0.json: disk full");
        assertThat(render(app)).contains("Cannot write doc-0.json: disk full");
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(render(app)).doesNotContain("Export document");

        // With them, Esc stops the rebuild.
        app.handleKey(key('E'));
        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.LoadDocumentExport(2, 0, true));
        app.documentExportLoaded(2, data(0));
        app.exportStarted(2, 4);
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.CancelExport(4));
        assertThat(app.polls()).isEmpty();
        assertThat(app.documentExportProgress(2, rebuiltId(0))).isEmpty();

        // A write that fails after the rebuild is a notice.
        app.handleKey(key('E'));
        app.handleKey(key(KeyCode.ENTER));
        app.documentExportLoaded(3, data(0));
        app.exportStarted(3, 5);
        assertThat(app.documentExportProgress(3, rebuiltId(0))).containsInstanceOf(Action.WriteExport.class);
        assertThat(wide(app)).contains("Exporting doc 0: writing…");
        app.exportFailed(3, "Cannot write doc-0.json: disk full");
        assertThat(render(app)).contains("Export failed: Cannot write doc-0.json: disk full");
    }

    @Test
    void aReopenStopsTheExportAndItsJobIsCancelledOnceItStarts(@TempDir Path dir) {
        App app = appExporting(dir);
        app.handleKey(key('E'));
        app.handleKey(key(KeyCode.ENTER));
        assertThat(app.documentExportLoaded(1, data(0))).hasValue(new Action.StartDocumentExport(1, 0));

        assertThat(app.reopened(withDocs(stats("/data/idx"), 3, field("id", 0))))
                .isEqualTo(load(0));
        app.documentLoaded(document(0));

        assertThat(wide(app)).contains("Reopened: segments_1; export stopped").doesNotContain("Export document");
        assertThat(app.exportStarted(1, 9)).hasValue(new Action.CancelExport(9));
        assertThat(app.polls()).isEmpty();
        // The next export is a new one.
        app.handleKey(key('E'));
        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.LoadDocumentExport(2, 0, true));
        app.exportFailed(1, "too late");
        assertThat(render(app)).doesNotContain("too late").contains("Exporting…");
    }

    @Test
    void checksTheIndexOnScreen7() {
        App app = appWithFields();
        assertThat(app.handleKey(key('7'))).isEmpty();
        String screen = render(app);
        assertThat(screen).contains("Lucene's CheckIndex", "⏎ check   l level: 1 checksums");

        app.handleKey(key('l'));
        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.StartCheck(1, OptionalInt.of(2)));
        assertThat(app.checking()).isTrue();
        assertThat(app.polls()).isEmpty();
        assertThat(app.checkStarted(1, 5)).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.Check(1, 5, 0));
        app.checkProgress(
                1, new GetCheckIndexResult(2, List.of("Checking /data/idx", "1 of 1: name=_0"), Optional.empty()));
        assertThat(app.polls()).containsExactly(new Poll.Check(1, 5, 2));
        assertThat(render(app)).contains("Esc cancel check");

        // Esc cancels the check rather than quitting.
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.CancelCheck(5));
        assertThat(app.polls()).isEmpty();
        assertThat(render(app)).contains("Cancelled.");
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.Quit());
    }

    @Test
    void escBeforeTheCheckStartsStopsItAndItsJobIsCancelledOnceItStarts() {
        App app = appWithFields();
        app.handleKey(key('7'));
        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.StartCheck(1, OptionalInt.of(1)));
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(app.checking()).isFalse();
        assertThat(app.checkStarted(1, 8)).hasValue(new Action.CancelCheck(8));
        assertThat(app.polls()).isEmpty();
        // A failure of the stopped check doesn't show.
        app.checkFailed(1, "too late");
        assertThat(render(app)).contains("Cancelled.").doesNotContain("too late");
    }

    @Test
    void checksWithoutAReaderAndStopsOnReopen() {
        App app = app();
        openedWithoutReader(app);
        app.handleKey(key('1'));
        assertThat(render(app)).contains("only the commits (4) and check (7) work");
        app.handleKey(key('7'));
        assertThat(app.handleKey(key(KeyCode.ENTER))).hasValue(new Action.StartCheck(1, OptionalInt.of(1)));
        app.checkStarted(1, 1);

        // Opening the path again keeps the screen, with a new check.
        openedWithoutReader(app);
        assertThat(app.polls()).isEmpty();
        assertThat(render(app)).contains("Lucene's CheckIndex");
        // The job of the old check is cancelled if it starts late.
        assertThat(app.checkStarted(1, 2)).hasValue(new Action.CancelCheck(2));

        app = appWithFields();
        app.handleKey(key('7'));
        app.handleKey(key(KeyCode.ENTER));
        app.checkStarted(1, 2);
        app.reopened(stats("/data/idx"));
        assertThat(app.polls()).isEmpty();
        assertThat(render(app)).contains("Stopped: the index was reopened.");

        // Closed, there is no check screen.
        app.closed();
        app.handleKey(key('7'));
        assertThat(render(app)).doesNotContain("CheckIndex");
    }

    @Test
    void exportsTheTermsOfTheSelectedField(@TempDir Path dir) {
        App app = appOnTerms().withCwd(dir);
        assertThat(app.handleKey(key('e'))).isEmpty();
        assertThat(render(app)).contains("Export the terms of id", "Tab delimiter: tab");
        app.handleKey(ctrl('u'));
        typed(app, "ids.out");

        assertThat(app.handleKey(key(KeyCode.ENTER)))
                .hasValue(new Action.StartExportTerms(
                        1,
                        new StartExportTermsParams(
                                "id",
                                dir.resolve("ids.out").toString(),
                                Optional.of(TermDelimiter.TAB),
                                Optional.of(false))));
        assertThat(app.exporting()).isTrue();
        assertThat(render(app)).contains("Exporting…");
        assertThat(app.exportStarted(1, 9)).isEmpty();
        assertThat(app.polls()).containsExactly(new Poll.ExportTerms(1, 9));
        app.termsExportProgress(1, new GetExportTermsResult(1024, Optional.empty()));
        assertThat(wide(app)).contains("Exporting id: 1024 terms", "Esc cancel export");
        // One export at a time.
        app.handleKey(key('e'));
        assertThat(wide(app)).contains("An export runs; Esc cancels it").doesNotContain("Export the terms");

        app.termsExportProgress(
                1,
                new GetExportTermsResult(
                        2,
                        Optional.of(new ExportTermsResult(dir.resolve("ids.out").toString(), 2, 6))));
        assertThat(app.polls()).isEmpty();
        assertThat(app.exporting()).isFalse();
        assertThat(render(app)).contains(" Exported 2 terms to ids.out");
        // Until the next key.
        app.handleKey(key(KeyCode.DOWN));
        assertThat(render(app)).doesNotContain("Exported");
    }

    @Test
    void aTermsExportThatCantStartSaysWhyInItsPrompt(@TempDir Path dir) {
        App app = appOnTerms().withCwd(dir);
        app.handleKey(key('e'));
        assertThat(app.handleKey(key(KeyCode.ENTER))).containsInstanceOf(Action.StartExportTerms.class);
        app.exportFailed(1, "Not writing into the index directory " + dir);
        assertThat(wide(app)).contains("Not writing into the index directory");
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(render(app)).doesNotContain("Export the terms");
    }

    @Test
    void escCancelsARunningTermsExportAndAFailureIsShown(@TempDir Path dir) {
        App app = appOnTerms().withCwd(dir);
        app.handleKey(key('e'));
        app.handleKey(key(KeyCode.ENTER));
        app.exportStarted(1, 4);
        assertThat(app.handleKey(key(KeyCode.ESCAPE))).hasValue(new Action.CancelExport(4));
        assertThat(app.polls()).isEmpty();
        // A late poll changes nothing.
        app.termsExportProgress(1, new GetExportTermsResult(5, Optional.empty()));
        assertThat(app.exporting()).isFalse();

        app.handleKey(key('e'));
        app.handleKey(key(KeyCode.ENTER));
        app.exportStarted(2, 5);
        app.exportFailed(2, "Cannot write x: disk full");
        assertThat(app.polls()).isEmpty();
        assertThat(render(app)).contains("Export failed: Cannot write x: disk full");

        // A job that starts for an export that a close forgot is cancelled.
        app.handleKey(key('e'));
        app.handleKey(key(KeyCode.ENTER));
        app.closed();
        assertThat(app.exportStarted(3, 6)).hasValue(new Action.CancelExport(6));
    }

    private static final JarComponents NO_COMPONENTS =
            new JarComponents(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    private static AddJarsResult jarsResult(String... jars) {
        return new AddJarsResult(List.of(jars), List.of(), NO_COMPONENTS);
    }

    private static final GetAnalysisComponentsResult COMPONENTS =
            new GetAnalysisComponentsResult(List.of(), List.of(), List.of(), List.of());

    @Test
    void xOpensTheJarsPopupFromAnyScreenAndAddsJars() {
        Path home = Path.of("/home/me");
        Path dir = home.resolve(".config/leia/jars");
        App app = app(Optional.of(home)).withDefaultJars(new DefaultJars(Optional.of(dir), DefaultJars.State.LOADED));
        assertThat(render(app)).contains("X jars", "or X to add jars");
        assertThat(app.handleKey(key('6'))).hasValue(new Action.LoadAnalysisComponents());
        app.componentsLoaded(COMPONENTS);

        assertThat(app.handleKey(key('X'))).hasValue(new Action.LoadJars());
        assertThat(app.jarsWaiting()).isTrue();
        app.jarsLoaded(List.of(dir.resolve("mine.jar").toString()));
        assertThat(render(app)).contains("mine.jar  default folder");
        assertThat(app.jarsWaiting()).isFalse();
        // The popup takes the keys.
        assertThat(app.handleKey(key('q'))).isEmpty();
        app.handleKey(key(KeyCode.BACKSPACE));
        "~/kuromoji".chars().forEach(c -> app.handleKey(key((char) c)));

        assertThat(app.handleKey(key(KeyCode.ENTER)))
                .hasValue(new Action.AddJars(
                        new AddJarsParams(List.of(home.resolve("kuromoji").toString()))));
        assertThat(app.jarsWaiting()).isTrue();
        // The analysis components are fetched again only if the jars were loaded.
        assertThat(app.jarsAdded(jarsResult())).isEmpty();
        assertThat(app.jarsAdded(jarsResult("/home/me/kuromoji/lucene-analysis-kuromoji.jar")))
                .hasValue(new Action.LoadAnalysisComponents());
        assertThat(app.analyzing()).isTrue();
        app.componentsLoaded(COMPONENTS);
        assertThat(app.analyzing()).isFalse();
        assertThat(render(app)).contains("Loaded: 2 jars");
        app.addJarsFailed("Cannot add the jars: not a jar: /x");
        assertThat(render(app)).contains("not a jar: /x");

        assertThat(app.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(render(app)).doesNotContain("Default folder");
        assertThat(app.handleKey(key('q'))).hasValue(new Action.Quit());
    }

    @Test
    void xOpensTheJarsPopupOverAnIndex() {
        App app = appWithFields();

        assertThat(app.handleKey(key('X'))).hasValue(new Action.LoadJars());

        assertThat(render(app)).contains(" Jars ", "Listing the jars…");
    }

    @Test
    void jarsAddedBeforeTheAnalysisScreenWasShownFetchNothing() {
        App app = app();
        app.handleKey(key('X'));

        assertThat(app.jarsAdded(jarsResult("/a.jar"))).isEmpty();
    }

    @Test
    void repliesForAClosedJarsPopupAreDropped() {
        App app = app();
        app.handleKey(key('X'));
        app.handleKey(key(KeyCode.ESCAPE));

        app.jarsLoaded(List.of("/a.jar"));
        app.jarsFailed("boom");
        app.addJarsFailed("boom");

        assertThat(app.jarsWaiting()).isFalse();
        assertThat(render(app)).doesNotContain("a.jar", "boom");
    }
}
