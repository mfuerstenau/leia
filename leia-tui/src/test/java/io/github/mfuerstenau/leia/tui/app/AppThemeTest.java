package io.github.mfuerstenau.leia.tui.app;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.theme.Theme;
import io.github.mfuerstenau.leia.tui.theme.ThemeFile;
import io.github.mfuerstenau.leia.tui.theme.Themes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The themes (ADR 0095, ADR 0096): {@code T} on the start screen, and how a theme paints the screen. */
class AppThemeTest {

    private static final VersionResult VERSION =
            new VersionResult("leia-server", "1", "abc", "t", new JavaRuntime("25", "v", "/h"));

    @TempDir
    Path dir;

    @AfterEach
    void resetTheme() {
        Palette.use(Themes.bundled().dark());
    }

    private static App app() {
        return new App(VERSION, Optional.empty(), History.inMemory(), DirectoryImpl.FS);
    }

    private static Theme bundled(String id) {
        return Themes.bundled().find(id).orElseThrow();
    }

    @Test
    void tOnTheStartScreenCyclesTheThemesAndSavesThem() {
        ThemeFile themeFile = new ThemeFile(Optional.of(dir.resolve("theme")));
        App app = app().withThemes(Themes.bundled(), themeFile);
        assertThat(TestScreens.render(app::render, 160, 30)).contains("T theme");

        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            assertThat(app.handleKey(key('T'))).isEmpty();
            seen.add(Palette.theme().id());
            assertThat(themeFile.load(Themes.bundled())).hasValue(Palette.theme());
        }

        assertThat(seen).containsExactly("light", "mono", "nord", "solarized-dark", "solarized-light", "dark");
    }

    @Test
    void tNamesTheThemeInTheFooterUntilTheNextKey() {
        App app = app();

        app.handleKey(key('T'));

        String screen = TestScreens.render(app::render, 80, 24);
        assertThat(screen).contains("Theme: light").doesNotContain("T theme");
        app.handleKey(key('T'));
        assertThat(TestScreens.render(app::render, 80, 24)).contains("Theme: mono");
        app.handleKey(key('z'));
        assertThat(TestScreens.render(app::render, 80, 24)).contains("T theme").doesNotContain("Theme:");
    }

    @Test
    void tCyclesThroughTheUsersThemesToo() throws Exception {
        Path themesDir = Files.createDirectories(dir.resolve("themes"));
        try (var nord = Themes.class.getResourceAsStream("nord.theme")) {
            Files.copy(nord, themesDir.resolve("solar.theme"));
        }
        App app = app().withThemes(Themes.bundled().withUserDir(Optional.of(themesDir)), ThemeFile.none());
        Palette.use(bundled("solarized-light"));

        app.handleKey(key('T'));

        assertThat(Palette.theme().id()).isEqualTo("solar");
        assertThat(TestScreens.render(app::render, 80, 24)).contains("Theme: solar");
        app.handleKey(key('T'));
        assertThat(Palette.theme().id()).isEqualTo("dark");
    }

    @Test
    void tInTheOpenPromptIsTyped() {
        App app = app();
        app.handleKey(key('o'));

        app.handleKey(key('T'));

        assertThat(Palette.theme().id()).isEqualTo("dark");
        assertThat(TestScreens.render(app::render, 160, 30)).contains("T");
    }

    @Test
    void tOnTheAnalysisScreenIsNotTheTheme() {
        App app = app();
        app.handleKey(key('6'));

        app.handleKey(key('T'));

        assertThat(Palette.theme().id()).isEqualTo("dark");
    }

    static Stream<Arguments> paintedThemesAndPopups() {
        return Stream.of("nord", "solarized-dark", "solarized-light")
                .flatMap(theme -> Stream.of("", "o", "L", "X", "D").map(key -> Arguments.of(theme, key)));
    }

    @ParameterizedTest
    @MethodSource("paintedThemesAndPopups")
    void aThemeWithItsOwnBackgroundPaintsEveryCellWithIt(String theme, String popupKey) {
        Palette.use(bundled(theme));
        App app = app();
        popupKey.chars().forEach(c -> app.handleKey(key((char) c)));

        Buffer buffer = TestScreens.buffer(app::render, 160, 30);

        // The theme's own, or where the logo's upper half is drawn, the colour of that half (ADR 0099).
        Set<Color> backgrounds = new HashSet<>();
        backgrounds.add(Palette.current().base().bg().orElseThrow());
        Palette.current()
                .logo()
                .rows()
                .forEach(row -> row.forEach(logo -> logo.style().bg().ifPresent(backgrounds::add)));
        assertThat(cells(buffer))
                .allSatisfy(cell -> assertThat(cell.style().bg())
                        .as("the background at %s", cell.position())
                        .hasValueSatisfying(colour -> assertThat(backgrounds).contains(colour)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "o", "L", "X", "D"})
    void monoHasNoColour(String popupKey) {
        Palette.use(bundled("mono"));
        App app = app();
        popupKey.chars().forEach(c -> app.handleKey(key((char) c)));

        Buffer buffer = TestScreens.buffer(app::render, 160, 30);

        assertThat(cells(buffer)).allSatisfy(cell -> {
            assertThat(cell.style().fg())
                    .as("the foreground at %s", cell.position())
                    .isEmpty();
            assertThat(cell.style().bg())
                    .as("the background at %s", cell.position())
                    .isEmpty();
        });
    }

    private record Positioned(String position, dev.tamboui.style.Style style) {}

    private static List<Positioned> cells(Buffer buffer) {
        Rect area = buffer.area();
        List<Positioned> cells = new ArrayList<>();
        for (int y = area.y(); y < area.bottom(); y++) {
            for (int x = area.x(); x < area.right(); x++) {
                cells.add(new Positioned(x + "," + y, buffer.get(x, y).style()));
            }
        }
        return cells;
    }
}
