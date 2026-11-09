package io.github.mfuerstenau.leia.tui.runtimes;

import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.known;
import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.lucene;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.client.runtimes.FakeRepository;
import io.github.mfuerstenau.leia.client.runtimes.InstallJob;
import io.github.mfuerstenau.leia.client.runtimes.LocalCaches;
import io.github.mfuerstenau.leia.client.runtimes.Mirror;
import io.github.mfuerstenau.leia.client.runtimes.Mirrors;
import io.github.mfuerstenau.leia.client.runtimes.RuntimeInstall;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window that downloads runtimes inside leia (ADR 0080), against a local repository. Only
 * lucene9 and lucene5 need downloads; the others are installed first.
 */
@Timeout(60)
class RuntimeWindowTest {

    private static final int WIDTH = 140;
    private static final int HEIGHT = 34;

    @TempDir
    Path tmp;

    private FakeRepository repository;
    private Path runtimes;
    private RuntimeInstall install;
    private RuntimeWindow window;

    @BeforeEach
    void start() throws Exception {
        repository = new FakeRepository();
        runtimes = tmp.resolve("runtimes");
        install = new RuntimeInstall(runtimes, repository.base(), RuntimeWindowTest.class.getClassLoader());
        RuntimeInstall.Plan all = install.plan(RuntimeFolders.KNOWN);
        assertThat(install.install(all, Set.copyOf(RuntimeFolders.KNOWN), new RuntimeInstall.Listener() {}, () -> false)
                        .status())
                .isZero();
        deleteFolder("lucene9");
        deleteFolder("lucene5");
        repository.requests.set(0);
        window = new RuntimeWindow(STATES);
    }

    /** As {@code getRuntimes} has them: lucene9 and lucene5 are missing, the others there. */
    private static final List<RuntimeFolder> STATES = List.of(
            new RuntimeFolder("lucene10", List.of(9, 10), RuntimeState.LOADED, "10.3.1", null),
            new RuntimeFolder("lucene9", List.of(8), RuntimeState.MISSING, null, null),
            new RuntimeFolder("lucene7", List.of(6, 7), RuntimeState.RESTART, null, null),
            new RuntimeFolder("lucene5", List.of(4, 5), RuntimeState.MISSING, null, null),
            new RuntimeFolder("lucene3", List.of(2, 3), RuntimeState.REFUSED, null, "its API is 0"));

    private static final List<RuntimeFolders.Known> MISSING = List.of(known("lucene9"), known("lucene5"));
    private static final List<RuntimeFolders.Known> PRESENT =
            List.of(known("lucene10"), known("lucene7"), known("lucene3"));

    @AfterEach
    void stop() {
        repository.close();
    }

    private void plan(List<RuntimeFolders.Known> runtimes) throws Exception {
        plan(runtimes, List.of());
    }

    /** As leia plans them: {@code present} only to be shown. */
    private void plan(List<RuntimeFolders.Known> runtimes, List<RuntimeFolders.Known> present) throws Exception {
        window.planned(install, install.plan(runtimes), install.plan(present).runtimes());
    }

    private String screen() {
        return screen(WIDTH, HEIGHT);
    }

    private String screen(int width, int height) {
        return TestScreens.render(frame -> window.render(frame, frame.area()), width, height);
    }

    /** Enter, and the install it starts, on a thread of its own as the session runs it. */
    private CompletableFuture<RuntimeInstall.Outcome> enter() {
        RuntimeWindow.Event event = window.handleKey(key(KeyCode.ENTER));
        assertThat(event).isInstanceOf(RuntimeWindow.Install.class);
        return ((RuntimeWindow.Install) event).job().start().done();
    }

    /** Ticks the window, as the app does, until it shows the install's question. */
    private void awaitQuestion() throws InterruptedException {
        await(() -> {
            window.tick();
            return !window.animating();
        });
    }

    @Test
    void waitsForItsPlanInsideItsBorder() {
        String screen = screen();

        assertThat(screen).contains(" Download runtimes ", "Reading runtimes/…", "Esc close");
        assertThat(window.animating()).isTrue();
        // Inside the border: the first row and column belong to the screen behind the window.
        List<String> rows = screen.lines().toList();
        assertThat(rows.getFirst()).isBlank();
        assertThat(rows)
                .filteredOn(row -> row.contains("Reading runtimes/"))
                .singleElement()
                .satisfies(row -> assertThat(row.indexOf('│')).isBetween(1, row.indexOf("Reading") - 1));
        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(RuntimeWindow.Close.class);
    }

    @Test
    void offersOnlyWhatNeedsDownloadsNoneChecked() throws Exception {
        plan(RuntimeFolders.KNOWN);

        assertThat(screen())
                .contains("[ ] Lucene 9 · indexes of 8 · ")
                .contains("[ ] Lucene 5 · indexes of 4 and 5 · ")
                .doesNotContain("[x]", "[ ] Lucene 7")
                .contains("Need no download, and are installed from leia.jar with them: lucene10, lucene7, lucene3")
                .contains("Checked: 0 runtimes, 0 jars, 0 B")
                .contains("Maven Central");
        assertThat(window.animating()).isFalse();
    }

    @Test
    void asksForACheckWhenEveryPlannedRuntimeNeedsADownload() throws Exception {
        plan(List.of(known("lucene9"), known("lucene5")));

        assertThat(window.handleKey(key(KeyCode.ENTER))).isInstanceOf(RuntimeWindow.Nothing.class);

        assertThat(screen()).contains("Nothing checked: Space checks a runtime; Esc closes");
    }

    @Test
    void saysSoWhenNothingIsMissing() throws Exception {
        plan(List.of());

        assertThat(screen()).contains("Every runtime is loaded or complete; there is nothing to download.");
        assertThat(window.handleKey(key(KeyCode.ENTER))).isInstanceOf(RuntimeWindow.Nothing.class);
        assertThat(window.handleKey(key('q'))).isInstanceOf(RuntimeWindow.Close.class);
    }

    @Test
    void showsPresentRuntimesCheckedAndGreyedOut() throws Exception {
        plan(MISSING, PRESENT);

        assertThat(screen())
                .contains("[ ] Lucene 9 · indexes of 8 · ")
                .contains("[ ] Lucene 5 · indexes of 4 and 5 · ")
                .contains("[x] Lucene 10 · indexes of 9 and 10 · loaded")
                .contains("[x] Lucene 7 · indexes of 6 and 7 · loads on restart")
                .contains("[x] Lucene 3 · indexes of 2 and 3 · refused")
                .contains("Checked: 0 runtimes, 0 jars, 0 B")
                .doesNotContain("Need no download");
    }

    @Test
    void cannotChooseAPresentRuntime() throws Exception {
        plan(MISSING, PRESENT);
        window.handleKey(key(KeyCode.DOWN));
        window.handleKey(key(KeyCode.DOWN));

        window.handleKey(key(' '));
        assertThat(screen())
                .contains("Checked: 0 runtimes")
                .contains("lucene10 is loaded; there is nothing to download for it");

        window.handleKey(key('a'));
        assertThat(screen()).contains("Checked: 2 runtimes");

        RuntimeWindow.Event event = window.handleKey(key(KeyCode.ENTER));
        assertThat(event).isInstanceOfSatisfying(RuntimeWindow.Install.class, start -> {
            assertThat(start.job().snapshot().state()).isEqualTo(InstallJob.State.READY);
            assertThat(start.job().snapshot().runtimes())
                    .extracting(InstallJob.RuntimeProgress::known)
                    .containsExactlyElementsOf(MISSING);
        });
    }

    @Test
    void showsTheJarsOfAPresentRuntime() throws Exception {
        plan(MISSING, PRESENT);
        RuntimeFolders.ListedJar jar = lucene(known("lucene7")).getFirst();

        window.handleKey(key(KeyCode.DOWN));
        window.handleKey(key(KeyCode.DOWN));
        assertThat(screen())
                .contains("Loaded (Lucene 10.3.1). Its jars are open")
                .contains("Lucene jars in runtimes/lucene10:");

        window.handleKey(key(KeyCode.DOWN));
        String screen = screen();
        assertThat(screen).contains("Complete; restart leia to load it.").contains("Lucene jars in runtimes/lucene7:");
        String details = detailsColumn(screen, "Lucene 7 · runtimes/lucene7");
        assertThat(details)
                .contains(install.repository().resolve(jar.mavenPath()).toString())
                .contains("SHA-256" + jar.sha256());

        window.handleKey(key(KeyCode.DOWN));
        assertThat(screen()).contains("Refused: its API is 0.");
    }

    @Test
    void listsPresentRuntimesWhenNothingIsMissing() throws Exception {
        plan(List.of(), PRESENT);

        assertThat(screen())
                .contains("Every runtime is loaded or complete; there is nothing to download.")
                .contains("[x] Lucene 10 · indexes of 9 and 10 · loaded")
                .contains("↑↓ move   [/] details   Esc close")
                .doesNotContain("␣ check");
        assertThat(window.handleKey(key(KeyCode.ENTER))).isInstanceOf(RuntimeWindow.Nothing.class);
        assertThat(screen()).contains("Nothing to download; Esc closes");
        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(RuntimeWindow.Close.class);
    }

    @Test
    void checksAndCountsTheRuntimes() throws Exception {
        plan(RuntimeFolders.KNOWN);
        int lucene9 = lucene(known("lucene9")).size();
        int lucene5 = lucene(known("lucene5")).size();

        window.handleKey(key(' '));
        assertThat(screen())
                .contains("[x] Lucene 9")
                .contains("[ ] Lucene 5")
                .contains("Checked: 1 runtime, " + lucene9 + " jars, ");

        window.handleKey(key('a'));
        assertThat(screen()).contains("[x] Lucene 5").contains("Checked: 2 runtimes, " + (lucene9 + lucene5) + " jars");

        window.handleKey(key('n'));
        assertThat(screen()).doesNotContain("[x]").contains("Checked: 0 runtimes");
    }

    @Test
    void showsTheDetailsOfTheHighlightedRuntime() throws Exception {
        plan(RuntimeFolders.KNOWN);
        RuntimeInstall.Download first = install.plan(List.of(known("lucene5")))
                .runtimes()
                .getFirst()
                .downloads()
                .getFirst();

        window.handleKey(key(KeyCode.DOWN));

        String screen = screen();
        assertThat(screen)
                .contains("Lucene 5 · runtimes/lucene5")
                .contains("To download into runtimes/lucene5:")
                .contains(first.jar().name());
        // The window is narrower than a URL and a SHA-256 on a line: they wrap in the details panel.
        String details = detailsColumn(screen, "Lucene 5 · runtimes/lucene5");
        assertThat(details)
                .contains(first.uri().toString())
                .contains("SHA-256" + first.jar().sha256());
    }

    /** The text of the panel titled {@code title}, its rows joined, without spaces or borders. */
    private static String detailsColumn(String screen, String title) {
        List<String> rows = screen.lines().toList();
        int top = indexOf(rows, title);
        // The title follows the corner and a space; the text starts right of the border.
        int column = rows.get(top).indexOf(title) - 1;
        StringBuilder text = new StringBuilder();
        for (String row : rows.subList(top + 1, rows.size())) {
            if (row.length() > column) {
                text.append(row.substring(column).replaceAll("[\\s│┃]", ""));
            }
        }
        return text.toString();
    }

    @Test
    void installsTheCheckedRuntimesThenShowsWhatWasLoaded() throws Exception {
        plan(RuntimeFolders.KNOWN);
        window.handleKey(key(' '));

        RuntimeInstall.Outcome outcome = enter().get();
        assertThat(window.installing()).isTrue();
        assertThat(window.finished(outcome)).isTrue();

        assertThat(outcome.status()).isZero();
        assertThat(screen())
                .contains("lucene9: installed")
                .doesNotContain("lucene5")
                .contains("✓ lucene-backward-codecs-9.12.3.jar")
                .contains("Loading the new runtimes…");
        assertThat(window.animating()).isTrue();
        assertThat(runtimes.resolve("lucene9/lucene-core-9.12.3.jar")).isRegularFile();
        assertThat(runtimes.resolve("lucene5")).doesNotExist();

        window.loaded(new LoadRuntimesResult(
                List.of("lucene9"),
                List.of(
                        new RuntimeFolder("lucene10", List.of(9, 10), RuntimeState.RESTART, null, null),
                        new RuntimeFolder("lucene9", List.of(8), RuntimeState.LOADED, "9.12.3", null))));

        assertThat(screen())
                .contains("Loaded lucene9 (Lucene 9.12.3)")
                .contains("lucene10: restart leia to load it")
                .contains("Open the index again (o) to read it with the new runtime.")
                .contains("⏎/Esc close");
        assertThat(window.animating()).isFalse();
        assertThat(window.handleKey(key(KeyCode.ENTER))).isInstanceOf(RuntimeWindow.Close.class);
    }

    @Test
    void escapeCancelsADownloadAndClosesOnlyAfterIt() throws Exception {
        plan(List.of(known("lucene9")));
        window.handleKey(key(' '));
        repository.holdBack();

        CompletableFuture<RuntimeInstall.Outcome> running = enter();
        await(() -> repository.requests.get() > 0);
        assertThat(screen()).contains("Downloading ", "of ");

        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(RuntimeWindow.Nothing.class);
        assertThat(screen()).contains("Cancelling…");
        repository.release();
        RuntimeInstall.Outcome outcome = running.get();

        assertThat(window.finished(outcome)).isFalse();
        assertThat(screen()).contains("lucene9: cancelled").doesNotContain("Loading");
        assertThat(outcome.results()).containsEntry(known("lucene9"), new RuntimeInstall.Result.Cancelled());
        try (Stream<Path> files = Files.list(runtimes.resolve("lucene9"))) {
            assertThat(files).noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        }
        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(RuntimeWindow.Close.class);
    }

    @Test
    void mChoosesTheMirrorToDownloadFrom() throws Exception {
        Mirror eu = Mirrors.builtIn().get(3);
        install = new RuntimeInstall(
                runtimes,
                List.of(new Mirror(repository.base(), "Local", Mirror.Origin.OPTION), eu),
                RuntimeWindowTest.class.getClassLoader());
        plan(RuntimeFolders.KNOWN);
        // The header wraps: its pieces are checked one by one.
        assertThat(screen()).contains("from Local", "(" + repository.base() + ") (--download-url)", "m mirror");

        assertThat(window.handleKey(key('m'))).isInstanceOf(RuntimeWindow.Nothing.class);
        assertThat(screen())
                .contains(" Mirror to download from ")
                .contains("Local · " + repository.base() + " (--download-url)")
                .contains(eu.name() + " · " + eu.uri())
                .contains("Own URL…")
                .contains("u own URL", "Esc back");
        window.handleKey(key(KeyCode.DOWN));

        assertThat(window.handleKey(key(KeyCode.ENTER))).isEqualTo(new RuntimeWindow.UseMirror(eu));
        assertThat(window.mirror()).hasValue(eu);
        assertThat(screen())
                .contains("from " + eu.name())
                .doesNotContain(repository.base() + "org/apache/lucene/")
                .doesNotContain(" Mirror to download from ");
    }

    @Test
    void refusesAnOwnUrlThatIsNotHttps() throws Exception {
        plan(RuntimeFolders.KNOWN);
        window.handleKey(key('m'));
        window.handleKey(key('u'));
        assertThat(screen()).contains(" Mirror URL ", "https://", "⏎ use");
        window.handleKey(TestScreens.ctrl('u'));
        "http://maven.example.com/".chars().forEach(c -> window.handleKey(key((char) c)));

        assertThat(window.handleKey(key(KeyCode.ENTER))).isInstanceOf(RuntimeWindow.Nothing.class);
        assertThat(screen()).contains("is http, which a proxy may rewrite");

        window.handleKey(TestScreens.ctrl('u'));
        "https://maven.example.com/central".chars().forEach(c -> window.handleKey(key((char) c)));
        assertThat(window.handleKey(key(KeyCode.ENTER)))
                .isEqualTo(new RuntimeWindow.UseMirror(new Mirror(
                        java.net.URI.create("https://maven.example.com/central/"),
                        "maven.example.com",
                        Mirror.Origin.TYPED)));
        assertThat(screen()).contains("from maven.example.com", "(https://maven.example.com/central/) (typed)");
    }

    @Test
    void escapeLeavesTheMirrorsAsTheyWere() throws Exception {
        plan(RuntimeFolders.KNOWN);
        window.handleKey(key('m'));

        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(RuntimeWindow.Nothing.class);
        assertThat(screen()).doesNotContain(" Mirror to download from ").contains("[ ] Lucene 9");
        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(RuntimeWindow.Close.class);
    }

    @Test
    void namesTheMirrorsThatWereLeftOut() throws Exception {
        window.planned(
                install,
                install.plan(RuntimeFolders.KNOWN),
                List.of(),
                List.of("/home/u/.config/leia/mirrors, line 2: 'http://x/' is http"));

        assertThat(screen()).contains("Mirror left out: /home/u/.config/leia/mirrors, line 2: 'http://x/' is http");
    }

    @Test
    void asksWhichMirrorToRetryAFailedDownloadFrom() throws Exception {
        try (FakeRepository second = new FakeRepository()) {
            String path = "org/apache/lucene/lucene-backward-codecs/9.12.3/lucene-backward-codecs-9.12.3.jar";
            repository.served.remove(path);
            Mirror local = new Mirror(repository.base(), "Local", Mirror.Origin.OPTION);
            Mirror other = new Mirror(second.base(), "Second", Mirror.Origin.OPTION);
            install = new RuntimeInstall(runtimes, List.of(local, other), RuntimeWindowTest.class.getClassLoader());
            plan(List.of(known("lucene9")));
            window.handleKey(key(' '));
            CompletableFuture<RuntimeInstall.Outcome> running = enter();
            awaitQuestion();

            assertThat(window.installing()).isTrue();
            assertThat(screen())
                    .contains("lucene9/lucene-backward-codecs-9.12.3.jar from Local failed: HTTP 404")
                    .contains("Esc stops the download")
                    .contains(" Retry lucene-backward-codecs-9.12.3.jar from ")
                    .contains("✗ Local · " + repository.base() + " (--download-url) · HTTP 404")
                    .contains("✗ lucene-backward-codecs-9.12.3.jar")
                    .contains("✗ Local: HTTP 404")
                    .contains("Esc stop");
            // The cursor is on the first mirror that hasn't failed.
            // The install goes on with it, and so does the next one.
            assertThat(window.handleKey(key(KeyCode.ENTER))).isEqualTo(new RuntimeWindow.UseMirror(other));

            RuntimeInstall.Outcome outcome = running.get(30, TimeUnit.SECONDS);
            assertThat(outcome.status()).isZero();
            assertThat(window.finished(outcome)).isTrue();
            assertThat(screen())
                    .contains("lucene9: installed")
                    .contains("lucene-backward-codecs-9.12.3.jar, ")
                    .contains("downloaded from Second")
                    .contains("✗ Local: HTTP 404");
            assertThat(window.gaugeDone()).isEqualTo(window.gaugeTotal());
        }
    }

    @Test
    void keepsTheFailuresOfAJarAcrossItsRetries() throws Exception {
        try (FakeRepository second = new FakeRepository()) {
            String path = "org/apache/lucene/lucene-backward-codecs/9.12.3/lucene-backward-codecs-9.12.3.jar";
            repository.served.remove(path);
            second.served.remove(path);
            Mirror local = new Mirror(repository.base(), "Local", Mirror.Origin.OPTION);
            Mirror other = new Mirror(second.base(), "Second", Mirror.Origin.OPTION);
            install = new RuntimeInstall(runtimes, List.of(local, other), RuntimeWindowTest.class.getClassLoader());
            plan(List.of(known("lucene9")));
            window.handleKey(key(' '));
            CompletableFuture<RuntimeInstall.Outcome> running = enter();
            awaitQuestion();
            assertThat(window.handleKey(key(KeyCode.ENTER))).isEqualTo(new RuntimeWindow.UseMirror(other));

            awaitQuestion();

            assertThat(screen())
                    .contains("lucene9/lucene-backward-codecs-9.12.3.jar from Second failed: HTTP 404")
                    .contains("✗ Local · " + repository.base() + " (--download-url) · HTTP 404")
                    .contains("✗ Second · " + second.base() + " (--download-url) · HTTP 404");
            assertThat(window.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new RuntimeWindow.Nothing());
            RuntimeInstall.Outcome outcome = running.get(30, TimeUnit.SECONDS);
            assertThat(outcome.results().get(known("lucene9"))).isInstanceOf(RuntimeInstall.Result.Failed.class);
        }
    }

    @Test
    void escapeAfterAFailedDownloadStopsIt() throws Exception {
        repository.served.remove("org/apache/lucene/lucene-core/9.12.3/lucene-core-9.12.3.jar");
        plan(List.of(known("lucene9"), known("lucene5")));
        window.handleKey(key('a'));
        CompletableFuture<RuntimeInstall.Outcome> running = enter();
        awaitQuestion();

        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new RuntimeWindow.Nothing());
        assertThat(window.installing()).isTrue();
        RuntimeInstall.Outcome outcome = running.get(30, TimeUnit.SECONDS);
        assertThat(outcome.results().get(known("lucene9"))).isInstanceOf(RuntimeInstall.Result.Failed.class);
        assertThat(outcome.results()).containsEntry(known("lucene5"), new RuntimeInstall.Result.Cancelled());
        window.finished(outcome);
        assertThat(window.installing()).isFalse();
    }

    @Test
    void escapeWhileItDownloadsCancelsIt() throws Exception {
        plan(List.of(known("lucene9")));
        window.handleKey(key(' '));
        repository.holdBack();
        CompletableFuture<RuntimeInstall.Outcome> running = enter();
        await(() -> repository.requests.get() > 0);

        window.handleKey(key(KeyCode.ESCAPE));
        assertThat(screen()).contains("Cancelling…");
        repository.release();

        RuntimeInstall.Outcome outcome = running.get(30, TimeUnit.SECONDS);
        assertThat(outcome.results()).containsEntry(known("lucene9"), new RuntimeInstall.Result.Cancelled());
        assertThat(window.finished(outcome)).isFalse();
        assertThat(screen()).contains("lucene9: cancelled");
    }

    @Test
    void copiesAJarThatALocalCacheHas() throws Exception {
        RuntimeFolders.ListedJar core = lucene(known("lucene9")).stream()
                .filter(jar -> jar.name().equals("lucene-core-9.12.3.jar"))
                .findFirst()
                .orElseThrow();
        Path cached = tmp.resolve("home/.m2/repository").resolve(core.mavenPath());
        Files.createDirectories(cached.getParent());
        Files.copy(FakeRepository.built(known("lucene9")).resolve(core.name()), cached);
        install = new RuntimeInstall(
                runtimes,
                install.mirrors(),
                RuntimeWindowTest.class.getClassLoader(),
                LocalCaches.detect(name -> null, name -> null, tmp.resolve("home")));
        plan(List.of(known("lucene9")));
        RuntimeInstall.RuntimePlan lucene9 =
                install.plan(List.of(known("lucene9"))).runtimes().getFirst();

        assertThat(screen())
                .contains(
                        "[ ] Lucene 9 · indexes of 8 · " + (lucene9.downloads().size() - 1) + " jars, "
                                + io.github.mfuerstenau.leia.tui.text.TextFormat.size(lucene9.downloadSize()))
                .contains("Copied from local caches, not downloaded:")
                .contains("• lucene-core-9.12.3.jar, ", "from Maven's local");

        window.handleKey(key(' '));
        RuntimeInstall.Outcome outcome = enter().get();
        assertThat(window.finished(outcome)).isTrue();

        assertThat(outcome.status()).isZero();
        assertThat(screen()).contains("lucene-core-9.12.3.jar, ", "copied from Maven's local repository");
        assertThat(repository.requests).hasValue(lucene9.downloads().size() - 1);
        assertThat(window.gaugeDone()).isEqualTo(window.gaugeTotal());
    }

    @Test
    void closesWithoutDownloading() throws Exception {
        plan(RuntimeFolders.KNOWN);
        window.handleKey(key(' '));

        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(RuntimeWindow.Close.class);

        assertThat(repository.requests).hasValue(0);
        assertThat(runtimes.resolve("lucene9")).doesNotExist();
    }

    @Test
    void saysWhyThePlanFailed() {
        window.planFailed("leia.jar lacks runtimes/lucene9");

        assertThat(screen()).contains("Cannot plan the download: leia.jar lacks runtimes/lucene9");
        assertThat(window.handleKey(key(KeyCode.ESCAPE))).isInstanceOf(RuntimeWindow.Close.class);
    }

    @Test
    void fitsANarrowTerminal() throws Exception {
        plan(RuntimeFolders.KNOWN);
        window.handleKey(key(KeyCode.DOWN));
        window.handleKey(key(' '));
        window.handleKey(key(KeyCode.UP));

        String narrow = screen(64, 28);
        String wide = screen(130, 28);

        // The details go below the list on a narrow terminal, beside it on a wide one.
        List<String> rows = narrow.lines().toList();
        int list = indexOf(rows, "Lucene 9 ·");
        int details = indexOf(rows, "To download into runtimes/lucene9:");
        assertThat(details).isGreaterThan(list);
        List<String> wideRows = wide.lines().toList();
        assertThat(indexOf(wideRows, "To download into runtimes/lucene9:"))
                .isLessThanOrEqualTo(indexOf(wideRows, "Lucene 5 ·"));
        assertThat(narrow).contains("␣ check", "Esc close");
    }

    /** Waits until {@code done}. */
    private static void await(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(done.getAsBoolean()).as("done in time").isTrue();
    }

    private static int indexOf(List<String> rows, String text) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(text)) {
                return i;
            }
        }
        throw new AssertionError(text + " not in\n" + String.join("\n", rows));
    }

    private void deleteFolder(String folder) throws IOException {
        try (Stream<Path> files = Files.list(runtimes.resolve(folder))) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(runtimes.resolve(folder));
    }
}
