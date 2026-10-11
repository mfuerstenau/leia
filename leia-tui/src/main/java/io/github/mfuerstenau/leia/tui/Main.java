package io.github.mfuerstenau.leia.tui;

import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.terminal.Backend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.github.mfuerstenau.leia.tui.spike.SpikeApp;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Starts leia's TUI, after {@link Launcher} has checked the Java version. For now it runs the spike
 * of ADR 0034.
 *
 * <p>With {@code -Dleia.spike.exitAfterFirstFrame=true} it quits as soon as the first frame is on
 * the screen and prints the startup times, so that they can be measured without a person.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        // From the launcher, not ProcessHandle's start instant, which is off by hundreds of
        // milliseconds on WSL. The JVM's own start before the launcher (some 40 ms) is not in it.
        // Set by Launcher, which Main cannot see: it is in its own source set.
        String launcherStarted = System.getProperty("leia.launcher.started");
        Instant started =
                launcherStarted == null ? Instant.now() : Instant.ofEpochMilli(Long.parseLong(launcherStarted));
        boolean exitAfterFirstFrame = Boolean.getBoolean("leia.spike.exitAfterFirstFrame");
        selectBackend();
        PrintStream out = System.out;
        PrintStream err = System.err;
        Instant[] firstFrame = new Instant[1];
        Instant terminalReady;
        try (PrintStream log = new PrintStream(new FileOutputStream("leia-spike.log"), true, StandardCharsets.UTF_8)) {
            TuiConfig config = TuiConfig.builder()
                    .tickRate(Duration.ofMillis(100))
                    .errorOutput(log)
                    .build();
            try (TuiRunner runner = TuiRunner.create(config)) {
                terminalReady = Instant.now();
                // The terminal has its own streams now; anything else that prints goes to the log
                // instead of over the screen (ADR 0034 §7).
                System.setOut(log);
                System.setErr(log);
                try (SpikeApp app = new SpikeApp(
                        describe(runner.backend()), Path.of("leia-spike-keys.log"), started, terminalReady)) {
                    // Runs right after the first frame is drawn and flushed.
                    runner.runLater(() -> {
                        firstFrame[0] = Instant.now();
                        if (exitAfterFirstFrame) {
                            runner.quit();
                        } else {
                            runner.dispatch(new SpikeApp.FirstFrame(firstFrame[0]));
                        }
                    });
                    runner.run(app::handle, app::render);
                }
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
        }
        if (exitAfterFirstFrame) {
            out.printf(
                    "terminal ready %d ms, first frame %d ms%n",
                    Duration.between(started, terminalReady).toMillis(),
                    firstFrame[0] == null
                            ? -1
                            : Duration.between(started, firstFrame[0]).toMillis());
        }
    }

    /**
     * Panama, and JLine if Panama can't create a terminal (ADR 0034 §1). TamboUI tries the providers
     * of a comma-separated {@code tamboui.backend} in order. An explicit {@code -Dtamboui.backend}
     * or {@code TAMBOUI_BACKEND} still wins.
     */
    private static void selectBackend() {
        String property = System.getProperty("tamboui.backend");
        String env = System.getenv("TAMBOUI_BACKEND");
        if ((property == null || property.isEmpty()) && (env == null || env.isEmpty())) {
            System.setProperty("tamboui.backend", "panama,jline3");
        }
    }

    private static String describe(Backend backend) {
        if (backend instanceof JLineBackend jline) {
            var terminal = jline.jlineTerminal();
            return "jline3 (" + terminal.getType() + ", " + terminal.getClass().getSimpleName() + ")";
        }
        return backend.getClass().getSimpleName();
    }
}
