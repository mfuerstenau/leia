package io.github.mfuerstenau.leia.tui;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.github.mfuerstenau.leia.client.history.History;
import io.github.mfuerstenau.leia.client.log.LeiaLog;
import io.github.mfuerstenau.leia.client.platform.DefaultJars;
import io.github.mfuerstenau.leia.client.platform.Platform;
import io.github.mfuerstenau.leia.client.platform.RuntimesDirs;
import io.github.mfuerstenau.leia.client.runtimes.RuntimeService;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.tui.app.Action;
import io.github.mfuerstenau.leia.tui.app.App;
import io.github.mfuerstenau.leia.tui.app.Session;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.theme.Theme;
import io.github.mfuerstenau.leia.tui.theme.ThemeFile;
import io.github.mfuerstenau.leia.tui.theme.Themes;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The TUI (ADR 0034), as leia-app starts it once the backend runs (ADR 0126). Until the TUI takes
 * the terminal, problems go to stderr; after that, everything goes into {@code leia.log} (§7).
 *
 * <p>With {@code -Dleia.exitAfterFirstFrame=true} it quits as soon as the first frame is on
 * the screen and prints the startup times, so that they can be measured without a person.
 */
public final class Tui {

    private Tui() {}

    /**
     * What the TUI runs with.
     *
     * @param indexPath the index to open once the first frame is on the screen, if any
     * @param mode how to open it
     * @param directory the {@code Directory} the open prompt starts with
     * @param theme the theme of {@code --theme}, among {@code themes}
     * @param log the log, which the log window shows (ADR 0087)
     * @param runtimes the install of the window of {@code D}; empty if leia doesn't run from {@code
     *     leia.jar} (ADR 0080)
     * @param started when leia started, for {@code leia.exitAfterFirstFrame}
     */
    public record Start(
            Platform platform,
            Optional<String> indexPath,
            OpenMode mode,
            DirectoryImpl directory,
            RuntimesDirs runtimesDirs,
            DefaultJars defaultJars,
            Themes themes,
            Optional<Theme> theme,
            LeiaBackend backend,
            LeiaLog.Opened log,
            Optional<RuntimeService> runtimes,
            Instant started) {}

    /** The bundled themes and the user's own of {@code platform}'s config folders (ADR 0096, ADR 0122). */
    public static Themes themes(Platform platform) {
        return Themes.bundled().withDirs(platform.themesDirs());
    }

    /**
     * Runs the TUI until the user quits.
     *
     * @return the exit status: 1 if something unexpected ended it
     */
    // It keeps the CLI's own streams (ADR 0088)
    @SuppressWarnings("java:S106")
    public static int run(Start start, PrintStream out, PrintStream err) throws Exception {
        boolean exitAfterFirstFrame = Boolean.getBoolean("leia.exitAfterFirstFrame");
        Platform platform = start.platform();
        LeiaBackend backend = start.backend();
        PrintStream log = start.log().stream();
        VersionResult version =
                backend.call(Methods.VERSION, new VersionParams()).get();
        History history = platform.historyFile()
                .map(write -> History.load(platform.historyReadFile().orElse(write), write))
                .orElseGet(History::inMemory);
        ThemeFile themeFile =
                new ThemeFile(platform.themeFile().or(platform::themeWriteFile), platform.themeWriteFile());
        Palette.use(themeFile.resolve(start.theme(), platform.env().apply("NO_COLOR"), start.themes()));
        App app = new App(version, platform.home(), history, start.directory())
                .withCwd(Path.of("").toAbsolutePath())
                .withDefaultJars(start.defaultJars())
                .withThemes(start.themes(), themeFile)
                .withRuntimeFolders(start.runtimesDirs())
                .withLog(start.log().buffer(), platform.logFile());
        app.runtimesLoaded(
                backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams()).get());
        Session session = new Session(app, backend, start.runtimes());
        // The first frame shows the path being opened.
        start.indexPath().ifPresent(path -> app.opening(path, start.mode()));
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
                    start.indexPath()
                            .ifPresent(path -> runner.dispatch(
                                    Session.perform(new Action.Open(path, app.directory(), start.mode()))));
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
                    Duration.between(start.started(), terminalReady).toMillis(),
                    firstFrame[0] == null
                            ? -1
                            : Duration.between(start.started(), firstFrame[0]).toMillis());
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
