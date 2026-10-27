package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.LogManager;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** What leia does before the TUI takes the terminal. A test that gets to the TUI times out. */
@Timeout(30)
class MainTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Path configDir;
    private Path logFile;
    private Platform platform;

    @BeforeEach
    void platform() {
        configDir = tmp.resolve("config");
        logFile = tmp.resolve("state/leia/leia.log");
        platform = new Platform(
                Map.of("XDG_STATE_HOME", tmp.resolve("state").toString(), "XDG_CONFIG_HOME", configDir.toString())::get,
                Optional.empty(),
                false,
                Optional.empty());
    }

    @AfterEach
    void resetLogging() {
        LogManager.getLogManager().reset();
    }

    @Test
    void reportsAWrongCommandLine() throws Exception {
        assertThat(run("--frobnicate")).isEqualTo(2);

        assertThat(err()).contains("leia: unknown option '--frobnicate'", Cli.USAGE, "--help");
        assertThat(out()).isEmpty();
    }

    @Test
    void printsHelp() throws Exception {
        assertThat(run("--help", "--frobnicate-is-not-reached")).isEqualTo(2);
        assertThat(run("--help")).isZero();

        assertThat(out()).isEqualTo(Cli.HELP_TEXT);
    }

    @Test
    void printsTheVersionOfLeiaAndJava() throws Exception {
        assertThat(run("--version", "--jar", "missing.jar")).isZero();

        assertThat(out().lines())
                .satisfiesExactly(
                        leia -> assertThat(leia).matches("leia \\S+ \\(\\S+, \\S+\\)"),
                        java -> assertThat(java).startsWith("Java " + Runtime.version() + " ("));
        assertThat(err()).isEmpty();
        // Not into the log, which keeps the last session's.
        assertThat(logFile).doesNotExist();
    }

    @Test
    void downloadsTheRuntimesOnlyFromLeiaJar() throws Exception {
        assertThat(run("--download-runtimes", "--runtime", "lucene9")).isEqualTo(1);

        assertThat(err())
                .contains("leia: --download-runtimes installs into the folder runtimes, which leia has only when it");
        assertThat(logFile).doesNotExist();
    }

    @Test
    void downloadsOnlyTheRuntimesNamed() throws Exception {
        Path leiaJar = Files.writeString(tmp.resolve("leia.jar"), "");
        platform = new Platform(platform.env(), Optional.empty(), false, Optional.of(leiaJar));

        assertThat(run("--download-runtimes")).isEqualTo(2);

        assertThat(err())
                .contains(
                        "leia: --download-runtimes needs --runtime <FOLDER>; to choose runtimes, start leia and press D"
                                + " on its start screen");
        assertThat(tmp.resolve("runtimes")).doesNotExist();
        assertThat(logFile).doesNotExist();
    }

    @Test
    void refusesTheDownloadWithAnIndexPath() throws Exception {
        assertThat(run("--download-runtimes", "/data/index")).isEqualTo(2);

        assertThat(err()).contains("--download-runtimes takes no INDEX_PATH");
    }

    @Test
    void refusesYes() throws Exception {
        assertThat(run("--download-runtimes", "--runtime", "lucene9", "--yes")).isEqualTo(2);

        assertThat(err()).contains("leia: unknown option '--yes'");
    }

    @Test
    void reportsAnUnknownTheme() throws Exception {
        assertThat(run("--theme", "bogus")).isEqualTo(2);

        assertThat(err())
                .contains(
                        "leia: invalid value 'bogus' for --theme <THEME>: possible values are dark, light, mono, nord",
                        Cli.USAGE);
        assertThat(out()).isEmpty();
    }

    @Test
    void namesTheUsersThemesAmongThePossibleValues() throws Exception {
        Path themes = Files.createDirectories(configDir.resolve("leia/themes"));
        Files.writeString(themes.resolve("broken.theme"), "base = none\n");
        try (var nord = Main.class.getResourceAsStream("/io/github/mfuerstenau/leia/tui/theme/nord.theme")) {
            Files.copy(nord, themes.resolve("solar.theme"));
        }

        assertThat(run("--theme", "bogus")).isEqualTo(2);

        assertThat(err()).contains("possible values are dark, light, mono, nord, solar" + System.lineSeparator());
        // A broken theme goes to the log, not the terminal.
        assertThat(err()).doesNotContain("broken");
        assertThat(Files.readString(logFile)).contains("broken.theme: missing error");
    }

    @Test
    void reportsAJarThatDoesNotExist() throws Exception {
        Path missing = tmp.resolve("missing.jar");

        assertThat(run("--jar", missing.toString())).isEqualTo(1);

        assertThat(err()).isEqualTo("leia: --jar " + missing + ": no such jar or directory" + System.lineSeparator());
    }

    @Test
    void reportsAServerThatIsNoInstallation() throws Exception {
        Path missing = tmp.resolve("elasticsearch");

        assertThat(run("--server", missing.toString())).isEqualTo(1);
        assertThat(err()).isEqualTo("leia: --server " + missing + ": no such directory" + System.lineSeparator());

        Path empty = Files.createDirectories(missing);
        err.reset();
        assertThat(run("--server", empty.toString())).isEqualTo(1);
        assertThat(err()).startsWith("leia: --server " + empty + ": no lucene-core jar in " + empty.resolve("lib"));
    }

    @Test
    void reportsAServerWhoseLuceneLeiaDoesntRead() throws Exception {
        Path lib = Files.createDirectories(tmp.resolve("elasticsearch-7/lib"));
        Files.createFile(lib.resolve("lucene-core-8.11.4.jar"));

        assertThat(run("--server", lib.getParent().toString())).isEqualTo(1);

        assertThat(err())
                .startsWith(
                        "leia: Cannot read with the server " + lib.getParent() + ": its Lucene is 8.11.4; leia reads"
                                + " the indexes of Elasticsearch 8 and 9 and OpenSearch 2 and 3");
    }

    @Test
    void reportsJarsThatCantBeLoaded() throws Exception {
        Path notAJar = Files.writeString(tmp.resolve("notes.txt"), "no jar");

        assertThat(run("--jar", notAJar.toString())).isEqualTo(1);

        assertThat(err()).startsWith("leia: Cannot load the jars: ").doesNotContain("--no-default-jars");
        assertThat(Files.readString(logFile)).contains("Cannot start");
    }

    @Test
    void namesTheDefaultFolderIfItsJarsCantBeLoaded() throws Exception {
        Path jarsDir = Files.createDirectories(configDir.resolve("leia/jars"));
        brokenJar(jarsDir.resolve("broken.jar"));

        assertThat(run()).isEqualTo(1);

        assertThat(err()).contains("leia: Cannot load the jars: ", jarsDir.toString(), "--no-default-jars");
    }

    private int run(String... args) throws Exception {
        return Main.run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                platform);
    }

    /** A jar that names a factory that isn't in it. */
    private static void brokenJar(Path path) throws Exception {
        try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new ZipEntry("META-INF/services/org.apache.lucene.analysis.TokenizerFactory"));
            jar.write("com.example.MissingTokenizerFactory\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
