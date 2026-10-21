package io.github.mfuerstenau.leia.tui.runtimes;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --download-runtimes} against a local repository that serves the Lucene jars of the
 * runtimes' folders that the build puts together (ADR 0073). leia's own jars come from the test
 * classpath, which has leia.jar's resources.
 */
class RuntimeInstallTest {

    private static final Path TEST_RUNTIMES = Path.of(System.getProperty("leia.test.runtimes"));

    @TempDir
    Path tmp;

    private HttpServer server;
    /** What the repository serves by Maven path; missing paths are 404. */
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    /** How many requests the repository got. */
    private final AtomicInteger requests = new AtomicInteger();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void serve() throws IOException {
        for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
            for (RuntimeFolders.ListedJar jar : listed(known)) {
                if (jar.mavenPath().isPresent()) {
                    served.put(jar.mavenPath().get(), lucene(known, jar));
                }
            }
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/maven2/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath().substring("/maven2/".length());
            byte[] body = served.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream response = exchange.getResponseBody()) {
                    response.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void installsEveryRuntimeAndKeepsWhatMatches() throws Exception {
        Path runtimes = tmp.resolve("runtimes");

        assertThat(install(runtimes)).isZero();

        for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
            Path folder = runtimes.resolve(known.folder());
            assertThat(folder.resolve(known.adapterJar())).isRegularFile();
            for (RuntimeFolders.ListedJar jar : listed(known)) {
                assertThat(Files.mismatch(
                                folder.resolve(jar.name()), built(known).resolve(jar.name())))
                        .as(jar.name())
                        .isEqualTo(-1L);
            }
        }
        assertThat(output())
                .contains("lucene9/lucene-core-9.12.3.jar: downloaded, ")
                .contains("lucene9/leia-lucene9.jar: written, ")
                .contains("lucene10/leia-core-")
                .contains("Installed the runtimes into " + runtimes);

        out.reset();
        assertThat(install(runtimes)).isZero();
        assertThat(output())
                .contains("lucene9/lucene-core-9.12.3.jar: kept", "lucene9/leia-lucene9.jar: kept")
                .doesNotContain("downloaded", "written");
    }

    @Test
    void replacesAJarThatDoesNotMatchAndRemovesStaleOnes() throws Exception {
        Path runtimes = tmp.resolve("runtimes");
        assertThat(install(runtimes)).isZero();
        Path core = runtimes.resolve("lucene9/lucene-core-9.12.3.jar");
        Files.writeString(core, "corrupt");
        Path stale = Files.writeString(runtimes.resolve("lucene9/lucene-core-9.12.2.jar"), "old");
        Path part = Files.writeString(runtimes.resolve("lucene9/lucene-core-9.12.3.jar123.part"), "half");
        out.reset();

        assertThat(install(runtimes)).isZero();

        assertThat(Files.mismatch(core, TEST_RUNTIMES.resolve("lucene9/lucene-core-9.12.3.jar")))
                .isEqualTo(-1L);
        assertThat(stale).doesNotExist();
        assertThat(part).doesNotExist();
        assertThat(output())
                .contains("lucene9/lucene-core-9.12.3.jar: downloaded, ")
                .contains("lucene9/lucene-core-9.12.2.jar: removed");
    }

    @Test
    void failsOnBytesThatDoNotMatchAndLeavesNothingBehind() throws Exception {
        String path = "org/apache/lucene/lucene-core/9.12.3/lucene-core-9.12.3.jar";
        served.put(path, "not lucene".getBytes(StandardCharsets.UTF_8));
        Path runtimes = tmp.resolve("runtimes");

        assertThat(install(runtimes)).isEqualTo(1);

        assertThat(error())
                .contains("leia: the download of lucene-core-9.12.3.jar from " + base() + path + " doesn't match");
        assertThat(runtimes.resolve("lucene9/lucene-core-9.12.3.jar")).doesNotExist();
        try (Stream<Path> files = Files.list(runtimes.resolve("lucene9"))) {
            assertThat(files).noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    void failsOnAJarTheRepositoryLacks() throws Exception {
        String path = "org/apache/lucene/lucene-core/7.7.3/lucene-core-7.7.3.jar";
        served.remove(path);

        assertThat(install(tmp.resolve("runtimes"))).isEqualTo(1);

        assertThat(error())
                .contains("leia: cannot download lucene-core-7.7.3.jar from " + base() + path + ": HTTP 404");
    }

    @Test
    void failsOnAFolderItCannotCreate() throws Exception {
        Path notADir = Files.writeString(tmp.resolve("runtimes"), "a file");

        assertThat(install(notADir)).isEqualTo(1);

        assertThat(error()).contains("leia: cannot create " + notADir.resolve("lucene10"), "runtimes/ isn't writable");
    }

    @Test
    void leiaJarEmbedsAFolderForEveryKnownRuntime() {
        for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
            assertThat(RuntimeInstallTest.class
                            .getClassLoader()
                            .getResource(RuntimeInstall.RESOURCES + known.folder() + "/" + known.adapterJar()))
                    .as(known.folder())
                    .isNotNull();
        }
    }

    @Test
    void listsTheJarsAndDownloadsNothingWhenTheAnswerIsNo() throws Exception {
        Path runtimes = tmp.resolve("runtimes");
        RuntimeFolders.ListedJar core = listed(lucene9()).stream()
                .filter(jar -> jar.name().equals("lucene-core-9.12.3.jar"))
                .findFirst()
                .orElseThrow();

        assertThat(run(runtimes, false, question -> Optional.of("n"))).isEqualTo(1);

        assertThat(error()).contains("leia: nothing downloaded");
        assertThat(requests).hasValue(0);
        assertThat(runtimes).doesNotExist();
        assertThat(output())
                .contains(base() + core.mavenPath().orElseThrow())
                .contains("SHA-256 " + core.sha256())
                .contains("unchanged", "Maven Central", "come from leia.jar")
                .doesNotContain("kept");
    }

    @Test
    void refusesWithoutATerminal() throws Exception {
        Path runtimes = tmp.resolve("runtimes");

        assertThat(run(runtimes, false, question -> Optional.empty())).isEqualTo(1);

        assertThat(error()).contains("leia: nothing downloaded", "no terminal", "--yes downloads without asking");
        assertThat(requests).hasValue(0);
        assertThat(runtimes).doesNotExist();
    }

    @Test
    void downloadsAfterAYes() throws Exception {
        List<String> questions = new ArrayList<>();
        long count = 0;
        for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
            count += listed(known).stream()
                    .filter(jar -> jar.mavenPath().isPresent())
                    .count();
        }

        assertThat(run(tmp.resolve("runtimes"), false, question -> {
                    questions.add(question);
                    return Optional.of(" YES ");
                }))
                .isZero();

        assertThat(questions)
                .singleElement()
                .asString()
                .startsWith("Download " + count + " jars, ")
                .endsWith("[y/N] ");
        assertThat(requests).hasValue((int) count);
    }

    @Test
    void asksNothingWhenEveryJarMatches() throws Exception {
        Path runtimes = tmp.resolve("runtimes");
        assertThat(install(runtimes)).isZero();
        out.reset();
        requests.set(0);

        assertThat(run(runtimes, false, question -> {
                    throw new AssertionError("asked " + question);
                }))
                .isZero();

        assertThat(output()).contains("nothing to download", "lucene9/lucene-core-9.12.3.jar: kept");
        assertThat(requests).hasValue(0);
    }

    @Test
    void listsOnlyWhatIsMissing() throws Exception {
        Path runtimes = tmp.resolve("runtimes");
        assertThat(install(runtimes)).isZero();
        Files.writeString(runtimes.resolve("lucene9/lucene-core-9.12.3.jar"), "corrupt");
        out.reset();

        assertThat(run(runtimes, false, question -> Optional.of("no"))).isEqualTo(1);

        assertThat(output())
                .contains("  lucene9/lucene-core-9.12.3.jar, ")
                .doesNotContain("lucene7/lucene-core-7.7.3.jar")
                .contains("already there and matching are kept");
        assertThat(runtimes.resolve("lucene9/lucene-core-9.12.3.jar")).hasContent("corrupt");
    }

    private int install(Path runtimes) {
        return run(runtimes, true, question -> {
            throw new AssertionError("asked " + question);
        });
    }

    private int run(Path runtimes, boolean yes, RuntimeInstall.Prompt prompt) {
        return new RuntimeInstall(
                        runtimes,
                        base(),
                        RuntimeInstallTest.class.getClassLoader(),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        prompt)
                .run(yes);
    }

    private static RuntimeFolders.Known lucene9() {
        return RuntimeFolders.KNOWN.stream()
                .filter(known -> known.folder().equals("lucene9"))
                .findFirst()
                .orElseThrow();
    }

    private URI base() {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/maven2/");
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String error() {
        return err.toString(StandardCharsets.UTF_8);
    }

    /** The list in the embedded adapter of {@code known}. */
    private static List<RuntimeFolders.ListedJar> listed(RuntimeFolders.Known known) throws IOException {
        String name = RuntimeInstall.RESOURCES + known.folder() + "/" + known.adapterJar();
        try (InputStream adapter = RuntimeInstallTest.class.getClassLoader().getResourceAsStream(name);
                JarInputStream jar = new JarInputStream(adapter)) {
            for (JarEntry entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                if (entry.getName().equals(RuntimeFolders.LIST)) {
                    return RuntimeFolders.list(jar);
                }
            }
        }
        throw new IllegalStateException("no list in " + name);
    }

    /** A Lucene jar as the build resolved it. */
    private static byte[] lucene(RuntimeFolders.Known known, RuntimeFolders.ListedJar jar) throws IOException {
        return Files.readAllBytes(built(known).resolve(jar.name()));
    }

    /**
     * The folder of {@code known} as the build puts it together: the tests' runtimes have the old
     * ones only, Lucene 10's is a folder of its own.
     */
    private static Path built(RuntimeFolders.Known known) {
        return known.equals(RuntimeFolders.LATEST)
                ? Path.of(System.getProperty("leia.test.lucene10"))
                : TEST_RUNTIMES.resolve(known.folder());
    }
}
