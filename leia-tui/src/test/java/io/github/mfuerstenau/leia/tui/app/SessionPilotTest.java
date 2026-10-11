package io.github.mfuerstenau.leia.tui.app;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.layout.Size;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whole flows through TamboUI's Pilot, against the real backend and indexes on disk. Pilot only
 * dispatches its keys, so {@link #settle} waits until they are handled and no call runs.
 */
@Timeout(60)
class SessionPilotTest {

    private static final int WIDTH = 100;
    private static final int HEIGHT = 30;

    /** Handled after every event dispatched before it. */
    private record Barrier(CountDownLatch handled) implements Event {}

    @TempDir
    Path tmp;

    private Path historyFile;
    private LeiaBackend backend;
    private App app;
    private TuiTestRunner test;
    private Pilot pilot;

    @BeforeEach
    void start() throws Exception {
        backend = LeiaBackend.start(List.of());
        VersionResult version =
                backend.call(Methods.VERSION, new VersionParams()).get();
        historyFile = tmp.resolve("state").resolve("history");
        app = new App(version, Optional.empty(), History.load(historyFile), DirectoryImpl.FS);
        Session session = new Session(app, backend);
        test = TuiTestRunner.runTest(
                (event, runner) -> handle(session, event, runner),
                session::render,
                new Size(WIDTH, HEIGHT),
                // The ticks poll the jobs, as in Main.
                TuiConfig.builder()
                        .backend(new TestBackend(WIDTH, HEIGHT))
                        .tickRate(Duration.ofMillis(100))
                        .build());
        pilot = test.pilot();
    }

    @AfterEach
    void stop() {
        test.close();
        backend.close();
    }

    private static boolean handle(Session session, Event event, TuiRunner runner) {
        if (event instanceof Barrier barrier) {
            barrier.handled().countDown();
            return false;
        }
        return session.handle(event, runner);
    }

    /**
     * Waits until the events dispatched so far are handled, and no open, reopen, close, term scan,
     * fetch of commits or fetch of terms runs.
     */
    private void settle() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            Barrier barrier = new Barrier(new CountDownLatch(1));
            test.runner().dispatch(barrier);
            assertThat(barrier.handled().await(20, SECONDS))
                    .as("the UI thread handles the events")
                    .isTrue();
        } while (running() && System.nanoTime() < deadline);
        assertThat(running()).as("the calls are done").isFalse();
    }

    private boolean running() {
        return app.busy() || app.scanning() || app.loadingCommits() || app.loadingTerms();
    }

    private String screen() {
        return TestScreens.render(app::render, WIDTH, HEIGHT);
    }

    /** Opens {@code path} as the command line does. */
    private void openAtStart(Path path, OpenMode mode) throws InterruptedException {
        test.runner().dispatch(new Session.Reply(app -> {
            app.opening(path.toString(), mode);
            return Optional.of(new Action.Open(path.toString(), app.directory(), mode));
        }));
        settle();
    }

    /** Waits until the events dispatched so far are handled. */
    private void handled() throws InterruptedException {
        Barrier barrier = new Barrier(new CountDownLatch(1));
        test.runner().dispatch(barrier);
        assertThat(barrier.handled().await(20, SECONDS)).isTrue();
    }

    private static Path writeIndex(Path dir, int docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            int start = writer.getDocStats().maxDoc;
            for (int i = start; i < start + docs; i++) {
                Document document = new Document();
                document.add(new StringField("id", "doc-" + i, Field.Store.YES));
                writer.addDocument(document);
            }
        }
        return dir;
    }

    @Test
    void opensThePathOfTheCommandLineAndRemembersIt() throws Exception {
        Path index = writeIndex(tmp.resolve("index"), 2);

        openAtStart(index, OpenMode.READER);

        assertThat(screen()).contains("Path       " + index, "Documents  2 live, 0 deleted, 2 max", "Fields (1)");
        assertThat(Files.readAllLines(historyFile)).containsExactly(index.toString());
    }

    @Test
    void reopensAfterACommitAndOpensFromThePrompt() throws Exception {
        Path index = writeIndex(tmp.resolve("index"), 2);
        openAtStart(index, OpenMode.READER);

        pilot.press('R');
        settle();
        assertThat(screen()).contains("Unchanged: segments_");

        writeIndex(index, 1);
        pilot.press('R');
        settle();
        assertThat(screen()).contains("Reopened: segments_", "Documents  3 live, 0 deleted, 3 max");

        // The prompt starts from the open index.
        pilot.press('o');
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("Path       " + index).doesNotContain("Open index");
    }

    @Test
    void opensACorruptIndexWithoutAReader() throws Exception {
        Path index = writeIndex(tmp.resolve("broken"), 2);
        try (Stream<Path> files = Files.list(index)) {
            for (Path si : files.filter(file -> file.toString().endsWith(".si")).toList()) {
                Files.delete(si);
            }
        }

        openAtStart(index, OpenMode.READER);
        assertThat(screen()).contains("CORRUPT_INDEX", "^N and Enter open it without a reader.");

        pilot.press('n', KeyModifiers.CTRL);
        pilot.press(KeyCode.ENTER);
        settle();
        // The commit can't be read either, but the commits screen says why.
        assertThat(screen()).contains("no reader", "Commits (0, 1 unreadable", "segments_1 could not be read.");
        pilot.press('1');
        settle();
        assertThat(screen()).contains("Reader     none");
    }

    @Test
    void showsTheCommitsAndReadsThemAgain() throws Exception {
        Path index = writeIndex(tmp.resolve("index"), 2);
        writeIndex(index, 1);
        openAtStart(index, OpenMode.READER);

        pilot.press('4');
        settle();
        assertThat(screen())
                .contains("Commits (1; ● opened, r reads again)", "Segments of segments_2 (2)", "Generation 2");

        pilot.press(KeyCode.ENTER);
        pilot.press(KeyCode.DOWN);
        settle();
        assertThat(screen()).contains("Segment _1", "Document 2 · 0 deleted");

        // A commit written since: the writer deletes the opened one, which the reader keeps open.
        writeIndex(index, 1);
        pilot.press('r');
        settle();
        assertThat(screen()).contains("segments_3", "Segments of segments_3 (3)");
    }

    @Test
    void scansTheTermsOfAField() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);

        pilot.press(KeyCode.ENTER);
        settle();

        assertThat(screen()).contains("Fields (1, 3 terms)", "id: 3 terms, top 3", "doc-0", "doc-2");
    }

    @Test
    void showsTheTermsAndSeeks() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);

        pilot.press('3');
        settle();
        assertThat(screen()).contains("id (/ seeks)", "doc-0", "doc-2", "Postings (1)");

        pilot.press('/');
        for (char c : "doc-2".toCharArray()) {
            pilot.press(c);
        }
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("id from doc-2 (/ seeks)", "doc-2").doesNotContain("doc-0");
    }

    @Test
    void enterOnATopTermShowsItOnTheTermsScreen() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);
        pilot.press(KeyCode.ENTER);
        settle();

        pilot.press(KeyCode.TAB);
        pilot.press(KeyCode.DOWN);
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("id from doc-1 (/ seeks)", "Postings (1)").doesNotContain("doc-0");
    }

    @Test
    void escCancelsTheScans() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);

        // Back to back, unlike Pilot's keys: a scan ends only after a tick and a poll, which come later.
        test.runner().dispatch(TestScreens.key('a'));
        test.runner().dispatch(TestScreens.key(KeyCode.ESCAPE));
        handled();
        assertThat(app.scanning()).isFalse();
        assertThat(test.runner().isRunning()).isTrue();

        // Whatever the cancelled scan still reports back is ignored.
        Thread.sleep(300);
        handled();
        assertThat(screen()).contains("Enter counts the terms").doesNotContain("3 terms");
    }

    @Test
    void closesAndQuits() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 1), OpenMode.READER);

        pilot.press('c');
        settle();
        assertThat(app.hasIndex()).isFalse();
        assertThat(screen()).contains("No index open");

        pilot.press('q');
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (test.runner().isRunning() && System.nanoTime() < deadline) {
            pilot.pause();
        }
        assertThat(test.runner().isRunning()).isFalse();
    }
}
