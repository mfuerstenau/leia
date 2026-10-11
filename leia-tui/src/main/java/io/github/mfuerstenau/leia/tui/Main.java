package io.github.mfuerstenau.leia.tui;

import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.terminal.Backend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.tui.spike.SpikeApp;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts leia, after {@link Launcher} has checked the Java version: parses the command line, loads
 * the jars into the backend and runs the TUI (ADR 0034). Until the TUI takes the terminal, problems
 * go to stderr, and exit with a non-zero status; after that, everything goes into {@code leia.log}
 * (§7). For now the TUI is the spike of ADR 0034.
 *
 * <p>With {@code -Dleia.spike.exitAfterFirstFrame=true} it quits as soon as the first frame is on
 * the screen and prints the startup times, so that they can be measured without a person.
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) throws Exception {
        int status = run(args, System.out, System.err, Platform.current());
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Runs leia with the command line {@code args}.
     *
     * @return the exit status: 0, 1 for a failure, 2 for a wrong command line
     */
    static int run(String[] args, PrintStream out, PrintStream err, Platform platform) throws Exception {
        // From the launcher, not ProcessHandle's start instant, which is off by hundreds of
        // milliseconds on WSL. The JVM's own start before the launcher (some 40 ms) is not in it.
        // Set by Launcher, which Main cannot see: it is in its own source set.
        String launcherStarted = System.getProperty("leia.launcher.started");
        Instant started =
                launcherStarted == null ? Instant.now() : Instant.ofEpochMilli(Long.parseLong(launcherStarted));
        Cli cli;
        try {
            cli = Cli.parse(args);
        } catch (Cli.UsageException e) {
            err.println("leia: " + e.getMessage());
            err.println(Cli.USAGE);
            err.println("For more information, try '--help'.");
            return 2;
        }
        if (cli.help()) {
            out.print(Cli.HELP);
            return 0;
        }
        if (cli.version()) {
            return printVersion(out, err);
        }
        for (Path jar : cli.jars()) {
            if (!Files.exists(jar)) {
                err.println("leia: --jar " + jar + ": no such jar or directory");
                return 1;
            }
        }
        // The default folder's jars come after those of --jar, which win a clash of names (ADR 0024).
        DefaultJars defaultJars = DefaultJars.find(platform.defaultJarsDir(), cli.noDefaultJars());
        List<Path> jars = new ArrayList<>(cli.jars());
        defaultJars.loadedDir().ifPresent(jars::add);

        try (PrintStream log = LeiaLog.toFile(platform.logFile())) {
            LOG.info(() -> "leia started with " + List.of(args));
            LeiaBackend backend;
            try {
                backend = LeiaBackend.start(jars);
            } catch (RpcException e) {
                LOG.log(Level.SEVERE, "Cannot start", e);
                err.println("leia: " + e.getMessage());
                defaultJars
                        .loadedDir()
                        .ifPresent(dir -> err.println("The jars of the default folder " + dir
                                + " are loaded too; --no-default-jars leaves them out."));
                return 1;
            }
            try (backend) {
                runTui(log, out, started);
            }
        }
        return 0;
    }

    /**
     * {@code --version}: leia's name, version and build, and the Java it runs on. The jars don't
     * matter to it, so the backend starts without them.
     */
    private static int printVersion(PrintStream out, PrintStream err) throws InterruptedException {
        // Not into leia.log, which would lose the last session's log; only problems show.
        LeiaLog.toStderr(Level.WARNING);
        try (LeiaBackend backend = LeiaBackend.start(List.of())) {
            VersionResult version =
                    backend.call(Methods.VERSION, new VersionParams()).get();
            out.println("leia " + version.version() + " (" + version.commit() + ", " + version.commitTime() + ")");
            JavaRuntime java = version.java();
            out.println("Java " + java.version() + " (" + java.vendor() + ") in " + java.home());
            return 0;
        } catch (RpcException | ExecutionException e) {
            err.println("leia: cannot read the version: " + e.getMessage());
            return 1;
        }
    }

    private static void runTui(PrintStream log, PrintStream out, Instant started) throws Exception {
        boolean exitAfterFirstFrame = Boolean.getBoolean("leia.spike.exitAfterFirstFrame");
        selectBackend();
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        Instant[] firstFrame = new Instant[1];
        Instant terminalReady;
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
            try (SpikeApp app =
                    new SpikeApp(describe(runner.backend()), Path.of("leia-spike-keys.log"), started, terminalReady)) {
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
            System.setOut(stdout);
            System.setErr(stderr);
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
