package io.github.mfuerstenau.leia.tui.app;

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
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.jars.RuntimeFolders;
import io.github.mfuerstenau.leia.tui.runtimes.FakeRepository;
import io.github.mfuerstenau.leia.tui.runtimes.Mirror;
import io.github.mfuerstenau.leia.tui.runtimes.MirrorSettings;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeInstall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window of {@code D} through TamboUI's Pilot and the session (ADR 0080): the download from a
 * local repository on a thread of its own, then {@code loadRuntimes} on the real backend.
 */
@Timeout(60)
class RuntimeDownloadPilotTest {

    private static final int WIDTH = 140;
    private static final int HEIGHT = 40;

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
    private LeiaBackend backend;
    private App app;
    private TuiTestRunner test;
    private Pilot pilot;

    @BeforeEach
    void start() throws Exception {
        repository = new FakeRepository();
        runtimes = Files.createDirectories(tmp.resolve("runtimes"));
        backend = LeiaBackend.start(Optional.of(runtimes), List.of());
        VersionResult version =
                backend.call(Methods.VERSION, new VersionParams()).get();
        app = new App(version, Optional.empty(), History.inMemory(), DirectoryImpl.FS)
                .withRuntimeFolders(new RuntimeFolders(Optional.of(runtimes)));
        // As if lucene5 were missing: the other tests of this JVM may have loaded it already.
        app.runtimesLoaded(new GetRuntimesResult(
                List.of(new RuntimeFolder(
                        "lucene5", List.of(4, 5), RuntimeState.MISSING, Optional.empty(), Optional.empty())),
                List.of()));
        RuntimeInstall install =
                new RuntimeInstall(runtimes, repository.base(), RuntimeDownloadPilotTest.class.getClassLoader());
        run(new Session(app, backend, Optional.of(install)));
    }

    /** Runs {@code session} in place of the one that runs. */
    private void run(Session session) throws Exception {
        if (test != null) {
            test.close();
        }
        test = TuiTestRunner.runTest(
                (event, runner) -> handle(session, event, runner),
                session::render,
                new Size(WIDTH, HEIGHT),
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
        repository.close();
    }

    private static boolean handle(Session session, Event event, TuiRunner runner) {
        if (event instanceof Barrier barrier) {
            barrier.handled().countDown();
            return false;
        }
        return session.handle(event, runner);
    }

    /** Waits until the events dispatched so far are handled, and the window shows nothing that moves. */
    private void settle() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            Barrier barrier = new Barrier();
            test.runner().dispatch(barrier);
            assertThat(barrier.handled().await(20, SECONDS)).isTrue();
        } while (app.animating() && System.nanoTime() < deadline);
        assertThat(app.animating()).as("the window is done").isFalse();
    }

    private String screen() {
        return TestScreens.render(app::render, WIDTH, HEIGHT);
    }

    @Test
    void asksForAnotherMirrorWhenADownloadFailsAndSavesIt() throws Exception {
        try (FakeRepository second = new FakeRepository()) {
            repository.served.remove("org/apache/lucene/lucene-core/5.5.5/lucene-core-5.5.5.jar");
            Path saved = tmp.resolve("config/leia/mirror");
            Mirror local = new Mirror(repository.base(), "Local", Mirror.Origin.OPTION);
            Mirror other = new Mirror(second.base(), "Second", Mirror.Origin.OPTION);
            RuntimeInstall install = new RuntimeInstall(
                    runtimes, List.of(local, other), RuntimeDownloadPilotTest.class.getClassLoader());
            run(new Session(app, backend, Optional.of(install), new MirrorSettings(List.of(), Optional.of(saved))));

            pilot.press('D');
            settle();
            pilot.press(' ');
            pilot.press(KeyCode.ENTER);
            settle();

            assertThat(screen())
                    .contains("lucene5/lucene-core-5.5.5.jar from Local failed: HTTP 404")
                    .contains("✗ Local · ");
            pilot.press(KeyCode.ENTER);
            settle();

            assertThat(screen()).contains("lucene5: installed", "downloaded from Second", "✗ Local: HTTP 404");
            assertThat(saved).content().startsWith(second.base() + " Second");
            try (Stream<Path> files = Files.list(runtimes.resolve("lucene5"))) {
                assertThat(files.map(file -> file.getFileName().toString()))
                        .contains("lucene-core-5.5.5.jar")
                        .noneMatch(name -> name.endsWith(".part"));
            }
        }
    }

    @Test
    void escapeAfterAFailedDownloadStopsItAndKeepsNoPart() throws Exception {
        repository.served.remove("org/apache/lucene/lucene-core/5.5.5/lucene-core-5.5.5.jar");

        pilot.press('D');
        settle();
        pilot.press(' ');
        pilot.press(KeyCode.ENTER);
        settle();
        assertThat(screen()).contains("from 127.0.0.1 failed: HTTP 404", "Esc stop");

        pilot.press(KeyCode.ESCAPE);
        settle();

        assertThat(screen()).contains("lucene5: failed: ", "was stopped", "⏎/Esc close");
        try (Stream<Path> files = Files.list(runtimes.resolve("lucene5"))) {
            assertThat(files).noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    void downloadsTheCheckedRuntimeAndLoadsIt() throws Exception {
        pilot.press('D');
        settle();
        assertThat(screen()).contains(" Download runtimes ", "[ ] Lucene 5 · indexes of 4 and 5");

        pilot.press(' ');
        pilot.press(KeyCode.ENTER);
        settle();

        assertThat(screen()).contains("lucene5: installed", "✓ lucene-core-5.5.5.jar", "⏎/Esc close");
        assertThat(repository.requests.get()).isPositive();
        try (Stream<Path> files = Files.list(runtimes.resolve("lucene5"))) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .contains("leia-lucene5.jar", "lucene-core-5.5.5.jar")
                    .noneMatch(name -> name.endsWith(".part"));
        }
        // The backend was asked to load it, and the about screen shows what getRuntimes says.
        assertThat(screen()).doesNotContain("Loading the new runtimes");
        pilot.press(KeyCode.ESCAPE);
        settle();
        assertThat(screen()).doesNotContain(" Download runtimes ");
        RuntimeFolder lucene5 = backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams()).get().runtimes().stream()
                .filter(runtime -> runtime.folder().equals("lucene5"))
                .findFirst()
                .orElseThrow();
        assertThat(lucene5.state()).isEqualTo(RuntimeState.LOADED);
    }
}
