package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTest {

    private static final Optional<Path> HOME = Optional.of(Path.of("/home/u"));

    @TempDir
    Path tmp;

    @Test
    void usesTheXdgVariables() {
        Platform platform = linux(Map.of("XDG_STATE_HOME", "/state", "XDG_CONFIG_HOME", "/config"), HOME);

        assertThat(platform.stateDir()).hasValue(Path.of("/state/leia"));
        assertThat(platform.logFile()).hasValue(Path.of("/state/leia/leia.log"));
        assertThat(platform.historyFile()).hasValue(Path.of("/state/leia/history"));
        assertThat(platform.defaultJarsDir()).hasValue(Path.of("/config/leia/jars"));
    }

    @Test
    void fallsBackToTheHomeDirectory() {
        Platform platform = linux(Map.of(), HOME);

        assertThat(platform.logFile()).hasValue(Path.of("/home/u/.local/state/leia/leia.log"));
        assertThat(platform.defaultJarsDir()).hasValue(Path.of("/home/u/.config/leia/jars"));
    }

    @Test
    void ignoresRelativeAndEmptyXdgVariables() {
        Platform platform = linux(Map.of("XDG_STATE_HOME", "state", "XDG_CONFIG_HOME", ""), HOME);

        assertThat(platform.stateDir()).hasValue(Path.of("/home/u/.local/state/leia"));
        assertThat(platform.defaultJarsDir()).hasValue(Path.of("/home/u/.config/leia/jars"));
    }

    @Test
    void knowsNoFolderWithoutVariablesOrHome() {
        Platform platform = linux(Map.of(), Optional.empty());

        assertThat(platform.logFile()).isEmpty();
        assertThat(platform.historyFile()).isEmpty();
        assertThat(platform.defaultJarsDir()).isEmpty();
    }

    @Test
    void usesAppDataOnWindows() {
        // A path that is absolute on the platform the test runs on stands for a folder below C:\Users.
        Platform platform = new Platform(
                Map.of("LOCALAPPDATA", "/local", "APPDATA", "/roaming", "XDG_STATE_HOME", "/state")::get,
                HOME,
                true,
                Optional.empty());

        assertThat(platform.logFile()).hasValue(Path.of("/local/leia/leia.log"));
        assertThat(platform.historyFile()).hasValue(Path.of("/local/leia/history"));
        assertThat(platform.defaultJarsDir()).hasValue(Path.of("/roaming/leia/jars"));
    }

    @Test
    void ignoresTheHomeDirectoryOnWindows() {
        Platform platform = new Platform(Map.<String, String>of()::get, HOME, true, Optional.empty());

        assertThat(platform.logFile()).isEmpty();
        assertThat(platform.defaultJarsDir()).isEmpty();
    }

    @Test
    void findsTheRuntimesBesideLeiaJarIfTheyAreThere() throws IOException {
        Path jar = tmp.resolve("leia.jar");
        Path runtimes = Files.createDirectories(tmp.resolve("runtimes"));

        assertThat(withJar(Map.of("XDG_DATA_HOME", "/data"), jar).runtimesDir()).hasValue(runtimes);
    }

    @Test
    void findsTheRuntimesInTheDataFolderOtherwise() {
        Path jar = tmp.resolve("leia.jar");

        assertThat(withJar(Map.of("XDG_DATA_HOME", "/data"), jar).runtimesDir())
                .hasValue(Path.of("/data/leia/runtimes"));
        assertThat(withJar(Map.of(), jar).runtimesDir()).hasValue(Path.of("/home/u/.local/share/leia/runtimes"));
        assertThat(withJar(Map.of("XDG_DATA_HOME", "data"), jar).runtimesDir())
                .hasValue(Path.of("/home/u/.local/share/leia/runtimes"));
        assertThat(new Platform(
                                Map.of("LOCALAPPDATA", "/local", "XDG_DATA_HOME", "/data")::get,
                                HOME,
                                true,
                                Optional.of(jar))
                        .runtimesDir())
                .hasValue(Path.of("/local/leia/runtimes"));
        assertThat(new Platform(Map.<String, String>of()::get, Optional.empty(), false, Optional.of(jar)).runtimesDir())
                .isEmpty();
    }

    @Test
    void findsNoRuntimesWithoutLeiaJar() throws IOException {
        Files.createDirectories(tmp.resolve("runtimes"));

        assertThat(linux(Map.of("XDG_DATA_HOME", "/data"), HOME).runtimesDir()).isEmpty();
    }

    @Test
    void findsTheRuntimesThatThePropertyNames() {
        String before = System.getProperty("leia.runtimes");
        System.setProperty("leia.runtimes", tmp.resolve("elsewhere").toString());
        try {
            assertThat(withJar(Map.of("XDG_DATA_HOME", "/data"), tmp.resolve("leia.jar"))
                            .runtimesDir())
                    .hasValue(tmp.resolve("elsewhere"));
            assertThat(linux(Map.of(), HOME).runtimesDir()).hasValue(tmp.resolve("elsewhere"));
        } finally {
            if (before == null) {
                System.clearProperty("leia.runtimes");
            } else {
                System.setProperty("leia.runtimes", before);
            }
        }
    }

    @Test
    void findsTheJarOfAClassOnlyIfItIsInOne() {
        // The test's own classes are in a directory, JUnit's in a jar.
        assertThat(Platform.jarOf(PlatformTest.class)).isEmpty();
        assertThat(Platform.jarOf(Test.class))
                .hasValueSatisfying(
                        jar -> assertThat(jar.getFileName().toString()).endsWith(".jar"));
        // From the boot class path, without a code source.
        assertThat(Platform.jarOf(String.class)).isEmpty();
    }

    private static Platform withJar(Map<String, String> env, Path jar) {
        return new Platform(env::get, HOME, false, Optional.of(jar));
    }

    private static Platform linux(Map<String, String> env, Optional<Path> home) {
        return new Platform(env::get, home, false, Optional.empty());
    }
}
