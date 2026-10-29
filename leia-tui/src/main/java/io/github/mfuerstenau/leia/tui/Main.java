package io.github.mfuerstenau.leia.tui;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.github.mfuerstenau.leia.mcp.McpServer;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.ServerRuntimes;
import io.github.mfuerstenau.leia.tui.app.Action;
import io.github.mfuerstenau.leia.tui.app.App;
import io.github.mfuerstenau.leia.tui.app.History;
import io.github.mfuerstenau.leia.tui.app.Session;
import io.github.mfuerstenau.leia.tui.jars.DefaultJars;
import io.github.mfuerstenau.leia.tui.jars.RuntimeFolders;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeDownload;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeInstall;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.theme.Theme;
import io.github.mfuerstenau.leia.tui.theme.ThemeFile;
import io.github.mfuerstenau.leia.tui.theme.Themes;
import java.io.InputStream;
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
 * the jars and servers into the backend and runs the TUI (ADR 0034), or with {@code --mcp} serves
 * the index to an MCP client on stdin and stdout (ADR 0100). Until the TUI takes the terminal, or
 * the MCP loop starts, problems go to stderr, and exit with a non-zero status; after that,
 * everything goes into {@code leia.log} (§7).
 *
 * <p>With {@code -Dleia.exitAfterFirstFrame=true} it quits as soon as the first frame is on
 * the screen and prints the startup times, so that they can be measured without a person.
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {}

    // The CLI's own output (ADR 0088)
    @SuppressWarnings("java:S106")
    public static void main(String[] args) throws Exception {
        int status = run(args, System.in, System.out, System.err, Platform.current());
        if (status != 0) {
            System.exit(status);
        }
    }

    /** As {@link #run(String[], InputStream, PrintStream, PrintStream, Platform)}, with an empty stdin. */
    static int run(String[] args, PrintStream out, PrintStream err, Platform platform) throws Exception {
        return run(args, InputStream.nullInputStream(), out, err, platform);
    }

    /**
     * Runs leia with the command line {@code args}.
     *
     * @param in stdin, which only MCP mode reads (ADR 0100)
     * @return the exit status: 0, 1 for a failure, 2 for a wrong command line
     */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err, Platform platform)
            throws Exception {
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
        for (Path server : cli.servers()) {
            if (!Files.isDirectory(server)) {
                err.println("leia: --server " + server + ": no such directory");
                return 1;
            }
            if (ServerRuntimes.lib(server).isEmpty()) {
                err.println("leia: --server " + server + ": no lucene-core jar in " + server.resolve("lib") + " or "
                        + server + "; give an Elasticsearch or OpenSearch installation, or its lib folder");
                return 1;
            }
        }
        // Real paths, which open_index compares the real path of an index with (ADR 0102).
        List<Path> mcpRoots = new ArrayList<>();
        for (Path root : cli.mcpRoots()) {
            if (!Files.isDirectory(root)) {
                err.println("leia: --mcp-root " + root + ": no such directory");
                return 1;
            }
            mcpRoots.add(root.toRealPath());
        }
        // The runtimes come first (ADR 0044, ADR 0073, ADR 0098); the default folder's jars come
        // after those of --jar, which win a clash of names (ADR 0024).
        RuntimeFolders runtimeFolders = new RuntimeFolders(platform.runtimesDir());
        DefaultJars defaultJars = DefaultJars.find(platform.defaultJarsDir(), cli.noDefaultJars());
        List<Path> jars = new ArrayList<>(cli.jars());
        defaultJars.loadedDir().ifPresent(jars::add);

        try (LeiaLog.Opened log = LeiaLog.toFile(platform.logFile())) {
            LOG.info(() -> "leia started with " + List.of(args));
            if (cli.mcp()) {
                return runMcp(cli, platform, runtimeFolders, defaultJars, jars, mcpRoots, log, in, out, err);
            }
            // In the log, which a broken theme of the user's goes to (ADR 0096).
            Themes themes = Themes.bundled().withUserDir(platform.themesDir());
            Optional<Theme> theme = Optional.empty();
            if (cli.theme().isPresent()) {
                theme = themes.find(cli.theme().get());
                if (theme.isEmpty()) {
                    err.println("leia: "
                            + Cli.unknownTheme(cli.theme().get(), themes.ids()).getMessage());
                    err.println(Cli.USAGE);
                    err.println("For more information, try '--help'.");
                    return 2;
                }
            }
            refreshOwnJars(platform);
            Optional<LeiaBackend> backend = startBackend(cli, runtimeFolders, defaultJars, jars, err);
            if (backend.isEmpty()) {
                return 1;
            }
            try (LeiaBackend running = backend.get()) {
                return runTui(
                        cli, platform, runtimeFolders, defaultJars, themes, theme, running, log, out, err, started);
            }
        }
    }

    /**
     * Starts the backend with the runtimes, the jars and the servers; if that fails, says why on
     * {@code err}.
     */
    private static Optional<LeiaBackend> startBackend(
            Cli cli, RuntimeFolders runtimeFolders, DefaultJars defaultJars, List<Path> jars, PrintStream err) {
        try {
            return Optional.of(LeiaBackend.start(runtimeFolders.dir(), jars, cli.servers()));
        } catch (RpcException e) {
            LOG.log(Level.SEVERE, "Cannot start", e);
            err.println("leia: " + e.getMessage());
            defaultJars
                    .loadedDir()
                    .ifPresent(dir -> err.println("The jars of the default folder " + dir
                            + " are loaded too; --no-default-jars leaves them out."));
            return Optional.empty();
        }
    }

    /**
     * {@code --mcp} (ADR 0100): serves {@code cli}'s index path, if any, to an MCP client on {@code
     * in} and {@code out} until {@code in} ends. stdout carries the protocol only, so {@code
     * System.out} and {@code System.err} go into the log from here on, before anything is loaded;
     * {@code err} is the real stderr, for what fails before the loop starts.
     *
     * @return the exit status: 1 if the backend can't start or the index path can't be opened
     */
    // Separate parts of the start; it keeps the CLI's own streams (ADR 0088)
    @SuppressWarnings({"java:S106", "java:S107"})
    private static int runMcp(
            Cli cli,
            Platform platform,
            RuntimeFolders runtimeFolders,
            DefaultJars defaultJars,
            List<Path> jars,
            List<Path> mcpRoots,
            LeiaLog.Opened log,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws Exception {
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        System.setOut(log.stream());
        System.setErr(log.stream());
        try {
            refreshOwnJars(platform);
            Optional<LeiaBackend> backend = startBackend(cli, runtimeFolders, defaultJars, jars, err);
            if (backend.isEmpty()) {
                return 1;
            }
            try (LeiaBackend started = backend.get()) {
                VersionResult version =
                        started.call(Methods.VERSION, new VersionParams()).get();
                McpServer server =
                        new McpServer(started, version, cli.directory(), platform.home(), mcpRoots, cli.mcpPin());
                if (cli.indexPath().isPresent()) {
                    String path = cli.indexPath().get();
                    try {
                        server.open(path);
                    } catch (RpcException e) {
                        LOG.log(Level.SEVERE, e, () -> "Cannot open " + path);
                        err.println("leia: cannot open " + path + ": " + e.getMessage() + " ("
                                + e.code().name() + ")");
                        return 1;
                    }
                }
                LOG.info("MCP mode: serving on stdin and stdout");
                return server.serve(in, out);
            }
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
    }

    /**
     * Brings leia's own jars in the runtimes folder in line with {@code leia.jar}, before the backend
     * loads them, since the folder outlives an upgrade (ADR 0098). Only from {@code leia.jar}, which
     * embeds them; into the log, as is a failure, whose runtime the backend then refuses as outdated.
     */
    private static void refreshOwnJars(Platform platform) {
        if (platform.leiaJar().isEmpty()) {
            return;
        }
        platform.runtimesDir()
                .map(dir -> new RuntimeInstall(dir, RuntimeInstall.base(), Main.class.getClassLoader()))
                .ifPresent(install -> install.refreshOwnJars(RuntimeInstall.Listener.logging(LOG)));
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
            err.println("leia: --download-runtimes installs into the folder runtimes, which leia has only when it"
                    + " runs from leia.jar");
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
     * @param theme the theme of {@code --theme}, among {@code themes}
     * @return the exit status: 1 if something unexpected ended it
     */
    // Separate options; it keeps the CLI's own streams (ADR 0088)
    @SuppressWarnings({"java:S106", "java:S107"})
    private static int runTui(
            Cli cli,
            Platform platform,
            RuntimeFolders runtimeFolders,
            DefaultJars defaultJars,
            Themes themes,
            Optional<Theme> theme,
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
        ThemeFile themeFile = new ThemeFile(platform.themeFile());
        Palette.use(themeFile.resolve(theme, platform.env().apply("NO_COLOR"), themes));
        App app = new App(version, platform.home(), history, cli.directory())
                .withCwd(Path.of("").toAbsolutePath())
                .withDefaultJars(defaultJars)
                .withThemes(themes, themeFile)
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
        Optional<Throwable> failure = session.failure();
        if (failure.isPresent()) {
            err.println("leia: internal error: " + failure.get() + " (see leia.log)");
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
