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
                .isEqualTo(
                        new Cli(false, false, java.util.List.of(), false, DirectoryImpl.FS, false, Optional.empty()));
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
        assertThat(Cli.HELP)
                .contains(
                        "--java <PATH>",
                        "--jar <PATH>",
                        "--no-default-jars",
                        "--directory <IMPL>",
                        "--no-reader",
                        "--version",
                        "--help",
                        "[INDEX_PATH]",
                        "leia.log",
                        "LEIA_OPTS");
    }
}
