package io.github.mfuerstenau.leia.app;

import io.github.mfuerstenau.leia.client.log.LeiaLog;
import io.github.mfuerstenau.leia.client.platform.DefaultJars;
import io.github.mfuerstenau.leia.client.platform.Platform;
import io.github.mfuerstenau.leia.client.platform.RuntimesDirs;
import io.github.mfuerstenau.leia.client.runtimes.ConsoleChooser;
import io.github.mfuerstenau.leia.client.runtimes.LocalCaches;
import io.github.mfuerstenau.leia.client.runtimes.MirrorChooser;
import io.github.mfuerstenau.leia.client.runtimes.MirrorSettings;
import io.github.mfuerstenau.leia.client.runtimes.Mirrors;
import io.github.mfuerstenau.leia.client.runtimes.RuntimeDownload;
import io.github.mfuerstenau.leia.client.runtimes.RuntimeInstall;
import io.github.mfuerstenau.leia.client.runtimes.RuntimeService;
import io.github.mfuerstenau.leia.mcp.McpServer;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.RuntimesDir;
import io.github.mfuerstenau.leia.server.runtime.ServerRuntimes;
import io.github.mfuerstenau.leia.tui.Tui;
import io.github.mfuerstenau.leia.tui.theme.Theme;
import io.github.mfuerstenau.leia.tui.theme.Themes;
import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        int status = run(args, System.in, System.out, System.err, Platform.current(Main.class));
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
        RuntimesDirs runtimeFolders = new RuntimesDirs(
                platform.runtimesDirs().stream().map(RuntimesDir::path).toList());
        DefaultJars defaultJars = DefaultJars.find(platform.defaultJarsDir(), cli.noDefaultJars());
        List<Path> jars = new ArrayList<>(cli.jars());
        defaultJars.loadedDir().ifPresent(jars::add);

        try (LeiaLog.Opened log = LeiaLog.toFile(platform.logFile())) {
            LOG.info(() -> "leia started with " + List.of(args));
            if (cli.mcp()) {
                return runMcp(cli, platform, runtimeFolders, defaultJars, jars, mcpRoots, log, in, out, err);
            }
            // In the log, which a broken theme of the user's goes to (ADR 0096).
            Themes themes = Tui.themes(platform);
            Optional<Theme> theme = Optional.empty();
            if (cli.theme().isPresent()) {
                theme = themes.find(cli.theme().get());
                if (theme.isEmpty()) {
                    String wanted = cli.theme().get();
                    Optional<String> refused = themes.leftOut().stream()
                            .filter(left -> left.id().equals(wanted))
                            .map(Themes.LeftOut::reason)
                            .findFirst();
                    err.println("leia: "
                            + (refused.isPresent()
                                    ? "theme " + wanted + " left out: " + refused.get()
                                    : Cli.unknownTheme(wanted, themes.ids()).getMessage()));
                    err.println(Cli.USAGE);
                    err.println("For more information, try '--help'.");
                    return 2;
                }
            }
            refreshOwnJars(platform);
            Optional<LeiaBackend> backend = startBackend(cli, platform, runtimeFolders, defaultJars, jars, err);
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
            Cli cli,
            Platform platform,
            RuntimesDirs runtimeFolders,
            DefaultJars defaultJars,
            List<Path> jars,
            PrintStream err) {
        try {
            return Optional.of(
                    LeiaBackend.startWith(platform.runtimesDirs(), jars, cli.servers(), platform.cacheDir()));
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
            RuntimesDirs runtimeFolders,
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
            Optional<LeiaBackend> backend = startBackend(cli, platform, runtimeFolders, defaultJars, jars, err);
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
        // The user's folders only, and only those that exist and are writable: a system folder is
        // another leia's too, and rewriting it would break that one (ADR 0122).
        for (Path dir : platform.userRuntimesDirs()) {
            if (Files.isDirectory(dir) && Files.isWritable(dir)) {
                // Downloads nothing, so the mirrors don't matter.
                new RuntimeInstall(dir, Mirrors.builtIn(), Main.class.getClassLoader())
                        .refreshOwnJars(RuntimeInstall.Listener.logging(LOG));
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
        // A bad mirror is a wrong command line, or as good as one (ADR 0124).
        Mirrors.Loaded mirrors = mirrors(platform, cli.downloadUrls());
        if (!mirrors.problems().isEmpty()) {
            mirrors.problems().forEach(problem -> err.println("leia: " + problem));
            return 2;
        }
        Optional<Path> runtimesDir = platform.runtimesInstallDir();
        if (platform.runtimesDirs().isEmpty()) {
            err.println("leia: --download-runtimes installs into the folder runtimes, which leia has only when it"
                    + " runs from leia.jar");
            return 1;
        }
        if (runtimesDir.isEmpty()) {
            err.println("leia: --download-runtimes finds no runtimes folder it can write to: "
                    + platform.userRuntimesDirs());
            return 1;
        }
        // Asks which mirror to retry from only on a terminal; a script gets the next one (ADR 0124).
        Console console = System.console();
        MirrorChooser chooser = console != null && console.isTerminal()
                ? new ConsoleChooser(new BufferedReader(console.reader()), out)
                : MirrorChooser.fallback();
        return RuntimeDownload.run(
                new RuntimeInstall(
                        runtimesDir.get(), mirrors.mirrors(), Main.class.getClassLoader(), localCaches(platform)),
                cli.runtimes(),
                out,
                err,
                chooser);
    }

    /**
     * The local Maven and Gradle caches that a Lucene jar is copied from before it is downloaded
     * (ADR 0125); none with {@code -Dleia.download.local=false}.
     */
    private static LocalCaches localCaches(Platform platform) {
        LocalCaches caches = LocalCaches.detect(System::getenv, System::getProperty, platform.home());
        caches.caches()
                .forEach(cache -> LOG.info(() ->
                        "Lucene jars are copied from " + cache.name() + ", " + cache.root() + ", where they match"));
        return caches;
    }

    /**
     * The mirrors to download the runtimes' Lucene jars from, in order (ADR 0124): {@code
     * -Dleia.download.url}, {@code --download-url}, the mirror chosen last, the {@code mirrors}
     * file, then leia's own list.
     */
    private static Mirrors.Loaded mirrors(Platform platform, List<String> downloadUrls) {
        return Mirrors.resolve(
                System.getProperty("leia.download.url"), downloadUrls, platform.mirrorFile(), platform.mirrorsFile());
    }

    /**
     * Runs the TUI until the user quits (ADR 0034): with the runtimes' install of the window of
     * {@code D}, if leia runs from {@code leia.jar} (ADR 0080, ADR 0126).
     *
     * @param theme the theme of {@code --theme}, among {@code themes}
     * @return the exit status: 1 if something unexpected ended it
     */
    // Separate options (ADR 0088)
    @SuppressWarnings("java:S107")
    private static int runTui(
            Cli cli,
            Platform platform,
            RuntimesDirs runtimeFolders,
            DefaultJars defaultJars,
            Themes themes,
            Optional<Theme> theme,
            LeiaBackend backend,
            LeiaLog.Opened leiaLog,
            PrintStream out,
            PrintStream err,
            Instant started)
            throws Exception {
        Mirrors.Loaded mirrors = mirrors(platform, List.of());
        mirrors.problems().forEach(problem -> LOG.warning(() -> "Mirror left out: " + problem));
        MirrorSettings mirrorSettings = new MirrorSettings(mirrors.problems(), platform.mirrorWriteFile());
        Optional<RuntimeService> runtimes = platform.runtimesInstallDir()
                .map(dir -> new RuntimeService(
                        new RuntimeInstall(dir, mirrors.mirrors(), Main.class.getClassLoader(), localCaches(platform)),
                        mirrorSettings));
        return Tui.run(
                new Tui.Start(
                        platform,
                        cli.indexPath(),
                        cli.noReader() ? OpenMode.DIRECTORY_ONLY : OpenMode.READER,
                        cli.directory(),
                        runtimeFolders,
                        defaultJars,
                        themes,
                        theme,
                        backend,
                        leiaLog,
                        runtimes,
                        started),
                out,
                err);
    }
}
