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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The themes (ADR 0095): {@code T} on the start screen, and how a theme paints the screen. */
class AppThemeTest {

    private static final VersionResult VERSION =
            new VersionResult("leia-server", "1", "abc", "t", new JavaRuntime("25", "v", "/h"));

    @TempDir
    Path dir;

    @AfterEach
    void resetTheme() {
        Palette.use(Theme.DARK);
    }

    private static App app() {
        return new App(VERSION, Optional.empty(), History.inMemory(), DirectoryImpl.FS);
    }

    @Test
    void tOnTheStartScreenCyclesTheThemesAndSavesThem() {
        ThemeFile themeFile = new ThemeFile(Optional.of(dir.resolve("theme")));
        App app = app().withThemeFile(themeFile);
        assertThat(TestScreens.render(app::render, 160, 30)).contains("T theme");

        List<Theme> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            assertThat(app.handleKey(key('T'))).isEmpty();
            seen.add(Palette.theme());
            assertThat(themeFile.load()).hasValue(Palette.theme());
        }

        assertThat(seen).containsExactly(Theme.LIGHT, Theme.MONO, Theme.NORD, Theme.DARK);
    }

    @Test
    void tInTheOpenPromptIsTyped() {
        App app = app();
        app.handleKey(key('o'));

        app.handleKey(key('T'));

        assertThat(Palette.theme()).isEqualTo(Theme.DARK);
        assertThat(TestScreens.render(app::render, 160, 30)).contains("T");
    }

    @Test
    void tOnTheAnalysisScreenIsNotTheTheme() {
        App app = app();
        app.handleKey(key('6'));

        app.handleKey(key('T'));

        assertThat(Palette.theme()).isEqualTo(Theme.DARK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "o", "L", "X", "D"})
    void nordPaintsEveryCellWithItsBackground(String popupKey) {
        Palette.use(Theme.NORD);
        App app = app();
        popupKey.chars().forEach(c -> app.handleKey(key((char) c)));

        Buffer buffer = TestScreens.buffer(app::render, 160, 30);

        Color background = Color.hex("#2E3440");
        assertThat(cells(buffer))
                .allSatisfy(cell -> assertThat(cell.style().bg())
                        .as("the background at %s", cell.position())
                        .hasValue(background));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "o", "L", "X", "D"})
    void monoHasNoColour(String popupKey) {
        Palette.use(Theme.MONO);
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
