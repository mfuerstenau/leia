package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Extensions;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The old runtimes, each loaded from its folder that the build puts together, in a class loader of
 * its own (ADR 0073), and leia-lucene10's Lucene 10 from the test classpath (ADR 0065).
 */
class RuntimesTest {

    @BeforeAll
    static void loadTheFolders() {
        RuntimeFolders.load(Path.of(System.getProperty("leia.test.runtimes")));
    }

    @Test
    void findsTheOldRuntimesInTheirFolders() {
        assertThat(RuntimeFolders.runtimes())
                .extracting(LuceneRuntime::luceneVersion)
                .containsExactlyInAnyOrder("3.6.2", "5.5.5", "7.7.3", "9.12.3");
        for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
            assertThat(RuntimeFolders.state(known))
                    .as(known.folder())
                    .isEqualTo(
                            known.equals(RuntimeFolders.LATEST)
                                    ? new RuntimeFolders.State.Missing()
                                    : new RuntimeFolders.State.Loaded());
        }
        assertThat(Runtimes.load(RuntimesTest.class.getClassLoader()).runtimes())
                .extracting(LuceneRuntime::luceneVersion)
                .containsExactly(Version.LATEST.toString());

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
        assertThat(runtimes.latest())
                .hasValueSatisfying(latest -> assertThat(latest.luceneVersion()).isEqualTo(Version.LATEST.toString()));
        assertThat(runtimes.newlyRefused()).isEmpty();
    }

    @Test
    void keepsEachOldLuceneInItsOwnClassLoader() throws Exception {
        for (LuceneRuntime runtime : RuntimeFolders.runtimes()) {
            ClassLoader loader = runtime.getClass().getClassLoader();
            assertThat(loader).isNotSameAs(RuntimesTest.class.getClassLoader());
            // The interface is leia's, the Lucene its own; neither leia's other classes nor Lucene 10 are seen.
            assertThat(loader.loadClass(LuceneRuntime.class.getName())).isSameAs(LuceneRuntime.class);
            assertThat(loader.loadClass(Version.class.getName())).isNotSameAs(Version.class);
            assertThatThrownBy(() -> loader.loadClass(Runtimes.class.getName()))
                    .isInstanceOf(ClassNotFoundException.class);
            assertThat(loader.getResource("META-INF/services/org.apache.lucene.codecs.Codec"))
                    .as(runtime.luceneVersion())
                    .satisfiesAnyOf(
                            url -> assertThat(url).isNull(),
                            url -> assertThat(url.toString()).doesNotContain("lucene-core-10"));
        }
    }

    @Test
    void onlyLucene10TakesExtensionJars() {
        // The old runtimes' class loaders see no jar of --jar (ADR 0068, ADR 0073).
        assertThat(new Runtimes().known())
                .filteredOn(known -> known.luceneVersion() != null)
                .hasSize(RuntimeFolders.KNOWN.size());
        assertThat(RuntimeFolders.runtimes())
                .allSatisfy(runtime -> assertThat(runtime.extensions()).isEmpty());
        assertThat(Runtimes.load(RuntimesTest.class.getClassLoader()).runtimes())
                .filteredOn(runtime -> runtime.extensions().isPresent())
                .extracting(LuceneRuntime::luceneVersion)
                .containsExactly(Version.LATEST.toString());
    }

    @Test
    void namesTheFolderForEachMajor() {
        assertThat(RuntimeFolders.forMajor(2)).map(RuntimeFolders.Known::folder).contains("lucene3");
        assertThat(RuntimeFolders.forMajor(3)).map(RuntimeFolders.Known::folder).contains("lucene3");
        assertThat(RuntimeFolders.forMajor(4)).map(RuntimeFolders.Known::folder).contains("lucene5");
        assertThat(RuntimeFolders.forMajor(5)).map(RuntimeFolders.Known::folder).contains("lucene5");
        assertThat(RuntimeFolders.forMajor(6)).map(RuntimeFolders.Known::folder).contains("lucene7");
        assertThat(RuntimeFolders.forMajor(7)).map(RuntimeFolders.Known::folder).contains("lucene7");
        assertThat(RuntimeFolders.forMajor(8)).map(RuntimeFolders.Known::folder).contains("lucene9");
        assertThat(RuntimeFolders.forMajor(9)).map(RuntimeFolders.Known::folder).contains("lucene10");
        assertThat(RuntimeFolders.forMajor(10))
                .map(RuntimeFolders.Known::folder)
                .contains("lucene10");
        assertThat(RuntimeFolders.forMajor(1)).isEmpty();
        assertThat(RuntimeFolders.forMajor(11)).isEmpty();
        assertThat(RuntimeFolders.LATEST.adapterJar()).isEqualTo("leia-lucene10.jar");
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

            @Override
            public Optional<Analysis> analysis() {
                return Optional.empty();
            }

            @Override
            public Optional<Extensions> extensions() {
                return Optional.empty();
            }
        };
    }
}
