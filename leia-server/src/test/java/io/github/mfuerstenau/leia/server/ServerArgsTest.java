package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerArgsTest {

    @Test
    void noArgumentsMeanNoExtensionJars() {
        assertThat(ServerArgs.parse().jars()).isEmpty();
    }

    @Test
    void jarsCanBeRepeated() {
        ServerArgs args = ServerArgs.parse("--jar", "a.jar", "--jar", "lib");

        assertThat(args.jars()).containsExactly(Path.of("a.jar"), Path.of("lib"));
    }

    @Test
    void rejectsUnknownArgumentsAndAMissingPath() {
        assertThatThrownBy(() -> ServerArgs.parse("--verbose"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown argument '--verbose'");
        assertThatThrownBy(() -> ServerArgs.parse("--jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--jar needs a path");
    }
}
