package io.github.mfuerstenau.leia.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LauncherTest {

    @Test
    void readsTheFeatureRelease() {
        assertThat(Launcher.feature("1.8")).isEqualTo(8);
        assertThat(Launcher.feature("11")).isEqualTo(11);
        assertThat(Launcher.feature("25")).isEqualTo(25);
        assertThat(Launcher.feature("nonsense")).isZero();
    }
}
