package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Extensions;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The servers of {@code --server} that leia refuses, and what it makes of a server's {@code lib/}
 * (ADR 0090). A refused server leaves nothing loaded, so these run in the plain test JVM; {@code
 * Server9BackendTest} and {@code Server10BackendTest} load one.
 */
class ServerRuntimesTest {

    @TempDir
    Path tmp;

    @Test
    void findsTheLibOfAHomeOrTakesTheLibItself() throws IOException {
        Path home = fakeLib(tmp.resolve("es"), "lucene-core-9.10.0.jar");

        assertThat(ServerRuntimes.lib(home)).hasValue(home.resolve("lib"));
        assertThat(ServerRuntimes.lib(home.resolve("lib"))).hasValue(home.resolve("lib"));
        assertThat(ServerRuntimes.lib(tmp)).isEmpty();
        assertThat(ServerRuntimes.lib(tmp.resolve("missing"))).isEmpty();
    }

    @Test
    void labelsTheServerByItsOwnJar() throws IOException {
        Path es = fakeLib(
                        tmp.resolve("es"),
                        "lucene-core-9.11.1.jar",
                        "elasticsearch-core-8.15.3.jar",
                        "elasticsearch-x-content-8.15.3.jar",
                        "elasticsearch-8.15.3.jar")
                .resolve("lib");
        Path os = fakeLib(
                        tmp.resolve("os"),
                        "lucene-core-10.1.0.jar",
                        "opensearch-3.0.0.jar",
                        "opensearch-core-3.0.0.jar")
                .resolve("lib");
        Path plain = fakeLib(tmp.resolve("plain"), "lucene-core-9.11.1.jar").resolve("lib");

        assertThat(ServerRuntimes.label(es)).hasValue("Elasticsearch 8.15.3");
        assertThat(ServerRuntimes.label(os)).hasValue("OpenSearch 3.0.0");
        assertThat(ServerRuntimes.label(plain)).isEmpty();
    }

    @Test
    void refusesAServerWithoutLucene() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("empty/lib"));

        assertThatThrownBy(() -> ServerRuntimes.load(List.of(home.getParent()), Optional.of(runtimes())))
                .isInstanceOf(ServerException.class)
                .hasMessageContaining("has no lucene-core jar");
    }

    @Test
    void refusesALuceneThatLeiaHasNoAdapterFor() throws IOException {
        Path home = fakeLib(tmp.resolve("es7"), "lucene-core-8.11.4.jar");

        assertThatThrownBy(() -> ServerRuntimes.load(List.of(home), Optional.of(runtimes())))
                .isInstanceOf(ServerException.class)
                .hasMessageContaining("its Lucene is 8.11.4")
                .hasMessageContaining("Elasticsearch 8 and 9 and OpenSearch 2 and 3");
        assertThat(ServerRuntimes.runtimes()).isEmpty();
    }

    @Test
    void refusesAServerWhoseAdapterIsntInstalled() throws IOException {
        Path home = fakeLib(tmp.resolve("es"), "lucene-core-9.10.0.jar");
        Path noRuntimes = Files.createDirectories(tmp.resolve("runtimes"));

        assertThatThrownBy(() -> ServerRuntimes.load(List.of(home), Optional.of(noRuntimes)))
                .isInstanceOf(ServerException.class)
                .hasMessageContaining(
                        noRuntimes.resolve("lucene9/leia-lucene9.jar").toString())
                .hasMessageContaining("--download-runtimes --runtime lucene9");
        assertThatThrownBy(() -> ServerRuntimes.load(List.of(home), Optional.empty()))
                .isInstanceOf(ServerException.class)
                .hasMessageContaining("leia has no runtimes folder");
    }

    @Test
    void refusesASecondServerOfOneMajorAndLoadsNone() throws IOException {
        Path other = fakeLib(tmp.resolve("other"), "lucene-core-9.9.0.jar");
        Path server9 = testFile("leia.test.server9");

        assertThatThrownBy(() -> ServerRuntimes.load(List.of(server9, other), Optional.of(runtimes())))
                .isInstanceOf(ServerException.class)
                .hasMessageContaining(other.toString())
                .hasMessageContaining("same major as that of OpenSearch 0.0.0-leiatest");
        assertThat(ServerRuntimes.runtimes()).isEmpty();
    }

    @Test
    void makesALinkageErrorInAServersClassesNotSupported() {
        ServerRuntime server = new ServerRuntime(
                new Stub(),
                Path.of("/opt/es"),
                Optional.of("Elasticsearch 8.15.3"),
                new LuceneJarVersion("9.11.1", 9),
                "9.12.3",
                new ClassLoader("leia-server-lucene9", null) {});
        NoSuchMethodError inServer = new NoSuchMethodError("'void org.apache.lucene.Foo.bar()'");
        inServer.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("app", null, null, "java.lang.Object", "wait", null, -1),
            new StackTraceElement(
                    "leia-server-lucene9", null, null, "io.github.mfuerstenau.leia.lucene9.Lucene9Index", "x", null, 1)
        });
        NoSuchMethodError elsewhere = new NoSuchMethodError("'void x()'");
        elsewhere.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("leia-lucene9", null, null, "io.github.mfuerstenau.leia.lucene9.X", "x", null, 1)
        });

        Optional<RpcException> notSupported = ServerRuntimes.notSupported(inServer, List.of(server));

        assertThat(notSupported).hasValueSatisfying(e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.NOT_SUPPORTED);
            assertThat(e.getMessage())
                    .startsWith("The Lucene 9.11.1 of Elasticsearch 8.15.3 lacks what leia needs for this, as leia's"
                            + " runtime is built for Lucene 9.12.3: java.lang.NoSuchMethodError");
            assertThat(e.getCause()).isSameAs(inServer);
        });
        assertThat(ServerRuntimes.notSupported(elsewhere, List.of(server))).isEmpty();
        // A class of the server that fails to initialize, not one that is missing.
        NoClassDefFoundError failing = new NoClassDefFoundError("Could not initialize class org.elasticsearch.X");
        failing.setStackTrace(inServer.getStackTrace());
        assertThat(ServerRuntimes.notSupported(failing, List.of(server)))
                .hasValueSatisfying(e -> assertThat(e.getMessage())
                        .startsWith("The classes of Elasticsearch 8.15.3 fail at this, with its Lucene 9.11.1 (more in"
                                + " leia.log): java.lang.NoClassDefFoundError: Could not initialize"));
        ServiceConfigurationError spi = new ServiceConfigurationError("Provider X could not be instantiated");
        spi.setStackTrace(inServer.getStackTrace());
        assertThat(ServerRuntimes.notSupported(spi, List.of(server))).isPresent();
        AssertionError other = new AssertionError("not the server's");
        other.setStackTrace(inServer.getStackTrace());
        assertThat(ServerRuntimes.notSupported(other, List.of(server))).isEmpty();
        assertThat(ServerRuntimes.notSupported(inServer, List.of())).isEmpty();
    }

    @Test
    void readsTheMajorsOfItsLuceneAndTheOneBefore() {
        ServerRuntime server = new ServerRuntime(
                new Stub(),
                Path.of("/opt/os"),
                Optional.empty(),
                new LuceneJarVersion("10.1.0", 10),
                "10.5.2",
                new ClassLoader("x", null) {});

        assertThat(server.indexMajors()).containsExactlyInAnyOrder(9, 10);
        assertThat(server.name()).isEqualTo("the server " + Path.of("/opt/os"));
        assertThat(server.analysis()).isEmpty();
        assertThat(server.extensions()).isEmpty();
    }

    @Test
    void takesTheJarsOfTheKnnPluginAndWhatItExtends() throws Exception {
        Path home = fakeLib(tmp.resolve("os"), "lucene-core-9.12.3.jar");
        Path plugin = Files.createDirectories(home.resolve("plugins/opensearch-knn"));
        Files.createFile(plugin.resolve("opensearch-knn-2.19.6.0.jar"));
        Files.createFile(plugin.resolve("guava.jar"));
        Files.createFile(plugin.resolve("notes.txt"));
        Files.writeString(
                plugin.resolve("plugin-descriptor.properties"),
                "name=opensearch-knn\nextended.plugins=lang-painless, transport-grpc,sibling\n");
        // lang-painless isn't there; one extended plugin is a module, the other a plugin.
        Path module = Files.createDirectories(home.resolve("modules/transport-grpc"));
        Files.createFile(module.resolve("failureaccess.jar"));
        Files.createFile(module.resolve("guava.jar"));
        // transitive: a module of its own, and the k-NN plugin again, which isn't taken twice
        Files.writeString(
                module.resolve("plugin-descriptor.properties"),
                "name=transport-grpc\nextended.plugins=deep,opensearch-knn\n");
        Path deep = Files.createDirectories(home.resolve("modules/deep"));
        Files.createFile(deep.resolve("deep.jar"));
        Path sibling = Files.createDirectories(home.resolve("plugins/sibling"));
        Files.createFile(sibling.resolve("sibling.jar"));
        // Another plugin is left alone.
        Files.createFile(Files.createDirectories(home.resolve("plugins/opensearch-security"))
                .resolve("security.jar"));

        List<Path> fromHome = ServerRuntimes.pluginJars(home, home.resolve("lib"));

        assertThat(fromHome)
                .containsExactly(
                        plugin.resolve("guava.jar"),
                        plugin.resolve("opensearch-knn-2.19.6.0.jar"),
                        module.resolve("failureaccess.jar"),
                        module.resolve("guava.jar"),
                        deep.resolve("deep.jar"),
                        sibling.resolve("sibling.jar"));
        // The lib/ alone finds the plugins beside it, and its own jars aren't among them.
        assertThat(ServerRuntimes.pluginJars(home.resolve("lib"), home.resolve("lib")))
                .isEqualTo(fromHome);
    }

    @Test
    void takesNoPluginJarsFromAServerWithoutTheKnnPlugin() throws Exception {
        Path home = fakeLib(tmp.resolve("os"), "lucene-core-9.12.3.jar");
        Files.createFile(Files.createDirectories(home.resolve("plugins/opensearch-security"))
                .resolve("security.jar"));
        Path plugin = Files.createDirectories(tmp.resolve("lone/plugins/opensearch-knn"));
        Files.createFile(plugin.resolve("opensearch-knn.jar"));

        assertThat(ServerRuntimes.pluginJars(home, home.resolve("lib"))).isEmpty();
        // A plugin without a descriptor extends nothing.
        Path lonely = fakeLib(tmp.resolve("lone"), "lucene-core-9.12.3.jar");
        assertThat(ServerRuntimes.pluginJars(lonely, lonely.resolve("lib")))
                .containsExactly(plugin.resolve("opensearch-knn.jar"));
    }

    @Test
    void takesNoPluginJarsBesideAFolderOfJarsThatIsNotNamedLib() throws Exception {
        Path jars = Files.createDirectories(tmp.resolve("jars"));
        Files.createFile(jars.resolve("lucene-core-9.12.3.jar"));
        Files.createFile(
                Files.createDirectories(tmp.resolve("plugins/opensearch-knn")).resolve("opensearch-knn.jar"));

        assertThat(ServerRuntimes.pluginJars(jars, jars)).isEmpty();
    }

    private static Path runtimes() {
        return testFile("leia.test.runtimes");
    }

    private static Path testFile(String property) {
        return Path.of(System.getProperty(property)).toAbsolutePath().normalize();
    }

    /** {@code home} with a {@code lib/} of empty files named {@code jars}. */
    private static Path fakeLib(Path home, String... jars) throws IOException {
        Path lib = Files.createDirectories(home.resolve("lib"));
        for (String jar : jars) {
            Files.createFile(lib.resolve(jar));
        }
        return home;
    }

    /** An adapter that is never asked to read. */
    private static final class Stub implements LuceneRuntime {

        @Override
        public int apiVersion() {
            return API_VERSION;
        }

        @Override
        public String luceneVersion() {
            return "9.11.1";
        }

        @Override
        public Set<Integer> indexMajors() {
            return Set.of(8);
        }

        @Override
        public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) {
            throw new AssertionError("not read");
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
    }
}
