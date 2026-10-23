package io.github.mfuerstenau.leia.tui;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.tui.app.Action;
import io.github.mfuerstenau.leia.tui.app.App;
import io.github.mfuerstenau.leia.tui.app.History;
import io.github.mfuerstenau.leia.tui.app.Session;
import io.github.mfuerstenau.leia.tui.jars.DefaultJars;
import io.github.mfuerstenau.leia.tui.jars.RuntimeFolders;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeDownload;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeInstall;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts leia, after {@link Launcher} has checked the Java version: parses the command line, loads
 * the jars into the backend and runs the TUI (ADR 0034). Until the TUI takes the terminal, problems
 * go to stderr, and exit with a non-zero status; after that, everything goes into {@code leia.log}
 * (§7).
 *
 * <p>With {@code -Dleia.exitAfterFirstFrame=true} it quits as soon as the first frame is on
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
            out.print(Cli.HELP_TEXT);
            return 0;
        }
        if (cli.version()) {
            return printVersion(out, err);
        }
        if (cli.downloadRuntimes()) {
            return downloadRuntimes(platform, cli, out, err);
        }
        for (Path jar : cli.jars()) {
            if (!Files.exists(jar)) {
                err.println("leia: --jar " + jar + ": no such jar or directory");
                return 1;
            }
        }
        // The runtimes beside leia.jar come first (ADR 0044, ADR 0073); the default folder's jars come
        // after those of --jar, which win a clash of names (ADR 0024).
        RuntimeFolders runtimeFolders = new RuntimeFolders(platform.runtimesDir());
        DefaultJars defaultJars = DefaultJars.find(platform.defaultJarsDir(), cli.noDefaultJars());
        List<Path> jars = new ArrayList<>(cli.jars());
        defaultJars.loadedDir().ifPresent(jars::add);

        try (LeiaLog.Opened log = LeiaLog.toFile(platform.logFile())) {
            LOG.info(() -> "leia started with " + List.of(args));
            LeiaBackend backend;
            try {
                backend = LeiaBackend.start(runtimeFolders.dir(), jars);
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
                return runTui(cli, platform, runtimeFolders, defaultJars, backend, log, out, err, started);
            }
        }
    }

    /**
     * {@code --version}: leia's name, version and build, and the Java it runs on. The jars don't
     * matter to it, so the backend starts without them.
     */
    private static int printVersion(PrintStream out, PrintStream err) throws InterruptedException {
        // Not into leia.log, which would lose the last session's log; only problems show.
        LeiaLog.toStderr(Level.WARNING);
        try (LeiaBackend backend = LeiaBackend.start(Optional.empty(), List.of())) {
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

    /**
     * {@code --download-runtimes --runtime …} (ADR 0073, ADR 0080): an unattended install, before any
     * jar is loaded, so that none of the runtimes' jars is open, which Windows wouldn't let it
     * replace. It asks nothing; the window inside leia (D on the start screen) is where a user chooses.
     */
    private static int downloadRuntimes(Platform platform, Cli cli, PrintStream out, PrintStream err) {
        // Not into leia.log, which would lose the last session's log; only problems show.
        LeiaLog.toStderr(Level.WARNING);
        Optional<Path> runtimesDir = platform.runtimesDir();
        if (runtimesDir.isEmpty()) {
            err.println("leia: --download-runtimes installs into the folder runtimes beside leia.jar, but leia doesn't"
                    + " run from leia.jar");
            return 1;
        }
        return RuntimeDownload.run(
                new RuntimeInstall(runtimesDir.get(), RuntimeInstall.base(), Main.class.getClassLoader()),
                cli.runtimes(),
                out,
                err);
    }

    /**
     * Runs the TUI until the user quits. Opens {@code cli}'s index path, if any, once the first frame
     * is on the screen.
     *
     * @return the exit status: 1 if something unexpected ended it
     */
    private static int runTui(
            Cli cli,
            Platform platform,
            RuntimeFolders runtimeFolders,
            DefaultJars defaultJars,
            LeiaBackend backend,
            LeiaLog.Opened leiaLog,
            PrintStream out,
            PrintStream err,
            Instant started)
            throws Exception {
        boolean exitAfterFirstFrame = Boolean.getBoolean("leia.exitAfterFirstFrame");
        PrintStream log = leiaLog.stream();
        VersionResult version =
                backend.call(Methods.VERSION, new VersionParams()).get();
        History history = platform.historyFile().map(History::load).orElseGet(History::inMemory);
        App app = new App(version, platform.home(), history, cli.directory())
                .withCwd(Path.of("").toAbsolutePath())
                .withDefaultJars(defaultJars)
                .withRuntimeFolders(runtimeFolders)
                .withLog(leiaLog.buffer(), platform.logFile());
        app.runtimesLoaded(
                backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams()).get());
        Session session = new Session(
                app,
                backend,
                platform.runtimesDir()
                        .map(dir -> new RuntimeInstall(dir, RuntimeInstall.base(), Main.class.getClassLoader())));
        OpenMode mode = cli.noReader() ? OpenMode.DIRECTORY_ONLY : OpenMode.READER;
        // The first frame shows the path being opened.
        cli.indexPath().ifPresent(path -> app.opening(path, mode));
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
            // Runs right after the first frame is drawn and flushed.
            runner.runLater(() -> {
                firstFrame[0] = Instant.now();
                if (exitAfterFirstFrame) {
                    runner.quit();
                } else {
                    cli.indexPath()
                            .ifPresent(path ->
                                    runner.dispatch(Session.perform(new Action.Open(path, app.directory(), mode))));
                }
            });
            runner.run(session::handle, session::render);
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
            // A download that runs is cancelled, and leaves no unfinished file behind (ADR 0080).
            session.awaitInstall();
        }
        if (exitAfterFirstFrame) {
            out.printf(
                    "terminal ready %d ms, first frame %d ms%n",
                    Duration.between(started, terminalReady).toMillis(),
                    firstFrame[0] == null
                            ? -1
                            : Duration.between(started, firstFrame[0]).toMillis());
        }
        if (session.failure().isPresent()) {
            err.println("leia: internal error: " + session.failure().get() + " (see leia.log)");
            return 1;
        }
        return 0;
    }

    /**
     * Panama, and JLine if Panama can't create a terminal (ADR 0034 §1). TamboUI tries the providers
     * of a comma-separated {@code tamboui.backend} in order. An explicit {@code -Dtamboui.backend}
     * or {@code TAMBOUI_BACKEND} still wins.
     */
    static void selectBackend() {
        String property = System.getProperty("tamboui.backend");
        String env = System.getenv("TAMBOUI_BACKEND");
        if ((property == null || property.isEmpty()) && (env == null || env.isEmpty())) {
            System.setProperty("tamboui.backend", "panama,jline3");
        }
    }
}
