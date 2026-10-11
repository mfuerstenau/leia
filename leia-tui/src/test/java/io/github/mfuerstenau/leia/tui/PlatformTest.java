package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlatformTest {

    private static final Optional<Path> HOME = Optional.of(Path.of("/home/u"));

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
                Map.of("LOCALAPPDATA", "/local", "APPDATA", "/roaming", "XDG_STATE_HOME", "/state")::get, HOME, true);

        assertThat(platform.logFile()).hasValue(Path.of("/local/leia/leia.log"));
        assertThat(platform.historyFile()).hasValue(Path.of("/local/leia/history"));
        assertThat(platform.defaultJarsDir()).hasValue(Path.of("/roaming/leia/jars"));
    }

    @Test
    void ignoresTheHomeDirectoryOnWindows() {
        Platform platform = new Platform(Map.<String, String>of()::get, HOME, true);

        assertThat(platform.logFile()).isEmpty();
        assertThat(platform.defaultJarsDir()).isEmpty();
    }

    private static Platform linux(Map<String, String> env, Optional<Path> home) {
        return new Platform(env::get, home, false);
    }
}
