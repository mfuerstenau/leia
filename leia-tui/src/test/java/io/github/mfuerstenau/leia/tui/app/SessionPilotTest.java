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
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport.ExportedDocument;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport.ExportedField;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

    /**
     * Handled after every event dispatched before it; it notes whether a call runs then, on the UI
     * thread, since a handler can end one call and start the next.
     */
    private record Barrier(CountDownLatch handled, AtomicBoolean running) implements Event {
        Barrier() {
            this(new CountDownLatch(1), new AtomicBoolean());
        }
    }

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
        app = new App(version, Optional.empty(), History.load(historyFile), DirectoryImpl.FS).withCwd(tmp);
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

    private boolean handle(Session session, Event event, TuiRunner runner) {
        if (event instanceof Barrier barrier) {
            barrier.running().set(running());
            barrier.handled().countDown();
            return false;
        }
        return session.handle(event, runner);
    }

    /**
     * Waits until the events dispatched so far are handled, and no open, reopen, close, term scan,
     * fetch of commits, terms or documents, rebuild, filter build, export or check runs.
     */
    private void settle() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Barrier barrier;
        do {
            barrier = new Barrier();
            test.runner().dispatch(barrier);
            assertThat(barrier.handled().await(20, SECONDS))
                    .as("the UI thread handles the events")
                    .isTrue();
        } while (barrier.running().get() && System.nanoTime() < deadline);
        assertThat(barrier.running().get()).as("the calls are done").isFalse();
    }

    private boolean running() {
        return app.busy()
                || app.scanning()
                || app.loadingCommits()
                || app.loadingTerms()
                || app.loadingDocuments()
                || app.documentJobs()
                || app.exporting()
                || app.checking()
                || app.analyzing()
                || app.searching()
                || app.jarsWaiting();
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
        Barrier barrier = new Barrier();
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

    /** An index written by Lucene 2.4, which the relocated Lucene 3.6 reads (ADR 0044). */
    @Test
    void opensALucene24IndexWithTheScreensItHas() throws Exception {
        Path index = Path.of(System.getProperty("leia.test.index.lucene24")).toAbsolutePath();

        openAtStart(index, OpenMode.READER);
        assertThat(screen()).contains("Lucene 2.x, read with 3.6", "Documents  2 live, 1 deleted, 3 max");

        pilot.press('2');
        settle();
        assertThat(screen()).contains("document number 1 of the old index");
        pilot.press('3');
        settle();
        assertThat(screen()).contains("Terms").doesNotContain("not for");
        pilot.press('4');
        settle();
        // Read by Lucene 3.6 too, without a codec (ADR 0045).
        assertThat(screen()).contains("segments_2", "Codec").doesNotContain("not for");

        // Lucene 3.6 checks at its one level, so there is no l.
        pilot.press('7');
        settle();
        assertThat(screen()).contains("⏎ check   Tab panel").doesNotContain("l level", "Check (level");
        pilot.press('l');
        settle();
        assertThat(screen()).contains("Check level: not for Lucene 2.x");
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("OK: no problems found in 1 index");

        pilot.press('5');
        pilot.press('/');
        for (char c : "body:number".toCharArray()) {
            pilot.press(c);
        }
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("2 hits", "body=document number 3 of the old index");
        // A key that Lucene 3 lacks is refused before it reaches the backend.
        pilot.press('x');
        settle();
        assertThat(screen()).contains("Explain: not for Lucene 2.x");
    }

    /** An index written by Lucene 5.5, which the relocated Lucene 5.5 reads (ADR 0046). */
    @Test
    void opensALucene5IndexWithDocValuesAndCheckLevels() throws Exception {
        Path index = Path.of(System.getProperty("leia.test.index.lucene5")).toAbsolutePath();

        openAtStart(index, OpenMode.READER);
        assertThat(screen()).contains("Documents  6 live, 1 deleted, 7 max");

        pilot.press('2');
        settle();
        assertThat(screen()).contains("Lucene 5.x, read with 5.5.5", "dv_num", "dv: 1", "dv: b1");

        // Lucene 5.5 checks at the levels, so l is there.
        pilot.press('7');
        settle();
        assertThat(screen()).contains("l level");
        pilot.press('l');
        settle();
        assertThat(screen()).doesNotContain("not for");
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("OK: no problems found in 1 index");
    }

    /** An index written by Lucene 7.7, which the relocated Lucene 7.7 reads with its points (ADR 0048). */
    @Test
    void opensALucene7IndexAndWalksItsPointTree() throws Exception {
        Path index = Path.of(System.getProperty("leia.test.index.lucene7")).toAbsolutePath();

        openAtStart(index, OpenMode.READER);
        assertThat(screen()).contains("Documents  7 live, 1 deleted, 8 max");

        // id, body, tag, then the point fields.
        pilot.press('3');
        settle();
        for (int i = 0; i < 3; i++) {
            pilot.press(KeyCode.DOWN);
            settle();
        }
        assertThat(screen()).contains("pt · 2 segments · 120 points in 8 docs", "▸ _0  60 pts  100 … 414");
        // Into the tree: _0, then its left cell, then that cell's first leaf.
        pilot.press(KeyCode.RIGHT);
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("▾ _0  60 pts", "▸ 32 pts  100 … 302", "▸ 28 pts  302 … 414");
        pilot.press(KeyCode.DOWN);
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("• 16 pts  100 … 201");
        pilot.press(KeyCode.DOWN);
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("Points (16)");
    }

    /**
     * An index created by Lucene 8.x and written to by 9.x, which the relocated Lucene 9.12 reads with
     * the KNN vectors of its 9.x segment (ADR 0049).
     */
    @Test
    void opensALucene8IndexWrittenBy9AndFindsTheNearestVectors() throws Exception {
        Path index = Path.of(System.getProperty("leia.test.index.lucene8to9")).toAbsolutePath();

        openAtStart(index, OpenMode.READER);
        assertThat(screen()).contains("Documents  9 live, 2 deleted, 11 max");

        // Document 8, the first of the 9.x segment, down to its vector.
        pilot.press('2');
        settle();
        pilot.press('i');
        pilot.press('8');
        pilot.press(KeyCode.ENTER);
        settle();
        for (int i = 0; i < 30 && !screen().contains("N finds the nearest"); i++) {
            pilot.press(KeyCode.DOWN);
            settle();
        }
        assertThat(screen()).contains("[1.0, 0.0, 0.0, 0.0] 4d", "N finds the nearest");

        pilot.press('N');
        settle();
        assertThat(screen()).contains("KNN · embedding (4d float, cosine)", "3 hits");
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
    void analyzesATypedTextAndAStoredValue() throws Exception {
        pilot.press('6');
        settle();
        pilot.press('/');
        for (char c : "The Foxes".toCharArray()) {
            pilot.press(c);
        }
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("standard analyzer", "Tokens after StandardAnalyzer · 2", "foxes");

        // b builds a preset; it becomes the current analyzer once the backend took it.
        pilot.press('b');
        pilot.press(' ');
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("EnglishAnalyzer", "fox").doesNotContain("foxes");

        // a on the documents screen analyzes the stored value.
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);
        pilot.press('2');
        settle();
        pilot.press('a');
        settle();
        assertThat(screen()).contains("Text · doc 0 · id", "EnglishAnalyzer", "doc");
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

    @Test
    void stepsThroughTheDocumentsAndViewsAValue() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);

        pilot.press('2');
        settle();
        assertThat(screen()).contains("#0 of 0–2", "doc-0");

        pilot.press(KeyCode.RIGHT);
        settle();
        assertThat(screen()).contains("#1 of 0–2", "doc-1");

        pilot.press('v');
        settle();
        assertThat(screen()).contains("id · 1 of 1 · string · 5 B", "Esc close");
        pilot.press(KeyCode.ESCAPE);
        settle();
        assertThat(screen()).contains("Fields (1, / seeks)", "v view");
        assertThat(test.runner().isRunning()).isTrue();
    }

    @Test
    void opensAPostingAndStepsThroughTheTermsDocuments() throws Exception {
        Path index = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(index);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 0; i < 3; i++) {
                Document document = new Document();
                document.add(new TextField("body", "red and red " + i, Field.Store.YES));
                writer.addDocument(document);
            }
        }
        openAtStart(index, OpenMode.READER);

        pilot.press('3');
        settle();
        // The terms of body: 0, 1, 2, and, red.
        pilot.press(KeyCode.TAB);
        for (int i = 0; i < 4; i++) {
            pilot.press(KeyCode.DOWN);
        }
        settle();
        assertThat(screen()).contains("Postings (3)");
        pilot.press(KeyCode.TAB);
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("#0 of 0–2", "body:red  1 of 3", "red ×2, doc 1 of 3", "  0, 2");

        pilot.press(']');
        settle();
        assertThat(screen()).contains("#1 of 0–2", "body:red  2 of 3");
    }

    /** Writes documents with {@code texts} in the stored and indexed field {@code body}, in a commit of their own. */
    private static Path writeBodies(Path dir, String... texts) throws IOException {
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (String text : texts) {
                Document document = new Document();
                document.add(new TextField("body", text, Field.Store.YES));
                writer.addDocument(document);
            }
        }
        return dir;
    }

    @Test
    void rebuildsTheTermsAFieldWasIndexedWith() throws Exception {
        openAtStart(writeBodies(tmp.resolve("index"), "Red fox and red"), OpenMode.READER);
        pilot.press('2');
        settle();

        pilot.press('r');
        settle();
        pilot.press(KeyCode.TAB);
        pilot.press(KeyCode.END);
        settle();
        assertThat(screen()).contains("Rebuilt     3 terms, from the postings", "  red fox and red", "  red ×2  0, 3");
    }

    @Test
    void exportsTheShownDocumentWithItsIndexedTerms() throws Exception {
        openAtStart(writeBodies(tmp.resolve("index"), "Red fox and red"), OpenMode.READER);
        pilot.press('2');
        settle();

        pilot.press('E');
        settle();
        assertThat(screen()).contains("Export document 0 as JSON", "doc-0.json", "Tab terms and points: on");
        pilot.press(KeyCode.ENTER);
        settle();

        assertThat(screen()).contains("Exported document 0 to doc-0.json").doesNotContain("as JSON");
        ExportedDocument export = DocumentExport.fromJson(Files.readString(tmp.resolve("doc-0.json")));
        assertThat(export.format()).isEqualTo(DocumentExport.FORMAT);
        assertThat(export.index()).isEqualTo(tmp.resolve("index").toString());
        assertThat(export.indexedTerms()).isTrue();
        ExportedField body = export.fields().getFirst();
        assertThat(body.name()).isEqualTo("body");
        assertThat(body.stored()).extracting(StoredValue::value).containsExactly("Red fox and red");
        assertThat(body.indexed().orElseThrow().terms())
                .extracting(TermVectorTerm::term)
                .containsExactly("and", "fox", "red");
    }

    @Test
    void copiesTheDocumentAndWritesItWithoutItsIndexedTerms() throws Exception {
        openAtStart(writeBodies(tmp.resolve("index"), "Red fox"), OpenMode.READER);
        pilot.press('2');
        settle();

        pilot.press('E');
        pilot.press('y', KeyModifiers.CTRL);
        settle();
        assertThat(screen()).contains("Copied document 0 as JSON, ").doesNotContain("Export document");

        pilot.press('E');
        pilot.press(KeyCode.TAB);
        pilot.press(KeyCode.ENTER);
        settle();
        ExportedDocument export = DocumentExport.fromJson(Files.readString(tmp.resolve("doc-0.json")));
        assertThat(export.indexedTerms()).isFalse();
        assertThat(export.fields()).allMatch(field -> field.indexed().isEmpty());

        // A second export to the same file asks first.
        pilot.press('E');
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("File exists: ", "again overwrites it.");
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("Exported document 0 to doc-0.json");
        assertThat(DocumentExport.fromJson(Files.readString(tmp.resolve("doc-0.json")))
                        .indexedTerms())
                .isTrue();
    }

    @Test
    void doesNotExportIntoTheIndexDirectory() throws Exception {
        Path index = writeBodies(tmp.resolve("index"), "Red fox");
        openAtStart(index, OpenMode.READER);
        pilot.press('2');
        settle();
        List<String> files = listFiles(index);

        pilot.press('E');
        pilot.press('u', KeyModifiers.CTRL);
        for (char c : "index/doc-0.json".toCharArray()) {
            pilot.press(c);
        }
        pilot.press(KeyCode.ENTER);
        settle();

        assertThat(screen()).contains("Not writing into the index directory", "Export document 0 as JSON");
        assertThat(listFiles(index)).isEqualTo(files);
    }

    @Test
    void checksTheIndex() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);
        pilot.press('7');
        pilot.press('l');
        pilot.press(KeyCode.ENTER);
        settle();

        assertThat(screen())
                .contains("Check (level 2, integrity)", "OK: no problems found in 1 index", "Output: lines");
    }

    @Test
    void checksACorruptIndexWithoutAReader() throws Exception {
        Path index = writeIndex(tmp.resolve("broken"), 2);
        try (Stream<Path> files = Files.list(index)) {
            for (Path si : files.filter(file -> file.toString().endsWith(".si")).toList()) {
                Files.delete(si);
            }
        }
        openAtStart(index, OpenMode.DIRECTORY_ONLY);
        pilot.press('7');
        pilot.press(KeyCode.ENTER);
        settle();

        assertThat(screen()).contains("BAD: problems in 1 of 1 index", "✗ ");
    }

    @Test
    void exportsTheTermsOfAField() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);
        pilot.press('3');
        settle();

        pilot.press('e');
        pilot.press('u', KeyModifiers.CTRL);
        for (char c : "terms.out".toCharArray()) {
            pilot.press(c);
        }
        pilot.press(KeyCode.TAB);
        pilot.press(KeyCode.ENTER);
        settle();

        assertThat(screen()).contains("Exported 3 terms to terms.out").doesNotContain("Export the terms");
        assertThat(Files.readAllLines(tmp.resolve("terms.out")))
                .hasSize(3)
                .allMatch(line -> line.startsWith("doc-") && line.contains(","));

        // A second export to the same file asks first.
        pilot.press('e');
        pilot.press('u', KeyModifiers.CTRL);
        for (char c : "terms.out".toCharArray()) {
            pilot.press(c);
        }
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("File exists: ", "again overwrites it.");
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("Exported 3 terms to terms.out");
        assertThat(Files.readAllLines(tmp.resolve("terms.out"))).allMatch(line -> line.contains("\t"));
    }

    private void type(String text) {
        text.codePoints().forEach(c -> pilot.press((char) c));
    }

    @Test
    void listsTheJarsAndSaysWhyOneCannotBeAdded() throws Exception {
        pilot.press('X');
        settle();
        assertThat(screen()).contains("Default folder: none (no home directory)", "No jars loaded");

        type(tmp.resolve("missing.jar").toString());
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("Cannot add the jars: no such jar or directory: ", "INVALID_JAR");

        pilot.press(KeyCode.ESCAPE);
        handled();
        assertThat(screen()).doesNotContain(" Jars ").contains("X jars");
    }

    /** Loads the test codec jar, which changes Lucene's global lists: run by jarsTest only. */
    @Test
    @Tag("jars")
    void addsTheJarThatAnIndexNeedsAndOpensIt() throws Exception {
        String index = System.getProperty("leia.test.index.custom-codec");
        String jar = System.getProperty("leia.test.codec.jar");
        assertThat(index).as("the custom-codec index, from jarsTest").isNotNull();

        openAtStart(Path.of(index), OpenMode.READER);
        assertThat(screen()).contains("MISSING_CODEC", "add a jar that has it", "with X, or start leia with --jar");
        pilot.press(KeyCode.ESCAPE);
        pilot.press('X');
        settle();
        assertThat(screen()).contains("No jars loaded");

        type(jar);
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen())
                .contains(
                        "Loaded 1 jar:",
                        "1 codec: LeiaTest",
                        "1 postings format: LeiaTestPostings",
                        "1 doc values format: LeiaTestDocValues",
                        "1 KNN vectors format: LeiaTestKnnVectors",
                        "Loaded: 1 jar",
                        "leia-test-codec.jar");

        // The prompt starts from the path that failed to open.
        pilot.press(KeyCode.ESCAPE);
        pilot.press('o');
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(app.hasIndex()).isTrue();
        assertThat(screen()).contains("1 Overview").doesNotContain("Open index", "MISSING_CODEC");
    }

    private static List<String> listFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(Path::toString).sorted().toList();
        }
    }

    /** Searches for {@code red} on the search screen, and browses its hits with {@code b}. */
    private void startFilter() throws InterruptedException {
        search("red");
        pilot.press('b');
        settle();
    }

    /** Searches for {@code query} on the search screen, and waits for the hits. */
    private void search(String query) throws InterruptedException {
        pilot.press('5');
        pilot.press('/');
        for (char c : query.toCharArray()) {
            pilot.press(c);
        }
        pilot.press(KeyCode.ENTER);
        settle();
    }

    @Test
    void searchesExplainsAndStepsThroughTheHits() throws Exception {
        openAtStart(writeBodies(tmp.resolve("index"), "red fox", "blue fox", "red lamp"), OpenMode.READER);

        search("red");
        assertThat(screen()).contains("2 hits in", "body: red fox", "Parsed     body:red");

        pilot.press('x');
        settle();
        assertThat(screen()).contains("Explanation of doc 0");

        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("#0 of 0–2", "[ ] hits");
        pilot.press(']');
        settle();
        assertThat(screen()).contains("#2 of 0–2", "red lamp");
    }

    @Test
    void highlightsTheHitsAndViewsAValueWithEveryMatch() throws Exception {
        openAtStart(writeBodies(tmp.resolve("index"), "red fox and a red hen", "blue fox"), OpenMode.READER);

        search("red");
        assertThat(screen())
                .contains("1 hit in", "body: red fox and a red hen", "body · offsets from analysis", "v views body[0]");

        pilot.press('v');
        settle();
        assertThat(screen()).contains("2 Documents", "match 1 of 2", "Esc close");
        pilot.press(KeyCode.ESCAPE);
        pilot.press('5');
        pilot.press('H');
        pilot.press('H');
        settle();
        assertThat(screen()).contains("body=red fox and a red hen", "count to 1000 · no highlights");
    }

    @Test
    void slashOnATopTermSearchesForIt() throws Exception {
        openAtStart(writeIndex(tmp.resolve("index"), 3), OpenMode.READER);
        pilot.press(KeyCode.ENTER);
        settle();
        pilot.press(KeyCode.TAB);

        pilot.press('/');
        settle();
        assertThat(screen()).contains("Term · field id", "1 hit in", "id=doc-0");
    }

    @Test
    void mOnADocumentSearchesForDocumentsLikeIt() throws Exception {
        openAtStart(writeBodies(tmp.resolve("index"), "red fox", "red fox", "blue lamp"), OpenMode.READER);
        pilot.press('2');
        settle();

        pilot.press('m');
        settle();
        assertThat(screen()).contains("More like this document · field body", "Parsed");
    }

    @Test
    void aRegexScanFindsValuesAndViewsOne() throws Exception {
        openAtStart(writeBodies(tmp.resolve("index"), "red fox", "blue fox", "red lamp"), OpenMode.READER);
        pilot.press('5');
        for (int i = 0; i < 7; i++) {
            pilot.press('m');
        }
        pilot.press('/');
        for (char c : "re+d".toCharArray()) {
            pilot.press(c);
        }
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("2 values in 2 docs in", "red fox", "red lamp");

        pilot.press('v');
        settle();
        assertThat(screen()).contains("2 Documents", "Esc close");
    }

    @Test
    void stepsThroughTheMatchesOfAFilterAndMarksThePostingsInIt() throws Exception {
        openAtStart(writeBodies(tmp.resolve("index"), "blue 0", "red 1", "blue 2", "red 3"), OpenMode.READER);
        pilot.press('2');
        settle();

        startFilter();
        assertThat(screen()).contains("filter red: 1 of 2", "#1 of 0–3", "←→ match   b unfilter");
        pilot.press(KeyCode.RIGHT);
        settle();
        assertThat(screen()).contains("filter red: 2 of 2", "#3 of 0–3");
        pilot.press(KeyCode.RIGHT);
        settle();
        assertThat(screen()).contains("filter red: 2 of 2 · no next match");

        // The terms of body: 0, 1, 2, 3, blue, red; the postings of 0 are marked.
        pilot.press('3');
        settle();
        assertThat(screen()).contains("Postings (1) · • filter", "b filter only");

        pilot.press('2');
        pilot.press('b');
        settle();
        assertThat(screen()).doesNotContain("filter red").contains("←→ doc");
        pilot.press('3');
        settle();
        assertThat(screen()).doesNotContain("• filter", "b filter only");
    }

    @Test
    void buildsTheFilterAgainAfterAReopen() throws Exception {
        Path index = writeBodies(tmp.resolve("index"), "blue 0", "red 1", "blue 2", "red 3");
        openAtStart(index, OpenMode.READER);
        pilot.press('2');
        settle();
        startFilter();
        pilot.press(KeyCode.RIGHT);
        settle();
        assertThat(screen()).contains("filter red: 2 of 2", "#3 of 0–3");

        writeBodies(index, "red 4");
        pilot.press('R');
        settle();

        // It locates the document shown in the new matches.
        assertThat(screen()).contains("filter red: 2 of 3", "#3 of 0–4");
        pilot.press(KeyCode.RIGHT);
        settle();
        assertThat(screen()).contains("filter red: 3 of 3", "#4 of 0–4");
    }
}
