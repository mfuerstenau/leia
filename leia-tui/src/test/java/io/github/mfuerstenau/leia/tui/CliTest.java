package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CliTest {

    @Test
    void allArgumentsAreOptional() throws Exception {
        Cli cli = Cli.parse();

        assertThat(cli)
                .isEqualTo(new Cli(
                        false,
                        false,
                        java.util.List.of(),
                        java.util.List.of(),
                        false,
                        DirectoryImpl.FS,
                        false,
                        Optional.empty(),
                        false,
                        java.util.List.of(),
                        Optional.empty()));
    }

    @Test
    void parsesEveryOption() throws Exception {
        Cli cli = Cli.parse(
                "--jar",
                "icu",
                "--no-default-jars",
                "--directory",
                "nio",
                "--no-reader",
                "--jar=my.jar",
                "/data/index");

        assertThat(cli.jars()).containsExactly(Path.of("icu"), Path.of("my.jar"));
        assertThat(cli.noDefaultJars()).isTrue();
        assertThat(cli.directory()).isEqualTo(DirectoryImpl.NIO);
        assertThat(cli.noReader()).isTrue();
        assertThat(cli.indexPath()).hasValue("/data/index");
        assertThat(cli.help()).isFalse();
        assertThat(cli.version()).isFalse();
    }

    @Test
    void parsesTheDownloadOfTheRuntimes() throws Exception {
        assertThat(Cli.parse("--download-runtimes", "--runtime", "lucene9")).satisfies(cli -> {
            assertThat(cli.downloadRuntimes()).isTrue();
            assertThat(cli.runtimes()).containsExactly("lucene9");
        });
        assertThat(Cli.parse().downloadRuntimes()).isFalse();
        assertThat(Cli.parse("--runtime=lucene5", "--download-runtimes", "--runtime", "lucene9", "--runtime", "lucene5")
                        .runtimes())
                .containsExactly("lucene5", "lucene9");
    }

    @Test
    void parsesTheTheme() throws Exception {
        assertThat(Cli.parse("--theme", "nord").theme()).hasValue("nord");
        // A theme of the user's own is known only once Main reads their folder (ADR 0096).
        assertThat(Cli.parse("--theme=solar", "/data/index").theme()).hasValue("solar");
        assertThat(Cli.parse().theme()).isEmpty();
    }

    @Test
    void parsesTheServersInOrder() throws Exception {
        assertThat(Cli.parse("--server", "/opt/es", "--server=/opt/os/lib", "/data/shard")
                        .servers())
                .containsExactly(Path.of("/opt/es"), Path.of("/opt/os/lib"));
        assertThat(Cli.parse().servers()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"fs, FS", "mmap, MMAP", "nio, NIO"})
    void parsesTheDirectory(String name, DirectoryImpl directory) throws Exception {
        assertThat(Cli.parse("--directory", name).directory()).isEqualTo(directory);
        assertThat(Cli.parse("--directory=" + name).directory()).isEqualTo(directory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--version", "-V"})
    void parsesTheVersionFlag(String flag) throws Exception {
        assertThat(Cli.parse(flag, "/data/index").version()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h"})
    void parsesTheHelpFlag(String flag) throws Exception {
        assertThat(Cli.parse(flag).help()).isTrue();
    }

    @Test
    void takesAnIndexPathThatLooksLikeAnOptionAfterTheSeparator() throws Exception {
        assertThat(Cli.parse("--no-reader", "--", "--odd").indexPath()).hasValue("--odd");
        assertThat(Cli.parse("-").indexPath()).hasValue("-");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            # arguments                 | message
            --frobnicate                | unknown option '--frobnicate'
            -x                          | unknown option '-x'
            --jar                       | --jar needs a value <PATH>
            --directory                 | --directory needs a value <IMPL>
            --directory ram             | invalid value 'ram' for --directory <IMPL>
            --no-reader=yes             | --no-reader takes no value
            /a /b                       | unexpected argument '/b'
            --download-runtimes=yes     | --download-runtimes takes no value
            --download-runtimes         | --download-runtimes needs --runtime <FOLDER>; to choose runtimes, start leia and press D on its start screen
            --download-runtimes /a      | --download-runtimes takes no INDEX_PATH
            --download-runtimes --jar a | --download-runtimes takes no INDEX_PATH and none of --jar
            --download-runtimes --server /opt/es | --download-runtimes takes no INDEX_PATH and none of --jar, --server
            --server                    | --server needs a value <PATH>
            --no-reader --download-runtimes | --download-runtimes takes no INDEX_PATH
            --download-runtimes --directory nio | --download-runtimes takes no INDEX_PATH
            --download-runtimes --no-default-jars | --download-runtimes takes no INDEX_PATH
            --yes                       | unknown option '--yes'
            --download-runtimes --runtime lucene9 --yes | unknown option '--yes'
            --runtime lucene9           | --runtime goes only with --download-runtimes
            --download-runtimes --runtime lucene99 | invalid value 'lucene99' for --runtime <FOLDER>: possible values are lucene10, lucene9, lucene7, lucene5, lucene3
            --download-runtimes --runtime | --runtime needs a value <FOLDER>
            --theme                     | --theme needs a value <THEME>
            --download-runtimes --runtime lucene9 --theme nord | --download-runtimes takes no INDEX_PATH and none of --jar, --server, --no-default-jars, --directory, --no-reader, --theme
            -j /opt/jdk                 | -j is taken by the leia script
            --java=/opt/jdk             | --java is taken by the leia script
            """)
    void rejectsAWrongCommandLine(String arguments, String message) {
        assertThatThrownBy(() -> Cli.parse(arguments.split(" ")))
                .isInstanceOf(Cli.UsageException.class)
                .hasMessageStartingWith(message);
    }

    @Test
    void helpNamesEveryOption() {
        assertThat(Cli.HELP_TEXT)
                .contains(
                        "--java <PATH>",
                        "--jar <PATH>",
                        "--no-default-jars",
                        "--directory <IMPL>",
                        "--no-reader",
                        "--download-runtimes",
                        "--runtime <FOLDER>",
                        "--theme <THEME>",
                        "NO_COLOR",
                        "repo1.maven.org",
                        "SHA-256",
                        "-Djava.net.useSystemProxies=true",
                        "--version",
                        "--help",
                        "[INDEX_PATH]",
                        "leia.log",
                        "LEIA_OPTS");
    }

    @Test
    void helpSaysWhatTheRuntimesAreFor() {
        assertThat(Cli.HELP_TEXT).doesNotContain("--yes");
        assertThat(Cli.HELP_TEXT.replaceAll("\\s+", " "))
                .contains("a folder for each Lucene from 3.6 to 10 in the folder runtimes beside leia.jar")
                .contains("without them, an index opens only without a reader. D on the start screen downloads them")
                .contains("To choose runtimes instead, start leia and press D on its start screen");
    }
}
