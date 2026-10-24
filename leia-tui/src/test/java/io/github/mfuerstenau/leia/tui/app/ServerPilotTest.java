package io.github.mfuerstenau.leia.tui.app;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.layout.Size;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.pilot.TuiTestRunner;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * An index of the fake server for Lucene 9 opened with {@code --server} (ADR 0090), through Pilot as
 * {@code SessionPilotTest} does. A server is loaded once per JVM, so this runs in a JVM of its own,
 * from serverTest.
 */
@Tag("server")
@Timeout(60)
class ServerPilotTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 30;

    /** Handled after every event dispatched before it. */
    private record Barrier(CountDownLatch handled) implements Event {}

    @TempDir
    Path tmp;

    @Test
    void readsTheServersIndexAndNamesTheServer() throws Exception {
        Path server = testFile("leia.test.server9");
        Path index = testFile("leia.test.server9.index");
        try (LeiaBackend backend =
                LeiaBackend.start(Optional.of(testFile("leia.test.runtimes")), List.of(), List.of(server))) {
            App app = new App(
                            backend.call(Methods.VERSION, new VersionParams()).get(),
                            Optional.empty(),
                            History.inMemory(),
                            DirectoryImpl.FS)
                    .withCwd(tmp);
            app.runtimesLoaded(
                    backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams()).get());
            Session session = new Session(app, backend);
            try (TuiTestRunner test = TuiTestRunner.runTest(
                    (event, runner) -> handle(session, event, runner),
                    session::render,
                    new Size(WIDTH, HEIGHT),
                    TuiConfig.builder()
                            .backend(new TestBackend(WIDTH, HEIGHT))
                            .tickRate(Duration.ofMillis(100))
                            .build())) {
                Pilot pilot = test.pilot();
                assertThat(screen(app))
                        .contains("Server: OpenSearch 0.0.0-leiatest, Lucene 9.10.0 (8–9), in " + server);

                test.runner().dispatch(new Session.Reply(opening -> {
                    opening.opening(index.toString(), OpenMode.READER);
                    return Optional.of(new Action.Open(index.toString(), opening.directory(), OpenMode.READER));
                }));
                settle(test, app);
                assertThat(app.hasIndex()).isTrue();
                assertThat(screen(app))
                        .contains("1 Overview", "Lucene 9.x, read with 9.10.0 (OpenSearch 0.0.0-leiatest)")
                        .doesNotContain("MISSING_CODEC");

                pilot.press('c');
                settle(test, app);
                assertThat(app.hasIndex()).isFalse();
            }
        }
    }

    private static boolean handle(Session session, Event event, TuiRunner runner) {
        if (event instanceof Barrier barrier) {
            barrier.handled().countDown();
            return false;
        }
        return session.handle(event, runner);
    }

    /** Waits until the events dispatched so far are handled and the open or close is done. */
    private static void settle(TuiTestRunner test, App app) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            Barrier barrier = new Barrier(new CountDownLatch(1));
            test.runner().dispatch(barrier);
            assertThat(barrier.handled().await(20, SECONDS)).isTrue();
        } while (app.busy() && System.nanoTime() < deadline);
        assertThat(app.busy()).as("the open or close is done").isFalse();
    }

    private static String screen(App app) {
        return TestScreens.render(app::render, WIDTH, HEIGHT);
    }

    private static Path testFile(String property) {
        String path = System.getProperty(property);
        assertThat(path).as(property + ", from serverTest").isNotNull();
        return Path.of(path).toAbsolutePath().normalize();
    }
}
