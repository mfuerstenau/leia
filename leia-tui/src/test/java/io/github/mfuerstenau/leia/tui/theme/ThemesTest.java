package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void bundlesSixThemesInTheOrderTCyclesThem() {
        Themes themes = Themes.bundled();

        assertThat(themes.ids()).containsExactly("dark", "light", "mono", "nord", "solarized-dark", "solarized-light");
        assertThat(themes.next(themes.find("dark").orElseThrow()).id()).isEqualTo("light");
        assertThat(themes.next(themes.find("nord").orElseThrow()).id()).isEqualTo("solarized-dark");
        assertThat(themes.next(themes.find("solarized-light").orElseThrow()).id())
                .isEqualTo("dark");
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
                        Style.EMPTY.bold(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.reversed(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.italic(),
                        Style.EMPTY.magenta().bold(),
                        icon("gazelle")));
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
                        Style.EMPTY.bold(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.reversed(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.italic(),
                        indexed(90).bold(),
                        icon("gazelle")));
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
                        Style.EMPTY.bold(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.reversed(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.italic(),
                        Style.EMPTY.fg(Color.hex("#B48EAD")).bold(),
                        icon("gazelle-aurora")));
    }

    @Test
    void solarizedAreTheColoursOfAdr0095() {
        Color blue = Color.hex("#268BD2");
        Color yellow = Color.hex("#B58900");
        Color base03 = Color.hex("#002B36");
        Color base3 = Color.hex("#FDF6E3");
        Color red = Color.hex("#DC322F");
        Color green = Color.hex("#859900");
        Color cyan = Color.hex("#2AA198");
        Color magenta = Color.hex("#D33682");
        Color violet = Color.hex("#6C71C4");
        assertThat(bundled("solarized-dark"))
                .isEqualTo(new Palette(
                        Style.EMPTY.fg(Color.hex("#839496")).bg(base03),
                        Style.EMPTY.fg(red),
                        Style.EMPTY.fg(yellow),
                        Style.EMPTY.fg(green),
                        Style.EMPTY.fg(blue),
                        Style.EMPTY.fg(blue),
                        Style.EMPTY.fg(cyan),
                        Style.EMPTY.fg(magenta),
                        Style.EMPTY.fg(violet),
                        Style.EMPTY.fg(base03).bg(yellow),
                        Style.EMPTY.fg(Color.hex("#657B83")),
                        Style.EMPTY.fg(Color.hex("#657B83")),
                        Style.EMPTY.fg(blue),
                        Style.EMPTY.bold(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.reversed(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.italic(),
                        Style.EMPTY.fg(violet).bold(),
                        icon("gazelle-solarized-dark")));
        assertThat(bundled("solarized-light"))
                .isEqualTo(new Palette(
                        Style.EMPTY.fg(Color.hex("#657B83")).bg(base3),
                        Style.EMPTY.fg(red),
                        Style.EMPTY.fg(yellow),
                        Style.EMPTY.fg(green),
                        Style.EMPTY.fg(blue),
                        Style.EMPTY.fg(blue),
                        Style.EMPTY.fg(cyan),
                        Style.EMPTY.fg(magenta),
                        Style.EMPTY.fg(violet),
                        Style.EMPTY.fg(base03).bg(yellow),
                        Style.EMPTY.fg(Color.hex("#839496")),
                        Style.EMPTY.fg(Color.hex("#839496")),
                        Style.EMPTY.fg(blue),
                        Style.EMPTY.bold(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.reversed(),
                        Style.EMPTY.bold(),
                        Style.EMPTY.italic(),
                        Style.EMPTY.fg(violet).bold(),
                        icon("gazelle-solarized-light")));
    }

    private static Icon icon(String name) {
        try {
            return Icons.bundled().find("", name);
        } catch (ThemeParser.Invalid e) {
            throw new AssertionError(e);
        }
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
        assertThat(mono.logo().rows())
                .allSatisfy(row -> assertThat(row).allMatch(cell -> cell.style().equals(Style.EMPTY)));
    }

    @ParameterizedTest
    @FieldSource("BUNDLED")
    void aMatchStandsOutInTheSelectedRow(String id) {
        // The selected row is reversed, so a match that is only reversed would vanish in it (mono did).
        Style selected = bundled(id).selection();

        assertThat(selected.patch(bundled(id).match())).isNotEqualTo(selected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nord", "solarized-dark", "solarized-light"})
    void aThemeWithItsOwnBackgroundKeepsMutedTextAndBordersReadable(String id) {
        // Panel titles take the border's colour; #4C566A on #2E3440 is 1.7:1 and all but vanished. The bound is
        // 2.9 for solarized-light, whose base0 #839496 on base3 is 2.93:1 (ADR 0095).
        Palette palette = bundled(id);
        Color background = palette.base().bg().orElseThrow();

        assertThat(contrast(palette.muted().fg().orElseThrow(), background)).isGreaterThanOrEqualTo(2.9);
        assertThat(contrast(palette.border().fg().orElseThrow(), background)).isGreaterThanOrEqualTo(2.9);
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

    @ParameterizedTest
    @FieldSource("BUNDLED")
    void everyBundledThemeSetsTheStyleSlotsOfAdr0121(String id) {
        Palette palette = bundled(id);

        assertThat(palette.heading()).isEqualTo(Style.EMPTY.bold());
        assertThat(palette.label()).isEqualTo(Style.EMPTY.bold());
        assertThat(palette.selection()).isEqualTo(Style.EMPTY.reversed());
        assertThat(palette.selectionUnfocused()).isEqualTo(Style.EMPTY.bold());
        assertThat(palette.secondary()).isEqualTo(Style.EMPTY.italic());
    }

    @ParameterizedTest
    @FieldSource("BUNDLED")
    void everyBundledThemeMarksTheSortBoldAndInAColourOfItsOwn(String id) {
        // In a colour no table uses, so that the sorted column is found at a glance; mono has none (ADR 0130).
        Palette palette = bundled(id);

        assertThat(palette.sortMarker().addModifiers()).contains(dev.tamboui.style.Modifier.BOLD);
        if (id.equals("mono")) {
            assertThat(palette.sortMarker()).isEqualTo(Style.EMPTY.bold());
        } else {
            assertThat(palette.sortMarker().fg())
                    .isPresent()
                    .isNotEqualTo(palette.accent().fg());
        }
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

        Themes themes = Themes.bundled().withUserDir(dir);

        assertThat(themes.ids())
                .containsExactly("dark", "light", "mono", "nord", "solarized-dark", "solarized-light", "solar", "zen");
        assertThat(themes.find("solar").orElseThrow().palette()).isEqualTo(bundled("light"));
        assertThat(themes.next(themes.find("solarized-light").orElseThrow()).id())
                .isEqualTo("solar");
        assertThat(themes.next(themes.find("zen").orElseThrow()).id()).isEqualTo("dark");
    }

    @Test
    void theFirstFolderWinsAClashOfNames() throws Exception {
        Path user = Files.createDirectories(dir.resolve("user"));
        Path system = Files.createDirectories(dir.resolve("system"));
        Files.writeString(system.resolve("shared.theme"), bundledText("light"));
        Files.writeString(system.resolve("admin.theme"), bundledText("nord"));
        Files.writeString(user.resolve("shared.theme"), bundledText("mono"));

        Themes themes = Themes.bundled().withDirs(List.of(user, system));

        assertThat(themes.find("shared").orElseThrow().palette()).isEqualTo(bundled("mono"));
        assertThat(themes.find("admin").orElseThrow().palette()).isEqualTo(bundled("nord"));
    }

    @Test
    void namesTheUserFilesItLeftOutWithTheReason() throws Exception {
        // A theme from before the style slots of ADR 0121 lacks them.
        Files.writeString(dir.resolve("old.theme"), "base = none\n");
        Files.writeString(dir.resolve("good.theme"), bundledText("mono"));

        Themes themes = Themes.bundled().withUserDir(dir);

        assertThat(themes.ids()).contains("good").doesNotContain("old");
        assertThat(themes.leftOut()).hasSize(1);
        assertThat(themes.leftOut().getFirst().id()).isEqualTo("old");
        assertThat(themes.leftOut().getFirst().reason())
                .contains("old.theme: missing error, ")
                .contains("heading, label, selection, selectionUnfocused, secondary");
        assertThat(Themes.bundled().leftOut()).isEmpty();
    }

    @Test
    void aUserThemeWithABundledNameReplacesItInItsPlace() throws Exception {
        Files.writeString(dir.resolve("nord.theme"), bundledText("nord").replace("#BF616A", "red"));

        Themes themes = Themes.bundled().withUserDir(dir);

        assertThat(themes.ids()).containsExactly("dark", "light", "mono", "nord", "solarized-dark", "solarized-light");
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

        Themes themes = Themes.bundled().withUserDir(dir);

        assertThat(themes.ids())
                .containsExactly("dark", "light", "mono", "nord", "solarized-dark", "solarized-light", "good");
    }

    @Test
    void aMissingFolderAddsNothing() {
        assertThat(Themes.bundled().withUserDir(dir.resolve("missing"))).isEqualTo(Themes.bundled());
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
