package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeFileTest {

    @TempDir
    Path dir;

    @Test
    void aMissingFileIsNoTheme() {
        assertThat(new ThemeFile(Optional.of(dir.resolve("theme"))).load()).isEmpty();
        assertThat(ThemeFile.none().load()).isEmpty();
    }

    @Test
    void anUnknownIdIsNoTheme() throws Exception {
        Path file = dir.resolve("theme");
        Files.writeString(file, "solarized\n");

        assertThat(new ThemeFile(Optional.of(file)).load()).isEmpty();
    }

    @Test
    void savesAndLoadsTheTheme() throws Exception {
        Path file = dir.resolve("leia").resolve("theme");
        ThemeFile themeFile = new ThemeFile(Optional.of(file));

        themeFile.save(Theme.NORD);

        assertThat(file).hasContent("nord");
        assertThat(themeFile.load()).hasValue(Theme.NORD);
        try (var files = Files.list(file.getParent())) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    void aFailedWriteIsIgnored() throws Exception {
        Path notADir = dir.resolve("file");
        Files.writeString(notADir, "");
        ThemeFile themeFile = new ThemeFile(Optional.of(notADir.resolve("theme")));

        themeFile.save(Theme.LIGHT);

        assertThat(themeFile.load()).isEmpty();
        ThemeFile.none().save(Theme.LIGHT);
    }

    @Test
    void resolvesTheOptionThenNoColorThenTheFileThenDark() {
        Path file = dir.resolve("theme");
        ThemeFile themeFile = new ThemeFile(Optional.of(file));

        assertThat(themeFile.resolve(Optional.empty(), null)).isEqualTo(Theme.DARK);
        themeFile.save(Theme.LIGHT);
        assertThat(themeFile.resolve(Optional.empty(), null)).isEqualTo(Theme.LIGHT);
        // NO_COLOR counts only when it is set and not empty (no-color.org).
        assertThat(themeFile.resolve(Optional.empty(), "")).isEqualTo(Theme.LIGHT);
        assertThat(themeFile.resolve(Optional.empty(), "1")).isEqualTo(Theme.MONO);
        assertThat(themeFile.resolve(Optional.of(Theme.NORD), "1")).isEqualTo(Theme.NORD);
    }
}
