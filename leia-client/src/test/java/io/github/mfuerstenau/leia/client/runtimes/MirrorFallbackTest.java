package io.github.mfuerstenau.leia.client.runtimes;

import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.built;
import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.known;
import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.lucene;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A download from mirrors (ADR 0124): the next mirror after one that is down, serves a wrong jar or
 * stalls, the mirror chosen after a failure, and a stop.
 */
class MirrorFallbackTest {

    @TempDir
    Path tmp;

    private FakeRepository first;
    private FakeRepository second;
    private Path runtimes;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void serve() throws IOException {
        first = new FakeRepository();
        second = new FakeRepository();
        runtimes = tmp.resolve("runtimes");
    }

    @AfterEach
    void stop() {
        first.close();
        second.close();
    }

    @Test
    void goesOnWithTheNextMirrorAfterOneThatIsDown() throws Exception {
        Mirror down = new Mirror(closedPort(), "Down", Mirror.Origin.FILE);

        int status = download(List.of(down, mirror(second, "Second")), List.of("lucene7"), MirrorChooser.fallback());

        assertThat(status).isZero();
        assertInstalled("lucene7");
        assertThat(error()).contains("cannot connect", "(Down)");
        assertThat(output()).contains("lucene7/lucene-core-7.7.3.jar: downloaded, ", ", from Second");
        assertThat(output()).doesNotContainPattern("downloaded, .*, from Down");
    }

    @Test
    void goesOnWithTheNextMirrorAfterAWrongJarAndStaysWithIt() throws Exception {
        RuntimeFolders.ListedJar firstJar = lucene(known("lucene7")).getFirst();
        first.served.put(firstJar.mavenPath().orElseThrow(), "not lucene".getBytes(StandardCharsets.UTF_8));

        int status = download(
                List.of(mirror(first, "First"), mirror(second, "Second")),
                List.of("lucene7"),
                MirrorChooser.fallback());

        assertThat(status).isZero();
        assertInstalled("lucene7");
        assertThat(error()).contains("the download of " + firstJar.name(), "doesn't match", "(First)");
        // The mirror that worked is the one the install goes on with.
        assertThat(first.requests).hasValue(1);
        assertThat(second.requests).hasValue(lucene(known("lucene7")).size());
        assertNoPart(runtimes.resolve("lucene7"));
    }

    @Test
    void goesOnWithTheNextMirrorAfterOneThatSendsNothing() throws Exception {
        first.holdBack();
        RuntimeInstall install = new RuntimeInstall(
                runtimes,
                List.of(mirror(first, "Slow"), mirror(second, "Second")),
                MirrorFallbackTest.class.getClassLoader(),
                Duration.ofSeconds(1));

        int status = RuntimeDownload.run(install, List.of("lucene7"), print(out), print(err), MirrorChooser.fallback());

        assertThat(status).isZero();
        assertInstalled("lucene7");
        assertThat(error()).contains("no bytes for 1 s", "(Slow)");
        assertNoPart(runtimes.resolve("lucene7"));
    }

    @Test
    void failsTheRuntimeOnlyOnceEveryMirrorFailedAndInstallsTheOthers() throws Exception {
        String path = "org/apache/lucene/lucene-core/7.7.3/lucene-core-7.7.3.jar";
        first.served.remove(path);
        second.served.remove(path);

        int status = download(
                List.of(mirror(first, "First"), mirror(second, "Second")),
                List.of("lucene7", "lucene5"),
                MirrorChooser.fallback());

        assertThat(status).isEqualTo(1);
        assertThat(error())
                .contains("leia: cannot download lucene-core-7.7.3.jar from any of 2 mirrors: First: HTTP 404;"
                        + " Second: HTTP 404");
        assertThat(output()).contains("lucene7: failed: ", "lucene5: installed");
        assertInstalled("lucene5");
        assertThat(runtimes.resolve("lucene7/lucene-core-7.7.3.jar")).doesNotExist();
        assertNoPart(runtimes.resolve("lucene7"));
    }

    @Test
    void retriesFromTheMirrorChosenAndGoesOnWithIt() throws Exception {
        RuntimeFolders.ListedJar firstJar = lucene(known("lucene7")).getFirst();
        first.served.remove(firstJar.mavenPath().orElseThrow());
        Mirror chosen = mirror(second, "Chosen");
        List<MirrorChooser.Failure> failures = new ArrayList<>();
        List<Set<URI>> tried = new ArrayList<>();

        int status = download(
                List.of(mirror(first, "First"), new Mirror(closedPort(), "Unused", Mirror.Origin.FILE)),
                List.of("lucene7"),
                (failure, mirrors, triedFrom) -> {
                    failures.add(failure);
                    tried.add(triedFrom);
                    return new MirrorChooser.Choice.Retry(chosen);
                });

        assertThat(status).isZero();
        assertInstalled("lucene7");
        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.folder()).isEqualTo("lucene7");
            assertThat(failure.jar()).isEqualTo(firstJar.name());
            assertThat(failure.mirror().name()).isEqualTo("First");
            assertThat(failure.reason()).isEqualTo("HTTP 404");
        });
        assertThat(tried).containsExactly(Set.of(first.base()));
        assertThat(first.requests).hasValue(1);
        assertThat(second.requests).hasValue(lucene(known("lucene7")).size());
        assertThat(output()).contains(", from Chosen");
    }

    @Test
    void anAbortFailsTheRuntimeAndCancelsTheRest() throws Exception {
        String path = "org/apache/lucene/lucene-core/7.7.3/lucene-core-7.7.3.jar";
        first.served.remove(path);
        RuntimeInstall install = install(List.of(mirror(first, "First"), mirror(second, "Second")));
        RuntimeInstall.Plan plan = install.plan(List.of(known("lucene7"), known("lucene5")));

        RuntimeInstall.Outcome outcome = install.install(
                plan,
                Set.of(known("lucene7"), known("lucene5")),
                RuntimeInstall.Listener.printing(print(out), print(err)),
                () -> false,
                (failure, mirrors, tried) -> new MirrorChooser.Choice.Abort());

        assertThat(outcome.status()).isEqualTo(1);
        assertThat(outcome.results().get(known("lucene7")))
                .isInstanceOfSatisfying(
                        RuntimeInstall.Result.Failed.class,
                        failed -> assertThat(failed.message()).contains("HTTP 404", "the download was stopped"));
        assertThat(outcome.results().get(known("lucene5"))).isEqualTo(new RuntimeInstall.Result.Cancelled());
        assertThat(runtimes.resolve("lucene5")).doesNotExist();
        assertNoPart(runtimes.resolve("lucene7"));
        assertThat(second.requests).hasValue(0);
    }

    @Test
    void aCancelWhileTheChooserIsAskedCancels() throws Exception {
        first.served.remove("org/apache/lucene/lucene-core/7.7.3/lucene-core-7.7.3.jar");
        RuntimeInstall install = install(List.of(mirror(first, "First"), mirror(second, "Second")));
        RuntimeInstall.Plan plan = install.plan(List.of(known("lucene7")));
        boolean[] cancelled = {false};

        RuntimeInstall.Outcome outcome = install.install(
                plan,
                Set.of(known("lucene7")),
                new RuntimeInstall.Listener() {},
                () -> cancelled[0],
                (failure, mirrors, tried) -> {
                    cancelled[0] = true;
                    return new MirrorChooser.Choice.Retry(mirrors.get(1));
                });

        assertThat(outcome.results()).containsEntry(known("lucene7"), new RuntimeInstall.Result.Cancelled());
        assertThat(second.requests).hasValue(0);
    }

    @Test
    void listsTheMirrorsInTheirOrder() throws Exception {
        assertThat(download(
                        List.of(mirror(first, "First"), mirror(second, "Second")),
                        List.of("lucene7"),
                        MirrorChooser.fallback()))
                .isZero();

        assertThat(output())
                .contains("from First (" + first.base() + ") (--download-url), or, when a download fails, from 1"
                        + " other mirror")
                .contains("The mirrors, in the order they are tried when a download fails:")
                .contains("  1. First  " + first.base() + " (--download-url)")
                .contains("  2. Second  " + second.base());
    }

    @Test
    void theConsoleAsksForANumberOfTheList() {
        List<Mirror> mirrors = List.of(mirror(first, "First"), mirror(second, "Second"));
        MirrorChooser.Failure failure =
                new MirrorChooser.Failure("lucene7", "lucene-core-7.7.3.jar", mirrors.getFirst(), "HTTP 404", "");

        assertThat(console("x\n9\n 2 \n").choose(failure, mirrors, Set.of(first.base())))
                .isEqualTo(new MirrorChooser.Choice.Retry(mirrors.get(1)));
        assertThat(output())
                .contains("Retry lucene7/lucene-core-7.7.3.jar from which mirror?")
                .contains("  1. First  " + first.base() + "  (failed)")
                .contains("  2. Second  " + second.base() + System.lineSeparator())
                .contains("Mirror [1-2], or a to abort: ");
        assertThat(console("A\n").choose(failure, mirrors, Set.of())).isEqualTo(new MirrorChooser.Choice.Abort());
        assertThat(console("").choose(failure, mirrors, Set.of())).isEqualTo(new MirrorChooser.Choice.Abort());
    }

    @Test
    void theFallbackTriesEachMirrorOnceInOrder() {
        List<Mirror> mirrors = List.of(mirror(first, "First"), mirror(second, "Second"));
        MirrorChooser.Failure failure = new MirrorChooser.Failure("lucene7", "a.jar", mirrors.getFirst(), "", "");

        assertThat(MirrorChooser.fallback().choose(failure, mirrors, Set.of(first.base())))
                .isEqualTo(new MirrorChooser.Choice.Retry(mirrors.get(1)));
        assertThat(MirrorChooser.fallback().choose(failure, mirrors, Set.of(second.base())))
                .isEqualTo(new MirrorChooser.Choice.Retry(mirrors.getFirst()));
        assertThat(MirrorChooser.fallback().choose(failure, mirrors, Set.of(first.base(), second.base())))
                .isEqualTo(new MirrorChooser.Choice.Skip());
    }

    private ConsoleChooser console(String input) {
        return new ConsoleChooser(new BufferedReader(new StringReader(input)), print(out));
    }

    private int download(List<Mirror> mirrors, List<String> folders, MirrorChooser chooser) {
        return RuntimeDownload.run(install(mirrors), folders, print(out), print(err), chooser);
    }

    private RuntimeInstall install(List<Mirror> mirrors) {
        return new RuntimeInstall(runtimes, mirrors, MirrorFallbackTest.class.getClassLoader());
    }

    private static Mirror mirror(FakeRepository repository, String name) {
        return new Mirror(repository.base(), name, Mirror.Origin.OPTION);
    }

    /** A base on loopback where nothing listens. */
    private static URI closedPort() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        return URI.create("http://127.0.0.1:" + port + "/maven2/");
    }

    private void assertInstalled(String folder) throws IOException {
        RuntimeFolders.Known known = known(folder);
        for (RuntimeFolders.ListedJar jar : lucene(known)) {
            assertThat(Files.mismatch(
                            runtimes.resolve(folder).resolve(jar.name()),
                            built(known).resolve(jar.name())))
                    .as(jar.name())
                    .isEqualTo(-1L);
        }
    }

    private static void assertNoPart(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files).noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        }
    }

    private static PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String error() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
