package io.github.mfuerstenau.leia.tui.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.tui.prompt.PathCompletion.Completion;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathCompletionTest {

    private static final String SEPARATOR = File.separator;

    @TempDir
    Path tmp;

    @Test
    void expandsTheHomeDirectory() {
        Optional<Path> home = Optional.of(Path.of("/home/me"));

        assertThat(PathCompletion.expandHome("~", home))
                .isEqualTo(Path.of("/home/me").toString());
        assertThat(PathCompletion.expandHome("~/idx", home)).isEqualTo(Path.of("/home/me") + "/idx");
        assertThat(PathCompletion.expandHome("~other/idx", home)).isEqualTo("~other/idx");
        assertThat(PathCompletion.expandHome("/data/~", home)).isEqualTo("/data/~");
        assertThat(PathCompletion.expandHome("~/idx", Optional.empty())).isEqualTo("~/idx");
    }

    @Test
    void completesDirectories() throws IOException {
        for (String dir : List.of("products", "production", "orders", ".hidden")) {
            Files.createDirectory(tmp.resolve(dir));
        }
        Files.writeString(tmp.resolve("prod.txt"), "");
        String base = tmp + SEPARATOR;

        assertThat(PathCompletion.complete(base + "o", Optional.empty()))
                .isEqualTo(new Completion(base + "orders" + SEPARATOR, List.of()));
        assertThat(PathCompletion.complete(base + "pr", Optional.empty()))
                .isEqualTo(new Completion(base + "product", List.of("production", "products")));
        assertThat(PathCompletion.complete(base + "x", Optional.empty()))
                .isEqualTo(new Completion(base + "x", List.of()));
        assertThat(PathCompletion.complete(base + ".", Optional.empty()))
                .isEqualTo(new Completion(base + ".hidden" + SEPARATOR, List.of()));
        assertThat(PathCompletion.complete(base, Optional.empty()).candidates())
                .containsExactly("orders", "production", "products");
    }

    @Test
    void completesFilesThatTheCallerAccepts() throws IOException {
        Files.createDirectory(tmp.resolve("lib"));
        Files.writeString(tmp.resolve("lucene.jar"), "");
        Files.writeString(tmp.resolve("lucene.txt"), "");
        String base = tmp + SEPARATOR;

        assertThat(PathCompletion.completeWithFiles(base + "lu", Optional.empty(), name -> name.endsWith(".jar")))
                .isEqualTo(new Completion(base + "lucene.jar", List.of()));
    }

    @Test
    void completesUnderTheHomeDirectoryButKeepsTheTilde() throws IOException {
        Files.createDirectory(tmp.resolve("indexes"));

        assertThat(PathCompletion.complete("~", Optional.of(tmp)))
                .isEqualTo(new Completion("~" + SEPARATOR, List.of()));
        assertThat(PathCompletion.complete("~/ind", Optional.of(tmp)))
                .isEqualTo(new Completion("~/indexes" + SEPARATOR, List.of()));
    }

    @Test
    void ctrlWWalksUpThePath() {
        assertThat(PathCompletion.componentStart("/data/indexes/products/")).isEqualTo("/data/indexes/".length());
        assertThat(PathCompletion.componentStart("/data/indexes/")).isEqualTo("/data/".length());
        assertThat(PathCompletion.componentStart("idx")).isZero();
    }
}
