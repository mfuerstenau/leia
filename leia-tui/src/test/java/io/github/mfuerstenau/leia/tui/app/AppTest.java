package io.github.mfuerstenau.leia.tui.app;

import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
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
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
        return new IndexField(
                name,
                number,
                IndexOptions.DOCS,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.of(new TermStats(1, 1, 1)));
    }

    /** An index with the fields {@code id} and {@code body}. */
    private static App appWithFields() {
        App app = app();
        GetIndexStatsResult stats = stats("/data/idx");
        app.opened(
                new GetIndexStatsResult(
                        stats.path(),
                        stats.directoryImpl(),
                        stats.segmentCount(),
                        stats.numDocs(),
                        stats.maxDoc(),
                        stats.indexes(),
                        List.of(field("id", 0), field("body", 1))),
                List.of());
        return app;
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

    private static void openedWithoutReader(App app) {
        app.openedWithoutReader(new OpenIndexResult(
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

        openedWithoutReader(app);
        String screen = render(app);
        assertThat(screen).contains("no reader", "4 Commits", "commits screen").doesNotContain("R reopen");

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
        app.handleKey(key('3'));

        var buffer = TestScreens.buffer(app::render, 100, 20);
        String tabs = TestScreens.text(buffer).lines().findFirst().orElseThrow();
        int terms = tabs.indexOf("3 Terms");
        assertThat(buffer.get(terms, 0).style().addModifiers()).contains(dev.tamboui.style.Modifier.REVERSED);
        assertThat(buffer.get(tabs.indexOf("1 Overview"), 0).style().addModifiers())
                .doesNotContain(dev.tamboui.style.Modifier.REVERSED);
        assertThat(render(app)).contains("terms screen");
    }

    @Test
    void anIndexOpenedAgainIsAddedToTheHistory() {
        History history = History.inMemory();
        App app = new App(VERSION, Optional.empty(), history, DirectoryImpl.FS);

        app.opened(stats("/data/a"), List.of());
        openedWithoutReader(app);

        assertThat(history.paths()).containsExactly("/data/broken", "/data/a");
    }
}
