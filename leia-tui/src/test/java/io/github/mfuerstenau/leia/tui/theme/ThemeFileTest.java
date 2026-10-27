package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeFileTest {

    private static final Themes THEMES = Themes.bundled();
    private static final Theme DARK = THEMES.find("dark").orElseThrow();
    private static final Theme LIGHT = THEMES.find("light").orElseThrow();
    private static final Theme MONO = THEMES.find("mono").orElseThrow();
    private static final Theme NORD = THEMES.find("nord").orElseThrow();

    @TempDir
    Path dir;

    @Test
    void aMissingFileIsNoTheme() {
        assertThat(new ThemeFile(Optional.of(dir.resolve("theme"))).load(THEMES))
                .isEmpty();
        assertThat(ThemeFile.none().load(THEMES)).isEmpty();
    }

    @Test
    void anUnknownIdIsNoTheme() throws Exception {
        Path file = dir.resolve("theme");
        Files.writeString(file, "solarized\n");

        assertThat(new ThemeFile(Optional.of(file)).load(THEMES)).isEmpty();
    }

    @Test
    void savesAndLoadsTheTheme() throws Exception {
        Path file = dir.resolve("leia").resolve("theme");
        ThemeFile themeFile = new ThemeFile(Optional.of(file));

        themeFile.save(NORD);

        assertThat(file).hasContent("nord");
        assertThat(themeFile.load(THEMES)).hasValue(NORD);
        try (var files = Files.list(file.getParent())) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    void aFailedWriteIsIgnored() throws Exception {
        Path notADir = dir.resolve("file");
        Files.writeString(notADir, "");
        ThemeFile themeFile = new ThemeFile(Optional.of(notADir.resolve("theme")));

        themeFile.save(LIGHT);

        assertThat(themeFile.load(THEMES)).isEmpty();
        ThemeFile.none().save(LIGHT);
    }

    @Test
    void resolvesTheOptionThenNoColorThenTheFileThenDark() {
        Path file = dir.resolve("theme");
        ThemeFile themeFile = new ThemeFile(Optional.of(file));

        assertThat(themeFile.resolve(Optional.empty(), null, THEMES)).isEqualTo(DARK);
        themeFile.save(LIGHT);
        assertThat(themeFile.resolve(Optional.empty(), null, THEMES)).isEqualTo(LIGHT);
        // NO_COLOR counts only when it is set and not empty (no-color.org).
        assertThat(themeFile.resolve(Optional.empty(), "", THEMES)).isEqualTo(LIGHT);
        assertThat(themeFile.resolve(Optional.empty(), "1", THEMES)).isEqualTo(MONO);
        assertThat(themeFile.resolve(Optional.of(NORD), "1", THEMES)).isEqualTo(NORD);
    }

    @Test
    void keepsAThemeOfTheUsersOwn() throws Exception {
        Path themesDir = Files.createDirectories(dir.resolve("themes"));
        try (var nord = Themes.class.getResourceAsStream("nord.theme")) {
            Files.copy(nord, themesDir.resolve("solar.theme"));
        }
        Themes themes = THEMES.withUserDir(Optional.of(themesDir));
        Theme solar = themes.find("solar").orElseThrow();
        ThemeFile themeFile = new ThemeFile(Optional.of(dir.resolve("theme")));

        themeFile.save(solar);

        assertThat(themeFile.load(themes)).hasValue(solar);
        // Without the user's folder, the theme it names is gone, and dark is the one to start with.
        assertThat(themeFile.load(THEMES)).isEmpty();
        assertThat(themeFile.resolve(Optional.empty(), null, THEMES)).isEqualTo(DARK);
    }

    @Test
    void noColorChoosesTheUsersMonoIfItReplacesTheBundledOne() throws Exception {
        Path themesDir = Files.createDirectories(dir.resolve("themes"));
        String mono;
        try (var in = Themes.class.getResourceAsStream("mono.theme")) {
            mono = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(themesDir.resolve("mono.theme"), mono.replace("logo          = plain", "logo = coloured"));
        Themes themes = THEMES.withUserDir(Optional.of(themesDir));

        Theme chosen = ThemeFile.none().resolve(Optional.empty(), "1", themes);

        assertThat(chosen.id()).isEqualTo("mono");
        assertThat(chosen.palette().colouredLogo()).isTrue();
    }
}
