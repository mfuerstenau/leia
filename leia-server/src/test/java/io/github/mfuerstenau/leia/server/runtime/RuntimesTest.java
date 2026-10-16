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
import org.junit.jupiter.api.Test;

/** The Lucene 3 runtime, which leia-lucene3's shadow jar brings to the test runtime classpath. */
class RuntimesTest {

    @Test
    void findsTheLucene3Runtime() {
        Runtimes.Loaded loaded = Runtimes.load(RuntimesTest.class.getClassLoader());
        assertThat(loaded.refused()).isEmpty();
        assertThat(loaded.runtimes())
                .singleElement()
                .satisfies(runtime -> assertThat(runtime.luceneVersion()).startsWith("3.6"));

        Runtimes runtimes = new Runtimes();
        assertThat(runtimes.forMajor(2)).isPresent();
        assertThat(runtimes.forMajor(3)).isPresent();
        assertThat(runtimes.forMajor(4)).isEmpty();
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
