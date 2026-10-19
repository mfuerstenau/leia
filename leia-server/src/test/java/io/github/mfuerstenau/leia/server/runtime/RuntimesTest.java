package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;

/**
 * The old runtimes, which the shadow jars of leia-lucene3, leia-lucene5, leia-lucene7 and leia-lucene9
 * bring to the test runtime classpath, and leia-lucene10's Lucene 10 (ADR 0065), which the server
 * doesn't route to yet.
 */
class RuntimesTest {

    @Test
    void findsTheOldRuntimes() {
        Runtimes.Loaded loaded = Runtimes.load(RuntimesTest.class.getClassLoader());
        assertThat(loaded.refused()).isEmpty();
        assertThat(loaded.runtimes())
                .extracting(LuceneRuntime::luceneVersion)
                .containsExactlyInAnyOrder("3.6.2", "5.5.5", "7.7.3", "9.12.3", Version.LATEST.toString());

        Runtimes runtimes = new Runtimes();
        assertThat(runtimes.forMajor(2))
                .hasValueSatisfying(
                        runtime -> assertThat(runtime.luceneVersion()).isEqualTo("3.6.2"));
        assertThat(runtimes.forMajor(3)).isPresent();
        assertThat(runtimes.forMajor(4))
                .hasValueSatisfying(
                        runtime -> assertThat(runtime.luceneVersion()).isEqualTo("5.5.5"));
        assertThat(runtimes.forMajor(5)).isPresent();
        assertThat(runtimes.forMajor(6))
                .hasValueSatisfying(
                        runtime -> assertThat(runtime.luceneVersion()).isEqualTo("7.7.3"));
        assertThat(runtimes.forMajor(7)).isPresent();
        assertThat(runtimes.forMajor(8))
                .hasValueSatisfying(
                        runtime -> assertThat(runtime.luceneVersion()).isEqualTo("9.12.3"));
        assertThat(runtimes.forMajor(9))
                .hasValueSatisfying(
                        runtime -> assertThat(runtime.luceneVersion()).isEqualTo(Version.LATEST.toString()));
        assertThat(runtimes.forMajor(10)).isPresent();
        assertThat(runtimes.forMajor(11)).isEmpty();
        assertThat(runtimes.newlyRefused()).isEmpty();
    }

    @Test
    void namesTheJarForEachMajor() {
        assertThat(Runtimes.jarFor(2)).contains("leia-lucene3.jar");
        assertThat(Runtimes.jarFor(3)).contains("leia-lucene3.jar");
        assertThat(Runtimes.jarFor(4)).contains("leia-lucene5.jar");
        assertThat(Runtimes.jarFor(5)).contains("leia-lucene5.jar");
        assertThat(Runtimes.jarFor(6)).contains("leia-lucene7.jar");
        assertThat(Runtimes.jarFor(7)).contains("leia-lucene7.jar");
        assertThat(Runtimes.jarFor(8)).contains("leia-lucene9.jar");
        assertThat(Runtimes.jarFor(1)).isEmpty();
        assertThat(Runtimes.jarFor(9)).isEmpty();
        assertThat(Runtimes.jarFor(10)).isEmpty();
    }

    @Test
    void namesTheMajorsInNotSupported() {
        assertThat(new OpenRuntime(Path.of("index"), runtime(Set.of(3, 2)), null, List.of())
                        .notSupported()
                        .getMessage())
                .isEqualTo("Not supported for an index written by Lucene 2.x or 3.x (read with Lucene 1.2.3)");
        assertThat(new OpenRuntime(Path.of("index"), runtime(Set.of(5)), null, List.of()).majors())
                .isEqualTo("5.x");
        assertThat(new OpenRuntime(Path.of("index"), runtime(Set.of(8, 6, 7)), null, List.of()).majors())
                .isEqualTo("6.x, 7.x or 8.x");
    }

    private static LuceneRuntime runtime(Set<Integer> majors) {
        return new LuceneRuntime() {
            @Override
            public int apiVersion() {
                return API_VERSION;
            }

            @Override
            public String luceneVersion() {
                return "1.2.3";
            }

            @Override
            public Set<Integer> indexMajors() {
                return majors;
            }

            @Override
            public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Commits> commits() {
                return Optional.empty();
            }

            @Override
            public Optional<Check> check() {
                return Optional.empty();
            }
        };
    }
}
