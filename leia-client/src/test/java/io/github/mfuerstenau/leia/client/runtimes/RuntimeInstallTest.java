package io.github.mfuerstenau.leia.client.runtimes;

import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.TEST_RUNTIMES;
import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.built;
import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.known;
import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.listed;
import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.lucene;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --download-runtimes} against a local repository that serves the Lucene jars of the
 * runtimes' folders that the build puts together (ADR 0073, ADR 0079). leia's own jars come from the
 * test classpath, which has leia.jar's resources.
 */
class RuntimeInstallTest {

    @TempDir
    Path tmp;

    private FakeRepository repository;
    private Path runtimes;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void serve() throws IOException {
        repository = new FakeRepository();
        runtimes = tmp.resolve("runtimes");
    }

    @AfterEach
    void stop() {
        repository.close();
    }

    @Test
    void installsEveryRuntimeAndKeepsWhatMatches() throws Exception {
        assertThat(yes()).isZero();

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
        repository.requests.set(0);
        assertThat(yes()).isZero();
        assertThat(output())
                .contains("nothing to download")
                .contains("lucene9/lucene-core-9.12.3.jar: kept", "lucene9/leia-lucene9.jar: kept")
                .doesNotContain("downloaded", "written");
        assertThat(repository.requests).hasValue(0);
    }

    @Test
    void replacesAJarThatDoesNotMatchAndRemovesStaleOnes() throws Exception {
        assertThat(yes()).isZero();
        Path core = runtimes.resolve("lucene9/lucene-core-9.12.3.jar");
        Files.writeString(core, "corrupt");
        Path stale = Files.writeString(runtimes.resolve("lucene9/lucene-core-9.12.2.jar"), "old");
        Path part = Files.writeString(runtimes.resolve("lucene9/lucene-core-9.12.3.jar123.part"), "half");
        out.reset();

        assertThat(yes()).isZero();

        assertThat(Files.mismatch(core, TEST_RUNTIMES.resolve("lucene9/lucene-core-9.12.3.jar")))
                .isEqualTo(-1L);
        assertThat(stale).doesNotExist();
        assertThat(part).doesNotExist();
        assertThat(output())
                .contains("  lucene9/lucene-core-9.12.3.jar, ")
                .doesNotContain("  lucene7/lucene-core-7.7.3.jar, ")
                .contains("already there and matching are kept")
                .contains("lucene9/lucene-core-9.12.3.jar: downloaded, ")
                .contains("lucene9/lucene-core-9.12.2.jar: removed");
    }

    @Test
    void listsWhereEachJarComesFrom() throws Exception {
        RuntimeFolders.ListedJar core = lucene(known("lucene9")).stream()
                .filter(jar -> jar.name().equals("lucene-core-9.12.3.jar"))
                .findFirst()
                .orElseThrow();

        assertThat(yes()).isZero();

        assertThat(output())
                .contains(repository.base() + core.mavenPath().orElseThrow())
                .contains("SHA-256 " + core.sha256())
                .contains("unchanged", "Maven Central", "come from leia.jar");
    }

    @Test
    void failsOneRuntimeOnBytesThatDoNotMatchAndInstallsTheOthers() throws Exception {
        String path = "org/apache/lucene/lucene-core/9.12.3/lucene-core-9.12.3.jar";
        repository.served.put(path, "not lucene".getBytes(StandardCharsets.UTF_8));

        assertThat(yes()).isEqualTo(1);

        assertThat(error())
                .contains("leia: the download of lucene-core-9.12.3.jar from " + repository.base() + path
                        + " doesn't match");
        assertThat(runtimes.resolve("lucene9/lucene-core-9.12.3.jar")).doesNotExist();
        assertNoPart(runtimes.resolve("lucene9"));
        for (String other : List.of("lucene10", "lucene7", "lucene5", "lucene3")) {
            for (RuntimeFolders.ListedJar jar : listed(known(other))) {
                assertThat(runtimes.resolve(other).resolve(jar.name())).isRegularFile();
            }
        }
        assertThat(output()).doesNotContain("Installed the runtimes");
    }

    @Test
    void failsOnAJarTheRepositoryLacks() throws Exception {
        String path = "org/apache/lucene/lucene-core/7.7.3/lucene-core-7.7.3.jar";
        repository.served.remove(path);

        assertThat(yes()).isEqualTo(1);

        assertThat(error())
                .contains(
                        "leia: cannot download lucene-core-7.7.3.jar from " + repository.base() + path + ": HTTP 404");
    }

    @Test
    void failsOnAFolderItCannotCreate() throws Exception {
        runtimes = Files.writeString(tmp.resolve("runtimes"), "a file");

        assertThat(yes()).isEqualTo(1);

        assertThat(error()).contains("leia: cannot create " + runtimes.resolve("lucene10"), "runtimes/ isn't writable");
    }

    @Test
    void leiaJarEmbedsAFolderForEveryKnownRuntime() {
        for (RuntimeFolders.Known known : RuntimeFolders.KNOWN) {
            assertThat(RuntimeInstallTest.class
                            .getClassLoader()
                            .getResource(RuntimeFolders.EMBEDDED + known.folder() + "/" + known.adapterJar()))
                    .as(known.folder())
                    .isNotNull();
        }
    }

    @Test
    void plansWithoutWritingOrDownloading() throws Exception {
        RuntimeInstall.Plan plan = install().plan(RuntimeFolders.KNOWN);

        assertThat(plan.runtimes())
                .extracting(RuntimeInstall.RuntimePlan::known)
                .isEqualTo(RuntimeFolders.KNOWN);
        assertThat(plan.needingDownload()).hasSize(RuntimeFolders.KNOWN.size());
        RuntimeInstall.RuntimePlan lucene9 = plan.runtimes().get(1);
        assertThat(lucene9.downloads())
                .extracting(download -> download.jar().name())
                .containsExactlyElementsOf(lucene(known("lucene9")).stream()
                        .map(RuntimeFolders.ListedJar::name)
                        .toList());
        assertThat(lucene9.ownJars()).containsExactly("leia-lucene9.jar");
        assertThat(plan.runtimes().getFirst().ownJars()).first().isEqualTo("leia-lucene10.jar");
        assertThat(plan.runtimes().getFirst().ownJars())
                .anySatisfy(jar -> assertThat(jar).startsWith("leia-core-"));
        assertThat(repository.requests).hasValue(0);
        assertThat(runtimes).doesNotExist();
    }

    @Test
    void leavesARuntimeThatIsNotChosenAlone() throws Exception {
        assertThat(yes()).isZero();
        Path lucene5 = runtimes.resolve("lucene5");
        Path core5 = lucene5.resolve("lucene-core-5.5.5.jar");
        Files.writeString(core5, "corrupt");
        Files.writeString(lucene5.resolve("stale.jar"), "stale");
        Path core9 = runtimes.resolve("lucene9/lucene-core-9.12.3.jar");
        Files.delete(core9);
        Map<String, String> before = snapshot(lucene5);
        RuntimeInstall install = install();
        RuntimeInstall.Plan plan = install.plan(RuntimeFolders.KNOWN);
        assertThat(plan.needingDownload())
                .extracting(runtime -> runtime.known().folder())
                .containsExactly("lucene9", "lucene5");

        RuntimeInstall.Outcome outcome = install.install(plan, Set.of(known("lucene9")), listener(), () -> false);

        assertThat(outcome.status()).isZero();
        // Complete runtimes are refreshed without being chosen; lucene5 isn't touched.
        assertThat(outcome.results().keySet())
                .extracting(RuntimeFolders.Known::folder)
                .containsExactly("lucene10", "lucene9", "lucene7", "lucene3");
        assertThat(snapshot(lucene5)).isEqualTo(before);
        assertThat(core9).isRegularFile();
        assertThat(outcome.summary()).contains("lucene9: installed");
    }

    @Test
    void reportsTheBytesOfEachDownload() throws Exception {
        AtomicLong bytes = new AtomicLong();
        Set<String> started = new HashSet<>();
        RuntimeInstall install = install();
        RuntimeInstall.Plan plan = install.plan(List.of(known("lucene7")));

        RuntimeInstall.Outcome outcome = install.install(
                plan,
                Set.of(known("lucene7")),
                new RuntimeInstall.Listener() {
                    @Override
                    public void jarStarted(String folder, String jar, long size) {
                        started.add(folder + "/" + jar);
                    }

                    @Override
                    public void bytes(long n) {
                        bytes.addAndGet(n);
                    }
                },
                () -> false);

        assertThat(outcome.status()).isZero();
        assertThat(bytes).hasValue(plan.runtimes().getFirst().downloadSize());
        assertThat(started).hasSize(lucene(known("lucene7")).size());
    }

    @Test
    void cancelsAfterTheFirstBytesAndLeavesNoPartBehind() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        RuntimeInstall install = install();
        RuntimeInstall.Plan plan = install.plan(List.of(known("lucene9"), known("lucene7")));

        RuntimeInstall.Outcome outcome = install.install(
                plan,
                Set.of(known("lucene9"), known("lucene7")),
                new RuntimeInstall.Listener() {
                    @Override
                    public void bytes(long n) {
                        cancelled.set(true);
                    }
                },
                cancelled::get);

        assertThat(outcome.status()).isEqualTo(1);
        assertThat(outcome.results())
                .containsEntry(known("lucene9"), new RuntimeInstall.Result.Cancelled())
                .containsEntry(known("lucene7"), new RuntimeInstall.Result.Cancelled());
        assertNoPart(runtimes.resolve("lucene9"));
        assertThat(runtimes.resolve("lucene7")).doesNotExist();
        assertThat(outcome.summary()).containsExactly("lucene9: cancelled", "lucene7: cancelled");
    }

    @Test
    void installsOnlyTheNamedRuntimes() throws Exception {
        assertThat(RuntimeDownload.run(install(), List.of("lucene9"), print(out), print(err)))
                .isZero();

        try (Stream<Path> folders = Files.list(runtimes)) {
            assertThat(folders).map(folder -> folder.getFileName().toString()).containsExactly("lucene9");
        }
    }

    @Test
    void downloadsNothingWhenEveryJarMatches() throws Exception {
        assertThat(yes()).isZero();
        out.reset();
        int requests = repository.requests.get();

        assertThat(yes()).isZero();

        assertThat(output()).contains("nothing to download", "lucene9/lucene-core-9.12.3.jar: kept");
        assertThat(repository.requests).hasValue(requests);
    }

    @Test
    void refreshesLeiasOwnJarsThatDifferAndKeepsTheRest() throws Exception {
        assertThat(RuntimeDownload.run(install(), List.of("lucene10", "lucene9"), print(out), print(err)))
                .isZero();
        Path leiaCore = ownJar(known("lucene10"), "leia-core-");
        Files.writeString(runtimes.resolve("lucene10/leia-lucene10.jar"), "an older leia's");
        Files.writeString(leiaCore, "an older leia's");
        Path lucene9Core = runtimes.resolve("lucene9/lucene-core-9.12.3.jar");
        Files.writeString(lucene9Core, "corrupt");
        Map<String, String> lucene9 = snapshot(runtimes.resolve("lucene9"));
        out.reset();
        repository.requests.set(0);

        assertThat(install().refreshOwnJars(listener())).isEmpty();

        for (RuntimeFolders.Known known : List.of(known("lucene10"), known("lucene9"))) {
            for (String own :
                    install().plan(List.of(known)).runtimes().getFirst().ownJars()) {
                assertThat(Files.mismatch(
                                runtimes.resolve(known.folder()).resolve(own),
                                built(known).resolve(own)))
                        .as(own)
                        .isEqualTo(-1L);
            }
        }
        // Lucene's jars aren't touched, nor is a matching adapter rewritten.
        assertThat(snapshot(runtimes.resolve("lucene9"))).isEqualTo(lucene9);
        assertThat(output())
                .contains("lucene10/leia-lucene10.jar: written, ", "lucene10/" + leiaCore.getFileName() + ": written, ")
                .contains("lucene9/leia-lucene9.jar: kept")
                .doesNotContain("downloaded", "removed");
        assertThat(repository.requests).hasValue(0);
    }

    @Test
    void refreshesNoFolderWithoutAnAdapterAndCreatesNone() throws Exception {
        Path lucene7 = Files.createDirectories(runtimes.resolve("lucene7"));
        Files.writeString(lucene7.resolve("lucene-core-7.7.3.jar"), "a Lucene");

        assertThat(install().refreshOwnJars(listener())).isEmpty();

        assertThat(snapshot(lucene7)).containsOnlyKeys("lucene-core-7.7.3.jar");
        try (Stream<Path> folders = Files.list(runtimes)) {
            assertThat(folders).map(folder -> folder.getFileName().toString()).containsExactly("lucene7");
        }
        assertThat(output()).isEmpty();

        runtimes = tmp.resolve("none");
        assertThat(install().refreshOwnJars(listener())).isEmpty();
        assertThat(runtimes).doesNotExist();
    }

    @Test
    void reportsAFolderItCannotRefresh() throws Exception {
        assertThat(RuntimeDownload.run(install(), List.of("lucene9"), print(out), print(err)))
                .isZero();
        Path lucene9 = runtimes.resolve("lucene9");
        Files.writeString(lucene9.resolve("leia-lucene9.jar"), "an older leia's");
        assertThat(lucene9.toFile().setWritable(false)).isTrue();
        try {
            assumeFalse(Files.isWritable(lucene9), "a folder that root can still write");

            Map<RuntimeFolders.Known, String> failed = install().refreshOwnJars(listener());

            assertThat(failed).containsOnlyKeys(known("lucene9"));
            assertThat(failed.get(known("lucene9"))).contains("cannot write " + lucene9.resolve("leia-lucene9.jar"));
            assertThat(error()).contains("leia: cannot write ");
            assertThat(lucene9.resolve("leia-lucene9.jar")).hasContent("an older leia's");
        } finally {
            assertThat(lucene9.toFile().setWritable(true)).isTrue();
        }
    }

    /** The own jar of {@code known}'s installed folder whose name starts with {@code prefix}. */
    private Path ownJar(RuntimeFolders.Known known, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(runtimes.resolve(known.folder()))) {
            return files.filter(file -> file.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow();
        }
    }

    /** {@code --download-runtimes} with every runtime named. */
    private int yes() {
        return RuntimeDownload.run(
                install(),
                RuntimeFolders.KNOWN.stream().map(RuntimeFolders.Known::folder).toList(),
                print(out),
                print(err));
    }

    private RuntimeInstall install() {
        return new RuntimeInstall(runtimes, repository.base(), RuntimeInstallTest.class.getClassLoader());
    }

    private RuntimeInstall.Listener listener() {
        return RuntimeInstall.Listener.printing(print(out), print(err));
    }

    private static PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }

    private static void assertNoPart(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files).noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        }
    }

    /** Each file of {@code folder} with its content. */
    private static Map<String, String> snapshot(Path folder) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> list = Files.list(folder)) {
            for (Path file : list.toList()) {
                files.put(file.getFileName().toString(), HexFormat.of().formatHex(Files.readAllBytes(file)));
            }
        }
        return files;
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String error() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
