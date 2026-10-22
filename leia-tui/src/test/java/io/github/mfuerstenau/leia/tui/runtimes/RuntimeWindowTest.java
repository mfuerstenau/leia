package io.github.mfuerstenau.leia.tui.runtimes;

import static io.github.mfuerstenau.leia.tui.runtimes.FakeRepository.known;
import static io.github.mfuerstenau.leia.tui.runtimes.FakeRepository.lucene;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.layout.Size;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dialogue of {@code --download-runtimes} (ADR 0079) through TamboUI's Pilot, against a local
 * repository. Only lucene9 and lucene5 need downloads; the others are installed first.
 */
@Timeout(60)
class RuntimeDialogTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 30;

    /** Handled after every event dispatched before it. */
    private record Barrier(CountDownLatch handled) implements Event {
        Barrier() {
            this(new CountDownLatch(1));
        }
    }

    @TempDir
    Path tmp;

    private FakeRepository repository;
    private Path runtimes;
    private RuntimeInstall install;
    private RuntimeDialog dialog;
    private TuiTestRunner test;
    private Pilot pilot;

    @BeforeEach
    void start() throws Exception {
        repository = new FakeRepository();
        runtimes = tmp.resolve("runtimes");
        install = new RuntimeInstall(runtimes, repository.base(), RuntimeDialogTest.class.getClassLoader());
        RuntimeInstall.Plan all = install.plan(RuntimeFolders.KNOWN);
        assertThat(install.install(all, Set.copyOf(RuntimeFolders.KNOWN), new RuntimeInstall.Listener() {}, () -> false)
                        .status())
                .isZero();
        deleteFolder("lucene9");
        deleteFolder("lucene5");
        repository.requests.set(0);
    }

    private void open(Set<RuntimeFolders.Known> checked) throws Exception {
        dialog = new RuntimeDialog(install, install.plan(RuntimeFolders.KNOWN), checked);
        test = TuiTestRunner.runTest(
                this::handle,
                dialog::render,
                new Size(WIDTH, HEIGHT),
                TuiConfig.builder()
                        .backend(new TestBackend(WIDTH, HEIGHT))
                        .tickRate(Duration.ofMillis(100))
                        .build());
        pilot = test.pilot();
    }

    @AfterEach
    void stop() {
        if (test != null) {
            test.close();
        }
        repository.close();
    }

    private boolean handle(Event event, TuiRunner runner) {
        if (event instanceof Barrier barrier) {
            barrier.handled().countDown();
            return false;
        }
        return dialog.handle(event, runner);
    }

    private void settle() throws InterruptedException {
        Barrier barrier = new Barrier();
        test.runner().dispatch(barrier);
        assertThat(barrier.handled().await(20, SECONDS)).isTrue();
    }

    /** Waits until {@code done}, settling between looks. */
    private void await(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            settle();
        }
        assertThat(done.getAsBoolean()).as("done in time").isTrue();
    }

    private String screen() {
        return TestScreens.render(dialog::render, WIDTH, HEIGHT);
    }

    @Test
    void offersOnlyWhatNeedsDownloadsNoneChecked() throws Exception {
        open(Set.of());
        settle();

        String screen = screen();
        assertThat(screen)
                .contains("[ ] Lucene 9 · indexes of 8 · ")
                .contains("[ ] Lucene 5 · indexes of 4 and 5 · ")
                .doesNotContain("Lucene 7", "Lucene 10 ·", "[x]")
                .contains("Checked: 0 runtimes, 0 jars, 0 B")
                .contains("Maven Central");

        pilot.press(KeyCode.ENTER);
        settle();

        assertThat(screen()).contains("Nothing checked: Space checks a runtime; Esc quits");
        assertThat(test.runner().isRunning()).isTrue();
    }

    @Test
    void checksAndCountsTheRuntimes() throws Exception {
        open(Set.of());
        int lucene9 = lucene(known("lucene9")).size();
        int lucene5 = lucene(known("lucene5")).size();

        pilot.press(' ');
        settle();
        assertThat(screen())
                .contains("[x] Lucene 9")
                .contains("[ ] Lucene 5")
                .contains("Checked: 1 runtime, " + lucene9 + " jars, ");

        pilot.press('a');
        settle();
        assertThat(screen()).contains("[x] Lucene 5").contains("Checked: 2 runtimes, " + (lucene9 + lucene5) + " jars");

        pilot.press('n');
        settle();
        assertThat(screen()).doesNotContain("[x]").contains("Checked: 0 runtimes");
    }

    @Test
    void showsTheDetailsOfTheHighlightedRuntime() throws Exception {
        open(Set.of());
        RuntimeInstall.Download first = install.plan(List.of(known("lucene5")))
                .runtimes()
                .getFirst()
                .downloads()
                .getFirst();

        pilot.press(KeyCode.DOWN);
        settle();

        // Wide enough for a URL and a SHA-256 on a line.
        assertThat(TestScreens.render(dialog::render, 200, HEIGHT))
                .contains("Lucene 5 · runtimes/lucene5")
                .contains("To download into runtimes/lucene5:")
                .contains(first.jar().name())
                .contains(first.uri().toString())
                .contains("SHA-256 " + first.jar().sha256());
    }

    @Test
    void installsTheCheckedRuntimesOnly() throws Exception {
        open(Set.of(known("lucene9")));
        settle();
        assertThat(screen()).contains("[x] Lucene 9", "[ ] Lucene 5");

        pilot.press(KeyCode.ENTER);
        await(() -> screen().contains("Done: "));

        assertThat(screen())
                .contains("lucene9: installed")
                .doesNotContain("lucene5")
                .contains("✓ lucene-core-9.12.3.jar");
        pilot.press(KeyCode.ENTER);
        Optional<RuntimeInstall.Outcome> outcome = dialog.awaitOutcome();
        assertThat(outcome).hasValueSatisfying(done -> assertThat(done.status()).isZero());
        assertThat(runtimes.resolve("lucene9/lucene-core-9.12.3.jar")).isRegularFile();
        assertThat(runtimes.resolve("lucene5")).doesNotExist();
    }

    @Test
    void cancelsADownloadWithEscape() throws Exception {
        open(Set.of(known("lucene9")));
        repository.holdBack();

        pilot.press(KeyCode.ENTER);
        await(() -> repository.requests.get() > 0);
        assertThat(screen()).contains("Downloading ", "of ");
        pilot.press(KeyCode.ESCAPE);
        settle();
        assertThat(screen()).contains("Cancelling…");
        repository.release();
        await(() -> screen().contains("Done: "));

        assertThat(screen()).contains("lucene9: cancelled", "lucene7: installed");
        assertThat(dialog.awaitOutcome())
                .hasValueSatisfying(done -> assertThat(done.results())
                        .containsEntry(known("lucene9"), new RuntimeInstall.Result.Cancelled()));
        try (Stream<Path> files = Files.list(runtimes.resolve("lucene9"))) {
            assertThat(files).noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    void quitsWithoutDownloading() throws Exception {
        open(Set.of(known("lucene9")));

        pilot.press(KeyCode.ESCAPE);
        await(() -> !test.runner().isRunning());

        assertThat(dialog.awaitOutcome()).isEmpty();
        assertThat(repository.requests).hasValue(0);
        assertThat(runtimes.resolve("lucene9")).doesNotExist();
    }

    @Test
    void fitsANarrowTerminal() throws Exception {
        dialog = new RuntimeDialog(install, install.plan(RuntimeFolders.KNOWN), Set.of(known("lucene5")));

        String narrow = TestScreens.render(dialog::render, 60, 24);
        String wide = TestScreens.render(dialog::render, 120, 24);

        // The details go below the list on a narrow terminal, beside it on a wide one.
        List<String> rows = narrow.lines().toList();
        int list = indexOf(rows, "Lucene 9 ·");
        int details = indexOf(rows, "To download into runtimes/lucene9:");
        assertThat(details).isGreaterThan(list);
        List<String> wideRows = wide.lines().toList();
        assertThat(indexOf(wideRows, "To download into runtimes/lucene9:"))
                .isLessThanOrEqualTo(indexOf(wideRows, "Lucene 5 ·"));
        assertThat(narrow).contains("␣ check", "Esc/q quit");
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
