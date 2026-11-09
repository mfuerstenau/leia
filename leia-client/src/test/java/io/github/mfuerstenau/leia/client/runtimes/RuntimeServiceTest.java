package io.github.mfuerstenau.leia.client.runtimes;

import static io.github.mfuerstenau.leia.client.runtimes.FakeRepository.known;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a UI needs of the runtimes' install, apart from the install's thread (ADR 0080, ADR 0126). */
class RuntimeServiceTest {

    @TempDir
    Path tmp;

    private FakeRepository repository;

    @BeforeEach
    void serve() throws IOException {
        repository = new FakeRepository();
    }

    @AfterEach
    void stop() {
        repository.close();
    }

    @Test
    void plansTheChosenAndThePresentRuntimesApart() throws Exception {
        RuntimeService service = service(RuntimeServiceTest.class.getClassLoader(), null);

        RuntimeService.Plans plans = service.plan(List.of(known("lucene9")), List.of(known("lucene7")))
                .get(30, TimeUnit.SECONDS);

        assertThat(plans.plan().runtimes())
                .extracting(RuntimeInstall.RuntimePlan::known)
                .containsExactly(known("lucene9"));
        assertThat(plans.present())
                .extracting(RuntimeInstall.RuntimePlan::known)
                .containsExactly(known("lucene7"));
        assertThat(Files.exists(tmp.resolve("runtimes"))).isFalse();
    }

    @Test
    void aPlanThatFailsSaysWhy() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            RuntimeService service = service(empty, null);

            assertThat(service.plan(List.of(known("lucene9")), List.of()))
                    .failsWithin(30, TimeUnit.SECONDS)
                    .withThrowableOfType(ExecutionException.class)
                    .withCauseInstanceOf(RuntimeInstall.InstallException.class);
        }
    }

    @Test
    void theMirrorUsedIsFirstFromNowOnAndSaved() throws Exception {
        Path saved = tmp.resolve("config/mirror");
        RuntimeService service = service(RuntimeServiceTest.class.getClassLoader(), saved);
        Mirror other = new Mirror(URI.create("https://mirror.example/maven2/"), "Other", Mirror.Origin.TYPED);

        service.useMirror(other);

        assertThat(service.install().mirrors().getFirst()).isEqualTo(other);
        assertThat(service.install().mirrors()).hasSize(2);
        assertThat(Files.readString(saved)).contains("https://mirror.example/maven2/");
    }

    @Test
    void anInstallJobUsesTheMirrorsAsTheyAreThen() throws Exception {
        RuntimeService service = service(RuntimeServiceTest.class.getClassLoader(), null);
        RuntimeService.Plans plans =
                service.plan(List.of(known("lucene7")), List.of()).get(30, TimeUnit.SECONDS);

        InstallJob job = service.job(plans.plan(), Set.of(known("lucene7")));

        assertThat(job.start().await().status()).isZero();
    }

    @Test
    void namesTheMirrorsLeftOut() {
        RuntimeService service = new RuntimeService(
                new RuntimeInstall(
                        tmp.resolve("runtimes"), repository.base(), RuntimeServiceTest.class.getClassLoader()),
                new MirrorSettings(List.of("mirrors, line 2: not a URL"), null));

        assertThat(service.mirrorProblems()).containsExactly("mirrors, line 2: not a URL");
    }

    private RuntimeService service(ClassLoader resources, @Nullable Path saveFile) {
        return new RuntimeService(
                new RuntimeInstall(
                        tmp.resolve("runtimes"),
                        List.of(new Mirror(repository.base(), "Fake", Mirror.Origin.OPTION)),
                        resources),
                new MirrorSettings(List.of(), saveFile));
    }
}
