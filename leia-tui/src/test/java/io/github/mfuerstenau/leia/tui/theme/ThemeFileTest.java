package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(new ThemeFile(dir.resolve("theme")).load(THEMES)).isEmpty();
        assertThat(ThemeFile.none().load(THEMES)).isEmpty();
    }

    @Test
    void anUnknownIdIsNoTheme() throws Exception {
        Path file = dir.resolve("theme");
        Files.writeString(file, "solarized\n");

        assertThat(new ThemeFile(file).load(THEMES)).isEmpty();
    }

    @Test
    void savesAndLoadsTheTheme() throws Exception {
        Path file = dir.resolve("leia").resolve("theme");
        ThemeFile themeFile = new ThemeFile(file);

        themeFile.save(NORD);

        assertThat(file).hasContent("nord");
        assertThat(themeFile.load(THEMES)).hasValue(NORD);
        try (var files = Files.list(file.getParent())) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    void readsTheSystemsDefaultAndSavesToTheUsersFolder() throws Exception {
        Path system = dir.resolve("etc/leia/theme");
        Files.createDirectories(system.getParent());
        Files.writeString(system, "light\n");
        Path user = dir.resolve("home/leia/theme");
        ThemeFile themeFile = new ThemeFile(system, user);

        assertThat(themeFile.load(THEMES)).hasValue(LIGHT);
        themeFile.save(NORD);

        assertThat(user).hasContent("nord");
        assertThat(system).hasContent("light\n");
    }

    @Test
    void aFailedWriteIsIgnored() throws Exception {
        Path notADir = dir.resolve("file");
        Files.writeString(notADir, "");
        ThemeFile themeFile = new ThemeFile(notADir.resolve("theme"));

        themeFile.save(LIGHT);

        assertThat(themeFile.load(THEMES)).isEmpty();
        ThemeFile.none().save(LIGHT);
    }

    @Test
    void resolvesTheOptionThenNoColorThenTheFileThenDark() {
        Path file = dir.resolve("theme");
        ThemeFile themeFile = new ThemeFile(file);

        assertThat(themeFile.resolve(null, null, THEMES)).isEqualTo(DARK);
        themeFile.save(LIGHT);
        assertThat(themeFile.resolve(null, null, THEMES)).isEqualTo(LIGHT);
        // NO_COLOR counts only when it is set and not empty (no-color.org).
        assertThat(themeFile.resolve(null, "", THEMES)).isEqualTo(LIGHT);
        assertThat(themeFile.resolve(null, "1", THEMES)).isEqualTo(MONO);
        assertThat(themeFile.resolve(NORD, "1", THEMES)).isEqualTo(NORD);
    }

    @Test
    void keepsAThemeOfTheUsersOwn() throws Exception {
        Path themesDir = Files.createDirectories(dir.resolve("themes"));
        try (var nord = Themes.class.getResourceAsStream("nord.theme")) {
            Files.copy(nord, themesDir.resolve("solar.theme"));
        }
        Themes themes = THEMES.withUserDir(themesDir);
        Theme solar = themes.find("solar").orElseThrow();
        ThemeFile themeFile = new ThemeFile(dir.resolve("theme"));

        themeFile.save(solar);

        assertThat(themeFile.load(themes)).hasValue(solar);
        // Without the user's folder, the theme it names is gone, and dark is the one to start with.
        assertThat(themeFile.load(THEMES)).isEmpty();
        assertThat(themeFile.resolve(null, null, THEMES)).isEqualTo(DARK);
    }

    @Test
    void noColorChoosesTheUsersMonoIfItReplacesTheBundledOne() throws Exception {
        Path themesDir = Files.createDirectories(dir.resolve("themes"));
        String mono;
        try (var in = Themes.class.getResourceAsStream("mono.theme")) {
            mono = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(
                themesDir.resolve("mono.theme"),
                mono.replace("logo          = gazelle-mono", "logo          = gazelle"));
        Themes themes = THEMES.withUserDir(themesDir);

        Theme chosen = ThemeFile.none().resolve(null, "1", themes);

        assertThat(chosen.id()).isEqualTo("mono");
        assertThat(chosen.palette().logo()).isEqualTo(DARK.palette().logo());
    }
}
