package io.github.mfuerstenau.leia.tui.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.tui.prompt.PathCompletion.Candidate;
import io.github.mfuerstenau.leia.tui.prompt.PathCompletion.Completion;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathCompletionTest {

    private static final String SEPARATOR = File.separator;

    @TempDir
    Path tmp;

    @Test
    void expandsTheHomeDirectory() {
        Path home = Path.of("/home/me");

        assertThat(PathCompletion.expandHome("~", home))
                .isEqualTo(Path.of("/home/me").toString());
        assertThat(PathCompletion.expandHome("~/idx", home)).isEqualTo(Path.of("/home/me") + "/idx");
        assertThat(PathCompletion.expandHome("~other/idx", home)).isEqualTo("~other/idx");
        assertThat(PathCompletion.expandHome("/data/~", home)).isEqualTo("/data/~");
        assertThat(PathCompletion.expandHome("~/idx", null)).isEqualTo("~/idx");
    }

    @Test
    void completesDirectories() throws IOException {
        for (String dir : List.of("products", "production", "orders", ".hidden")) {
            Files.createDirectory(tmp.resolve(dir));
        }
        Files.writeString(tmp.resolve("prod.txt"), "");
        String base = tmp + SEPARATOR;

        assertThat(PathCompletion.complete(base + "o", null))
                .isEqualTo(new Completion(base + "orders" + SEPARATOR, base, List.of()));
        assertThat(PathCompletion.complete(base + "pr", null))
                .isEqualTo(new Completion(
                        base + "product",
                        base,
                        List.of(new Candidate("production", true), new Candidate("products", true))));
        assertThat(PathCompletion.complete(base + "x", null)).isEqualTo(new Completion(base + "x", base, List.of()));
        assertThat(PathCompletion.complete(base + ".", null))
                .isEqualTo(new Completion(base + ".hidden" + SEPARATOR, base, List.of()));
        assertThat(PathCompletion.complete(base, null).candidates())
                .extracting(Candidate::name)
                .containsExactly("orders", "production", "products");
    }

    @Test
    void completesFilesThatTheCallerAccepts() throws IOException {
        Files.createDirectory(tmp.resolve("lib"));
        Files.writeString(tmp.resolve("lucene.jar"), "");
        Files.writeString(tmp.resolve("lucene.txt"), "");
        String base = tmp + SEPARATOR;

        assertThat(PathCompletion.completeWithFiles(base + "lu", null, name -> name.endsWith(".jar")))
                .isEqualTo(new Completion(base + "lucene.jar", base, List.of()));
    }

    @Test
    void listsTheMatchesOfTheLastComponentWithoutChangingTheInput() throws IOException {
        for (String dir : List.of("alpha", "alps", "beta", ".hidden", ".hat")) {
            Files.createDirectory(tmp.resolve(dir));
        }
        Files.writeString(tmp.resolve("alpine.jar"), "");
        Files.writeString(tmp.resolve("alpine.txt"), "");
        String base = tmp + SEPARATOR;

        assertThat(PathCompletion.matches(base + "al", null, name -> name.endsWith(".jar")))
                .containsExactly(
                        new Candidate("alpha", true), new Candidate("alpine.jar", false), new Candidate("alps", true));
        assertThat(PathCompletion.matches(base, null, name -> false))
                .extracting(Candidate::name)
                .containsExactly("alpha", "alps", "beta");
        assertThat(PathCompletion.matches(base + ".h", null, name -> false))
                .extracting(Candidate::name)
                .containsExactly(".hat", ".hidden");
        assertThat(PathCompletion.matches(base + "nope" + SEPARATOR + "x", null, name -> false))
                .isEmpty();
        assertThat(new Candidate("alpha", true).text()).isEqualTo("alpha" + SEPARATOR);
        assertThat(new Candidate("a.jar", false).text()).isEqualTo("a.jar");
        assertThat(PathCompletion.base(base + "al")).isEqualTo(base);
        assertThat(PathCompletion.base("al")).isEmpty();
    }

    @Test
    void completesUnderTheHomeDirectoryButKeepsTheTilde() throws IOException {
        Files.createDirectory(tmp.resolve("indexes"));

        assertThat(PathCompletion.complete("~", tmp)).isEqualTo(new Completion("~" + SEPARATOR, "", List.of()));
        assertThat(PathCompletion.complete("~/ind", tmp))
                .isEqualTo(new Completion("~/indexes" + SEPARATOR, "~/", List.of()));
    }

    @Test
    void ctrlWWalksUpThePath() {
        assertThat(PathCompletion.componentStart("/data/indexes/products/")).isEqualTo("/data/indexes/".length());
        assertThat(PathCompletion.componentStart("/data/indexes/")).isEqualTo("/data/".length());
        assertThat(PathCompletion.componentStart("idx")).isZero();
    }
}
