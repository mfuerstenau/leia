package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/** The bundled themes, those of the user's folder, and the one in use (ADR 0095, ADR 0096). */
class ThemesTest {

    static final List<String> BUNDLED = Themes.BUNDLED;

    @TempDir
    Path dir;

    @AfterEach
    void resetTheme() {
        Palette.use(Themes.bundled().dark());
    }

    private static Palette bundled(String id) {
        return Themes.bundled().find(id).orElseThrow().palette();
    }

    private static String bundledText(String id) throws IOException {
        try (InputStream in = Themes.class.getResourceAsStream(id + ".theme")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bundlesFourThemesInTheOrderTCyclesThem() {
        Themes themes = Themes.bundled();

        assertThat(themes.ids()).containsExactly("dark", "light", "mono", "nord");
        assertThat(themes.next(themes.find("dark").orElseThrow()).id()).isEqualTo("light");
        assertThat(themes.next(themes.find("nord").orElseThrow()).id()).isEqualTo("dark");
    }

    @Test
    void darkIsTheDefaultAndTheLookBeforeThemes() {
        assertThat(Palette.theme().id()).isEqualTo("dark");
        assertThat(Palette.current())
                .isEqualTo(new Palette(
                        Style.EMPTY,
                        Style.EMPTY.red(),
                        Style.EMPTY.yellow(),
                        Style.EMPTY.green(),
                        Style.EMPTY.cyan(),
                        Style.EMPTY.cyan(),
                        Style.EMPTY.green(),
                        Style.EMPTY.yellow(),
                        Style.EMPTY.magenta(),
                        Style.EMPTY.black().onYellow(),
                        Style.EMPTY.fg(Color.DARK_GRAY),
                        Style.EMPTY,
                        Style.EMPTY,
                        Style.EMPTY,
                        fg("#1A0D0D"),
                        fg("#563F38"),
                        fg("#9E6038"),
                        fg("#C7905D"),
                        fg("#E4CDA5")));
    }

    @Test
    void lightAndNordAreTheColoursOfAdr0095() {
        assertThat(bundled("light"))
                .isEqualTo(new Palette(
                        Style.EMPTY,
                        Style.EMPTY.red(),
                        indexed(130),
                        indexed(28),
                        indexed(25),
                        indexed(25),
                        indexed(28),
                        indexed(130),
                        indexed(90),
                        Style.EMPTY.black().bg(Color.indexed(229)),
                        indexed(245),
                        Style.EMPTY,
                        Style.EMPTY,
                        Style.EMPTY,
                        fg("#1A0D0D"),
                        fg("#563F38"),
                        fg("#9E6038"),
                        fg("#C7905D"),
                        fg("#E4CDA5")));
        Color background = Color.hex("#2E3440");
        Color grey = Color.hex("#7B88A1");
        Color frost = Color.hex("#88C0D0");
        Color yellow = Color.hex("#EBCB8B");
        Color green = Color.hex("#A3BE8C");
        assertThat(bundled("nord"))
                .isEqualTo(new Palette(
                        Style.EMPTY.fg(Color.hex("#D8DEE9")).bg(background),
                        Style.EMPTY.fg(Color.hex("#BF616A")),
                        Style.EMPTY.fg(yellow),
                        Style.EMPTY.fg(green),
                        Style.EMPTY.fg(frost),
                        Style.EMPTY.fg(Color.hex("#8FBCBB")),
                        Style.EMPTY.fg(green),
                        Style.EMPTY.fg(Color.hex("#B48EAD")),
                        Style.EMPTY.fg(Color.hex("#81A1C1")),
                        Style.EMPTY.fg(background).bg(yellow),
                        Style.EMPTY.fg(grey),
                        Style.EMPTY.fg(grey),
                        Style.EMPTY.fg(frost),
                        Style.EMPTY,
                        fg("#BF616A"),
                        fg("#B48EAD"),
                        fg("#A3BE8C"),
                        fg("#EBCB8B"),
                        fg("#D08770")));
    }

    private static Style fg(String hex) {
        return Style.EMPTY.fg(Color.hex(hex));
    }

    private static Style indexed(int colour) {
        return Style.EMPTY.fg(Color.indexed(colour));
    }

    @Test
    void monoHasNoColours() {
        Palette mono = bundled("mono");

        assertThat(styles(mono)).allSatisfy(style -> {
            assertThat(style.fg()).isEmpty();
            assertThat(style.bg()).isEmpty();
        });
    }

    @ParameterizedTest
    @FieldSource("BUNDLED")
    void aMatchStandsOutInTheSelectedRow(String id) {
        // The selected row is reversed, so a match that is only reversed would vanish in it (mono did).
        Style selected = Panels.highlight(true);

        assertThat(selected.patch(bundled(id).match())).isNotEqualTo(selected);
    }

    @Test
    void nordKeepsMutedTextAndBordersReadable() {
        // Panel titles take the border's colour; #4C566A on #2E3440 is 1.7:1 and all but vanished.
        Palette nord = bundled("nord");
        Color background = nord.base().bg().orElseThrow();

        assertThat(contrast(nord.muted().fg().orElseThrow(), background)).isGreaterThanOrEqualTo(3.0);
        assertThat(contrast(nord.border().fg().orElseThrow(), background)).isGreaterThanOrEqualTo(3.0);
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color colour) {
        Color.Rgb rgb = colour.toRgb();
        return 0.2126 * linear(rgb.r()) + 0.7152 * linear(rgb.g()) + 0.0722 * linear(rgb.b());
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    @ParameterizedTest
    @FieldSource("BUNDLED")
    void everyBundledFileSetsEveryRoleOnce(String id) throws Exception {
        // The parser refuses a file that misses a role or sets one twice.
        Palette palette = ThemeParser.parse(id + ".theme", bundledText(id));

        assertThat(palette).isEqualTo(bundled(id));
        // A highlighted match must stand out from the text around it under every theme.
        assertThat(palette.match()).isNotEqualTo(palette.base());
        assertThat(palette.error()).isNotEqualTo(Style.EMPTY);
        assertThat(palette.warning()).isNotEqualTo(Style.EMPTY);
    }

    @Test
    void useSwitchesThePalette() {
        Theme nord = Themes.bundled().find("nord").orElseThrow();

        Palette.use(nord);

        assertThat(Palette.theme()).isSameAs(nord);
        assertThat(Palette.current().base().bg()).hasValue(Color.hex("#2E3440"));
    }

    @Test
    void addsTheUsersThemesAfterTheBundledOnesInTheOrderOfTheirNames() throws Exception {
        Files.writeString(dir.resolve("zen.theme"), bundledText("mono"));
        Files.writeString(dir.resolve("solar.theme"), bundledText("light"));
        Files.writeString(dir.resolve("notes.txt"), "not a theme");

        Themes themes = Themes.bundled().withUserDir(Optional.of(dir));

        assertThat(themes.ids()).containsExactly("dark", "light", "mono", "nord", "solar", "zen");
        assertThat(themes.find("solar").orElseThrow().palette()).isEqualTo(bundled("light"));
        assertThat(themes.next(themes.find("nord").orElseThrow()).id()).isEqualTo("solar");
        assertThat(themes.next(themes.find("zen").orElseThrow()).id()).isEqualTo("dark");
    }

    @Test
    void aUserThemeWithABundledNameReplacesItInItsPlace() throws Exception {
        Files.writeString(dir.resolve("nord.theme"), bundledText("nord").replace("#BF616A", "red"));

        Themes themes = Themes.bundled().withUserDir(Optional.of(dir));

        assertThat(themes.ids()).containsExactly("dark", "light", "mono", "nord");
        assertThat(themes.find("nord").orElseThrow().palette().error()).isEqualTo(Style.EMPTY.red());
        // The bundled themes stay as they are.
        assertThat(bundled("nord").error()).isEqualTo(Style.EMPTY.fg(Color.hex("#BF616A")));
    }

    @Test
    void leavesOutABrokenOrPartialFileOrABadName() throws Exception {
        Files.writeString(dir.resolve("broken.theme"), bundledText("dark").replace("cyan", "teal"));
        Files.writeString(dir.resolve("partial.theme"), "base = none\n");
        Files.writeString(dir.resolve("Upper.theme"), bundledText("dark"));
        Files.writeString(dir.resolve("-dash.theme"), bundledText("dark"));
        Files.writeString(dir.resolve("good.theme"), bundledText("dark"));

        Themes themes = Themes.bundled().withUserDir(Optional.of(dir));

        assertThat(themes.ids()).containsExactly("dark", "light", "mono", "nord", "good");
    }

    @Test
    void aMissingFolderAddsNothing() {
        assertThat(Themes.bundled().withUserDir(Optional.of(dir.resolve("missing"))))
                .isEqualTo(Themes.bundled());
        assertThat(Themes.bundled().withUserDir(Optional.empty())).isEqualTo(Themes.bundled());
    }

    private static Style[] styles(Palette palette) {
        return Arrays.stream(Palette.class.getRecordComponents())
                .filter(component -> component.getType() == Style.class)
                .map(component -> {
                    try {
                        return (Style) component.getAccessor().invoke(palette);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                })
                .toArray(Style[]::new);
    }
}
