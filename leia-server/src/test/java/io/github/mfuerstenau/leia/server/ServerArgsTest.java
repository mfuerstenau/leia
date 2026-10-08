package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerArgsTest {

    @Test
    void noArgumentsMeanNoAnalysisJars() {
        assertThat(ServerArgs.parse().analysisJars()).isEmpty();
    }

    @Test
    void analysisJarsCanBeRepeated() {
        ServerArgs args = ServerArgs.parse("--analysis-jar", "a.jar", "--analysis-jar", "lib");

        assertThat(args.analysisJars()).containsExactly(Path.of("a.jar"), Path.of("lib"));
    }

    @Test
    void rejectsUnknownArgumentsAndAMissingPath() {
        assertThatThrownBy(() -> ServerArgs.parse("--verbose"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown argument '--verbose'");
        assertThatThrownBy(() -> ServerArgs.parse("--analysis-jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--analysis-jar needs a path");
    }
}
