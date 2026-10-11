package io.github.mfuerstenau.leia.tui.app;

import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

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
                path, docBase, segmentsFile, 1, 1, 0, maxDoc, maxDoc, 10, "10.5.2", Optional.empty(), Map.of());
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

        assertThat(app.handleKey(key('6'))).isEmpty();
        String screen = render(app);
        assertThat(screen).contains("6 Analysis", " q quit   o open");
        // The other screens need an index.
        app.handleKey(key('5'));
        assertThat(render(app)).contains("analysis screen");

        // An index opened now keeps the analysis screen shown.
        app.opened(stats("/data/idx"), List.of());
        assertThat(render(app)).contains("1-7 screen", "analysis screen");
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
        assertThat(render(app)).contains("Unchanged: segments_1");

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
                List.of()));
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

        app.handleKey(key('2'));
        assertThat(render(app)).contains("This screen needs a reader");
        assertThat(app.handleKey(key('R'))).isEmpty();
        assertThat(render(app)).contains("No reader to reopen");
        app.handleKey(key('1'));
        assertThat(render(app)).contains("Reader     none");

        assertThat(app.handleKey(key('c'))).hasValue(new Action.Close());
        app.closed();
        assertThat(app.hasIndex()).isFalse();
    }

    @Test
    void openingWithoutAReaderKeepsTheAnalysisScreen() {
        App app = app();
        app.handleKey(key('6'));
        openedWithoutReader(app);
        assertThat(render(app)).contains("analysis screen");
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
        assertThat(render(app)).contains("search screen");
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
        assertThat(render(app)).contains("failed");

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
        assertThat(screen).contains("3 Terms", "id (/ seeks)", "Postings (2)", "/ seek", "⏎ open doc", "←/→ column");
        // The tools screen isn't ported yet, so e exports nothing.
        assertThat(app.handleKey(key('e'))).isEmpty();
        assertThat(render(app)).contains("Postings (2)");
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
        assertThat(render(app)).contains("This screen needs a reader");
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
        return Optional.of(new Action.LoadDocument(docId, List.of(), OptionalLong.empty()));
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
                List.of(new DocumentField("id", OptionalLong.empty(), List.of(id), Optional.empty(), false)));
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
        assertThat(render(app)).contains("This screen needs a reader");
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
        assertThat(render(app)).contains("id:1  1 of 2", "[ ] term docs", "no positions indexed");

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
}
