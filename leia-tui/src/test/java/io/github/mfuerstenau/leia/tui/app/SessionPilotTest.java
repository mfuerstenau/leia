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
                || app.searching();
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
        assertThat(screen()).contains("Export document 0 as JSON", "doc-0.json", "Tab indexed terms: on");
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
        assertThat(screen()).contains("2 hits in", "body=red fox", "Parsed     body:red");

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
        for (int i = 0; i < 6; i++) {
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
