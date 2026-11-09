package io.github.mfuerstenau.leia.client.runtimes;

import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.known;
import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.lucene;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.client.runtimes.InstallJob.JarProgress;
import io.github.mfuerstenau.leia.client.runtimes.InstallJob.JarState;
import io.github.mfuerstenau.leia.client.runtimes.InstallJob.RuntimeProgress;
import io.github.mfuerstenau.leia.client.runtimes.InstallJob.Snapshot;
import io.github.mfuerstenau.leia.client.runtimes.InstallJob.State;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The install as a handle that a UI polls (ADR 0126): its snapshot, the mirror question of ADR 0124
 * answered through {@link InstallJob#choose}, and the cancel of ADR 0080.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InstallJobTest {

    @TempDir
    Path tmp;

    private FakeRepository first;
    private FakeRepository second;
    private Path runtimes;

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
    void isReadyBeforeItStartsWithEveryJarWaiting() throws Exception {
        RuntimeInstall install = install(mirror(first, "First"));
        RuntimeInstall.Plan plan = install.plan(List.of(known("lucene9")));

        Snapshot snapshot =
                InstallJob.of(install, plan, Set.of(known("lucene9"))).snapshot();

        assertThat(snapshot.state()).isEqualTo(State.READY);
        assertThat(snapshot.runtimes()).singleElement().satisfies(runtime -> {
            assertThat(runtime.known()).isEqualTo(known("lucene9"));
            assertThat(runtime.failure()).isNull();
            assertThat(runtime.jars())
                    .extracting(JarProgress::name)
                    .containsExactlyElementsOf(lucene(known("lucene9")).stream()
                            .map(RuntimeFolders.ListedJar::name)
                            .toList());
            assertThat(runtime.jars()).allMatch(jar -> jar.state() == JarState.WAITING && jar.bytes() == 0);
        });
        assertThat(snapshot.total()).isEqualTo(plan.runtimes().getFirst().downloadSize());
        assertThat(snapshot.done()).isZero();
        assertThat(snapshot.jarCount()).isEqualTo(lucene(known("lucene9")).size());
        assertThat(snapshot.question()).isNull();
        assertThat(snapshot.outcome()).isNull();
        assertThat(snapshot.active()).isEmpty();
    }

    @Test
    void installsAndEndsDoneWithItsOutcome() throws Exception {
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene7"))), Set.of(known("lucene7")));

        RuntimeInstall.Outcome outcome = job.start().await();

        assertThat(outcome.results()).containsEntry(known("lucene7"), new RuntimeInstall.Result.Installed());
        assertThat(job.done()).isCompletedWithValue(outcome);
        Snapshot snapshot = job.snapshot();
        assertThat(snapshot.state()).isEqualTo(State.DONE);
        assertThat(snapshot.outcome()).isEqualTo(outcome);
        assertThat(snapshot.done()).isEqualTo(snapshot.total());
        assertThat(snapshot.runtimes().getFirst().jars()).allSatisfy(jar -> {
            assertThat(jar.state()).isEqualTo(JarState.DOWNLOADED);
            assertThat(jar.bytes()).isEqualTo(jar.size());
            assertThat(jar.source()).isEqualTo("First");
        });
    }

    @Test
    void showsTheActiveJarAndItsBytesWhileItDownloads() throws Exception {
        first.holdBack();
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene9"))), Set.of(known("lucene9")))
                .start();

        Snapshot running = awaitSnapshot(job, snapshot -> snapshot.done() > 0);

        assertThat(running.state()).isEqualTo(State.RUNNING);
        assertThat(running.active()).hasValueSatisfying(jar -> {
            assertThat(jar.state()).isEqualTo(JarState.ACTIVE);
            assertThat(jar.bytes()).isPositive().isLessThanOrEqualTo(FakeRepository.CHUNK);
        });
        first.release();
        assertThat(job.await().status()).isZero();
    }

    @Test
    void asksWhichMirrorToRetryFromAndGoesOnWithTheOneChosen() throws Exception {
        RuntimeFolders.ListedJar missing = lucene(known("lucene7")).getFirst();
        first.served.remove(missing.mavenPath().orElseThrow());
        Mirror chosen = mirror(second, "Second");
        RuntimeInstall install = install(mirror(first, "First"), chosen);
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene7"))), Set.of(known("lucene7")))
                .start();

        Snapshot asking = awaitSnapshot(job, snapshot -> snapshot.state() == State.WAITING_FOR_MIRROR);

        assertThat(asking.question()).satisfies(question -> {
            assertThat(question.failure().folder()).isEqualTo("lucene7");
            assertThat(question.failure().jar()).isEqualTo(missing.name());
            assertThat(question.failure().reason()).isEqualTo("HTTP 404");
            assertThat(question.mirrors()).extracting(Mirror::name).containsExactly("First", "Second");
            assertThat(question.tried()).containsExactly(first.base());
        });
        assertThat(jar(asking, missing.name()).state()).isEqualTo(JarState.FAILED);
        assertThat(jar(asking, missing.name()).failures()).containsExactly("First: HTTP 404");

        job.choose(new MirrorChooser.Choice.Retry(chosen));
        assertThat(job.snapshot().question()).isNull();

        RuntimeInstall.Outcome outcome = job.await();
        assertThat(outcome.results()).containsEntry(known("lucene7"), new RuntimeInstall.Result.Installed());
        assertThat(jar(job.snapshot(), missing.name()).state()).isEqualTo(JarState.DOWNLOADED);
        assertThat(jar(job.snapshot(), missing.name()).source()).isEqualTo("Second");
        assertNoPart(runtimes.resolve("lucene7"));
    }

    @Test
    void aRetryStartsTheJarsBytesOver() throws Exception {
        first.holdBack();
        Mirror chosen = mirror(second, "Second");
        RuntimeInstall install = new RuntimeInstall(
                runtimes,
                List.of(mirror(first, "Slow"), chosen),
                InstallJobTest.class.getClassLoader(),
                Duration.ofSeconds(1));
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene7"))), Set.of(known("lucene7")))
                .start();

        Snapshot asking = awaitSnapshot(job, snapshot -> snapshot.question() != null);
        String jar = Objects.requireNonNull(asking.question()).failure().jar();
        // The bytes of the failed try count until the jar starts over.
        assertThat(asking.done()).isPositive();
        assertThat(jar(asking, jar).failures()).containsExactly("Slow: no bytes for 1 s");

        job.choose(new MirrorChooser.Choice.Retry(chosen));
        first.release();

        assertThat(job.await().status()).isZero();
        assertThat(job.snapshot().done()).isEqualTo(job.snapshot().total());
        assertThat(jar(job.snapshot(), jar).bytes())
                .isEqualTo(jar(job.snapshot(), jar).size());
    }

    @Test
    void skipFailsTheRuntimeAndGoesOnWithTheNext() throws Exception {
        first.served.remove(lucene(known("lucene7")).getFirst().mavenPath().orElseThrow());
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(
                        install,
                        install.plan(List.of(known("lucene7"), known("lucene5"))),
                        Set.of(known("lucene7"), known("lucene5")))
                .start();

        awaitSnapshot(job, snapshot -> snapshot.question() != null);
        job.choose(new MirrorChooser.Choice.Skip());

        RuntimeInstall.Outcome outcome = job.await();
        assertThat(outcome.results().get(known("lucene7"))).isInstanceOf(RuntimeInstall.Result.Failed.class);
        assertThat(outcome.results()).containsEntry(known("lucene5"), new RuntimeInstall.Result.Installed());
        assertThat(job.snapshot().runtimes().getFirst().failure()).isNotNull();
    }

    @Test
    void abortCancelsTheRuntimesNotBegunYet() throws Exception {
        first.served.remove(lucene(known("lucene7")).getFirst().mavenPath().orElseThrow());
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(
                        install,
                        install.plan(List.of(known("lucene7"), known("lucene5"))),
                        Set.of(known("lucene7"), known("lucene5")))
                .start();

        awaitSnapshot(job, snapshot -> snapshot.question() != null);
        job.choose(new MirrorChooser.Choice.Abort());

        RuntimeInstall.Outcome outcome = job.await();
        assertThat(outcome.results().get(known("lucene7"))).isInstanceOf(RuntimeInstall.Result.Failed.class);
        assertThat(outcome.results()).containsEntry(known("lucene5"), new RuntimeInstall.Result.Cancelled());
    }

    @Test
    void aCancelWhileTheQuestionIsOpenEndsTheInstall() throws Exception {
        first.served.remove(lucene(known("lucene7")).getFirst().mavenPath().orElseThrow());
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(
                        install,
                        install.plan(List.of(known("lucene7"), known("lucene5"))),
                        Set.of(known("lucene7"), known("lucene5")))
                .start();

        awaitSnapshot(job, snapshot -> snapshot.question() != null);
        job.cancel();

        RuntimeInstall.Outcome outcome = job.await();
        assertThat(outcome.status()).isEqualTo(1);
        assertThat(outcome.results()).containsEntry(known("lucene5"), new RuntimeInstall.Result.Cancelled());
        assertThat(job.snapshot().cancelled()).isTrue();
        assertThat(job.snapshot().question()).isNull();
        assertNoPart(runtimes.resolve("lucene7"));
    }

    @Test
    void aCancelWhileAJarDownloadsMarksItCancelledAndLeavesNoPartBehind() throws Exception {
        first.holdBack();
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene9"))), Set.of(known("lucene9")))
                .start();
        awaitSnapshot(job, snapshot -> snapshot.active().isPresent() && snapshot.done() > 0);

        job.cancel();
        first.release();

        RuntimeInstall.Outcome outcome = job.await();
        assertThat(outcome.results()).containsEntry(known("lucene9"), new RuntimeInstall.Result.Cancelled());
        Snapshot done = job.snapshot();
        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.active()).isEmpty();
        assertThat(done.runtimes().getFirst().jars()).anyMatch(jar -> jar.state() == JarState.CANCELLED);
        assertNoPart(runtimes.resolve("lucene9"));
    }

    @Test
    void choosingWithoutAQuestionIsAnError() throws Exception {
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene7"))), Set.of(known("lucene7")));

        MirrorChooser.Choice skip = new MirrorChooser.Choice.Skip();

        assertThatThrownBy(() -> job.choose(skip)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void awaitingBeforeTheStartIsAnError() throws Exception {
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene7"))), Set.of(known("lucene7")));

        assertThat(job.install()).isSameAs(install);
        assertThatThrownBy(job::await).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anInstallThatThrowsFailsEveryChosenRuntime() throws Exception {
        RuntimeInstall install = install(mirror(first, "First"));
        IllegalStateException broken = new IllegalStateException("a broken listener");
        RuntimeInstall.Listener listener = new RuntimeInstall.Listener() {
            @Override
            public void jarStarted(String folder, String jar, long size) {
                throw broken;
            }
        };
        InstallJob job = InstallJob.of(
                install,
                install.plan(List.of(known("lucene7"))),
                Set.of(known("lucene7")),
                listener,
                MirrorChooser.fallback());

        RuntimeInstall.Outcome outcome = job.start().await();

        assertThat(outcome.results())
                .containsExactly(Map.entry(known("lucene7"), new RuntimeInstall.Result.Failed(broken.toString())));
        assertThat(outcome.status()).isNotZero();
        assertThat(job.snapshot().state()).isEqualTo(State.DONE);
    }

    @Test
    void startsOnlyOnce() throws Exception {
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene7"))), Set.of(known("lucene7")))
                .start();

        assertThatThrownBy(job::start).isInstanceOf(IllegalStateException.class);
        job.await();
    }

    @Test
    void aCancelBeforeTheStartCancelsEveryRuntime() throws Exception {
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(install, install.plan(List.of(known("lucene7"))), Set.of(known("lucene7")));

        job.cancel();

        assertThat(job.start().await().results())
                .containsEntry(known("lucene7"), new RuntimeInstall.Result.Cancelled());
        assertThat(first.requests).hasValue(0);
    }

    @Test
    void aChooserOfItsOwnAnswersOnTheInstallsThreadAndAListenerHearsIt() throws Exception {
        first.served.remove(lucene(known("lucene7")).getFirst().mavenPath().orElseThrow());
        RuntimeInstall install = install(mirror(first, "First"), mirror(second, "Second"));
        AtomicReference<String> failed = new AtomicReference<>();
        RuntimeInstall.Listener listener = new RuntimeInstall.Listener() {
            @Override
            public void mirrorFailed(MirrorChooser.Failure failure) {
                failed.set(failure.mirror().name() + ": " + failure.reason());
            }
        };

        InstallJob job = InstallJob.of(
                install,
                install.plan(List.of(known("lucene7"))),
                Set.of(known("lucene7")),
                listener,
                MirrorChooser.fallback());

        assertThat(job.start().await().status()).isZero();
        assertThat(failed).hasValue("First: HTTP 404");
        assertThat(job.snapshot().question()).isNull();
    }

    @Test
    void snapshotsAreCopiesThatAnyThreadCanRead() throws Exception {
        RuntimeInstall install = install(mirror(first, "First"));
        InstallJob job = InstallJob.of(
                        install,
                        install.plan(List.of(known("lucene9"), known("lucene7"))),
                        Set.of(known("lucene9"), known("lucene7")))
                .start();
        Snapshot before = job.snapshot();

        while (!job.done().isDone()) {
            Snapshot snapshot = job.snapshot();
            assertThat(snapshot.done()).isBetween(0L, snapshot.total());
        }

        assertThat(before.runtimes().getFirst().jars()).anyMatch(jar -> jar.state() != JarState.DOWNLOADED);
        List<RuntimeProgress> shown = before.runtimes();
        assertThatThrownBy(shown::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(job.await().status()).isZero();
    }

    private static JarProgress jar(Snapshot snapshot, String name) {
        return snapshot.runtimes().stream()
                .flatMap(runtime -> runtime.jars().stream())
                .filter(jar -> jar.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Snapshot awaitSnapshot(InstallJob job, Predicate<Snapshot> condition) {
        Instant end = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(end)) {
            Snapshot snapshot = job.snapshot();
            if (condition.test(snapshot)) {
                return snapshot;
            }
            // Until the install ends, or for 10 ms
            job.done().copy().completeOnTimeout(null, 10, TimeUnit.MILLISECONDS).join();
        }
        throw new AssertionError("no such snapshot: " + job.snapshot());
    }

    private RuntimeInstall install(Mirror... mirrors) {
        return new RuntimeInstall(runtimes, List.of(mirrors), InstallJobTest.class.getClassLoader());
    }

    private static Mirror mirror(FakeRepository repository, String name) {
        return new Mirror(repository.base(), name, Mirror.Origin.OPTION);
    }

    private static void assertNoPart(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files).noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        }
    }
}
