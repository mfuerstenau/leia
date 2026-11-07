package io.github.mfuerstenau.leia.tui.runtimes;

import static io.github.mfuerstenau.leia.tui.runtimes.FakeRepository.built;
import static io.github.mfuerstenau.leia.tui.runtimes.FakeRepository.known;
import static io.github.mfuerstenau.leia.tui.runtimes.FakeRepository.lucene;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lucene jars taken from the local Maven and Gradle caches instead of a download (ADR 0125). */
class LocalCachesTest {

    private static final Function<String, String> NONE = name -> null;

    @TempDir
    Path tmp;

    private Path home;
    private FakeRepository repository;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        repository = new FakeRepository();
    }

    @AfterEach
    void stop() {
        repository.close();
    }

    @Test
    void findsAJarInMavensLocalRepository() throws Exception {
        RuntimeFolders.ListedJar core = core7();
        Path file = putMaven(home.resolve(".m2/repository"), core);

        LocalCaches caches = LocalCaches.detect(NONE, NONE, Optional.of(home));

        assertThat(caches.caches()).extracting(LocalCaches.Cache::name).containsExactly("Maven's local repository");
        assertThat(caches.find(core.mavenPath().orElseThrow(), core))
                .hasValueSatisfying(local -> assertThat(local.file()).isEqualTo(file));
    }

    @Test
    void findsTheCopyThatMatchesAmongGradlesHashFolders() throws Exception {
        RuntimeFolders.ListedJar core = core7();
        Path version = Files.createDirectories(
                home.resolve(".gradle/caches/modules-2/files-2.1/org.apache.lucene/lucene-core/7.7.3"));
        Files.createDirectories(version.resolve("0000wrong"));
        Files.write(version.resolve("0000wrong").resolve(core.name()), new byte[(int) core.size()]);
        Path good = Files.createDirectories(version.resolve("ffffgood")).resolve(core.name());
        Files.copy(built(known("lucene7")).resolve(core.name()), good);

        LocalCaches caches = LocalCaches.detect(NONE, NONE, Optional.of(home));

        assertThat(caches.caches()).extracting(LocalCaches.Cache::name).containsExactly("Gradle's cache");
        assertThat(caches.find(core.mavenPath().orElseThrow(), core))
                .hasValueSatisfying(local -> assertThat(local.file()).isEqualTo(good));
    }

    @Test
    void takesGradleUserHomeFromTheEnvironment() throws Exception {
        Path gradleHome = Files.createDirectories(tmp.resolve("gradle-home/caches/modules-2/files-2.1"));
        Function<String, String> env =
                Map.of("GRADLE_USER_HOME", tmp.resolve("gradle-home").toString())::get;

        assertThat(LocalCaches.detect(env, NONE, Optional.of(home)).caches())
                .extracting(LocalCaches.Cache::root)
                .containsExactly(gradleHome);
    }

    @Test
    void takesMavenRepoLocalThenTheSettingsLocalRepository() throws Exception {
        Path settingsRepository = Files.createDirectories(home.resolve("own-repository"));
        Files.createDirectories(home.resolve(".m2"));
        Files.writeString(home.resolve(".m2/settings.xml"), """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <localRepository>${user.home}/own-repository</localRepository>
                </settings>
                """);
        assertThat(LocalCaches.detect(NONE, NONE, Optional.of(home)).caches())
                .extracting(LocalCaches.Cache::root)
                .containsExactly(settingsRepository);

        Path property = Files.createDirectories(tmp.resolve("repo-local"));
        Function<String, String> properties = Map.of("maven.repo.local", property.toString())::get;
        assertThat(LocalCaches.detect(NONE, properties, Optional.of(home)).caches())
                .extracting(LocalCaches.Cache::root)
                .containsExactly(property);
    }

    @Test
    void refusesASettingsFileWithADoctypeAndFallsBackToTheDefault() throws Exception {
        Path repository = Files.createDirectories(home.resolve(".m2/repository"));
        Files.writeString(home.resolve(".m2/settings.xml"), """
                <!DOCTYPE settings [<!ENTITY x SYSTEM "file:///etc/hostname">]>
                <settings><localRepository>&x;</localRepository></settings>
                """);

        assertThat(LocalCaches.detect(NONE, NONE, Optional.of(home)).caches())
                .extracting(LocalCaches.Cache::root)
                .containsExactly(repository);
    }

    @Test
    void isOffWithThePropertyAndWithoutCaches() throws Exception {
        putMaven(home.resolve(".m2/repository"), core7());

        assertThat(LocalCaches.detect(NONE, Map.of(LocalCaches.PROPERTY, "false")::get, Optional.of(home))
                        .caches())
                .isEmpty();
        assertThat(LocalCaches.detect(NONE, NONE, Optional.of(tmp.resolve("nobody")))
                        .caches())
                .isEmpty();
        assertThat(LocalCaches.detect(NONE, NONE, Optional.empty()).caches()).isEmpty();
    }

    @Test
    void ignoresACopyOfAnotherSizeOrHash() throws Exception {
        RuntimeFolders.ListedJar core = core7();
        Path file = putMaven(home.resolve(".m2/repository"), core);
        LocalCaches caches = LocalCaches.detect(NONE, NONE, Optional.of(home));

        Files.write(file, new byte[(int) core.size()]);
        assertThat(caches.find(core.mavenPath().orElseThrow(), core)).isEmpty();
        Files.writeString(file, "short");
        assertThat(caches.find(core.mavenPath().orElseThrow(), core)).isEmpty();
    }

    @Test
    void copiesTheJarsACacheHasAndDownloadsNone() throws Exception {
        for (RuntimeFolders.ListedJar jar : lucene(known("lucene7"))) {
            putMaven(home.resolve(".m2/repository"), jar);
        }
        RuntimeInstall install = install();

        assertThat(RuntimeDownload.run(install, List.of("lucene7"), print(out), print(err)))
                .isZero();

        assertThat(repository.requests).hasValue(0);
        assertInstalled();
        assertThat(output())
                .contains("    copied from " + home.resolve(".m2/repository/org/apache/lucene/lucene-core/7.7.3"))
                .contains(lucene(known("lucene7")).size() + " jars, ")
                .contains(", come from local caches and aren't downloaded; 0 jars, 0 B, are.")
                .contains("lucene7/lucene-core-7.7.3.jar: copied, ")
                .contains(", from Maven's local repository");
        assertThat(install.plan(List.of(known("lucene7"))).runtimes().getFirst().needsDownload())
                .isFalse();
    }

    @Test
    void downloadsAJarWhoseCopyChangedAfterThePlan() throws Exception {
        RuntimeFolders.ListedJar core = core7();
        Path file = putMaven(home.resolve(".m2/repository"), core);
        RuntimeInstall install = install();
        RuntimeInstall.Plan plan = install.plan(List.of(known("lucene7")));
        assertThat(plan.runtimes().getFirst().localCopies())
                .singleElement()
                .satisfies(download -> assertThat(download.jar()).isEqualTo(core));
        assertThat(plan.runtimes().getFirst().downloadSize())
                .isEqualTo(lucene(known("lucene7")).stream()
                                .mapToLong(RuntimeFolders.ListedJar::size)
                                .sum()
                        - core.size());
        Files.write(file, new byte[(int) core.size()]);

        RuntimeInstall.Outcome outcome = install.install(
                plan, Set.of(known("lucene7")), RuntimeInstall.Listener.printing(print(out), print(err)), () -> false);

        assertThat(outcome.status()).isZero();
        assertInstalled();
        assertThat(output())
                .contains("lucene7/lucene-core-7.7.3.jar: downloaded, ")
                .doesNotContain("copied");
        assertThat(repository.requests).hasValue(lucene(known("lucene7")).size());
    }

    private RuntimeInstall install() {
        return new RuntimeInstall(
                home.resolve("runtimes"),
                List.of(new Mirror(repository.base(), "Local", Mirror.Origin.OPTION)),
                LocalCachesTest.class.getClassLoader(),
                LocalCaches.detect(NONE, NONE, Optional.of(home)));
    }

    private void assertInstalled() throws IOException {
        for (RuntimeFolders.ListedJar jar : lucene(known("lucene7"))) {
            assertThat(Files.mismatch(
                            home.resolve("runtimes/lucene7").resolve(jar.name()),
                            built(known("lucene7")).resolve(jar.name())))
                    .as(jar.name())
                    .isEqualTo(-1L);
        }
        try (Stream<Path> files = Files.list(home.resolve("runtimes/lucene7"))) {
            assertThat(files).noneMatch(file -> file.getFileName().toString().endsWith(".part"));
        }
    }

    /** Puts {@code jar} of lucene7 into a Maven repository at {@code root}. */
    private static Path putMaven(Path root, RuntimeFolders.ListedJar jar) throws IOException {
        Path file = root.resolve(jar.mavenPath().orElseThrow());
        Files.createDirectories(file.getParent());
        Files.copy(built(known("lucene7")).resolve(jar.name()), file);
        return file;
    }

    private static RuntimeFolders.ListedJar core7() throws IOException {
        return lucene(known("lucene7")).stream()
                .filter(jar -> jar.name().equals("lucene-core-7.7.3.jar"))
                .findFirst()
                .orElseThrow();
    }

    private static PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }
}
