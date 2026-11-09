package io.github.mfuerstenau.leia.tui.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The theme files' format (ADR 0096). */
class ThemeParserTest {

    /** Every role but {@code base}, which the tests prepend. */
    private static final String REST = """
            error         = red
            warning       = yellow
            success       = green
            accent        = cyan
            jsonKey       = cyan
            jsonString    = green
            jsonNumber    = yellow
            jsonLiteral   = magenta
            match         = black on yellow
            muted         = dark-gray
            border        = none
            borderFocused = none
            heading       = bold
            label         = bold
            selection     = reversed
            selectionUnfocused = bold
            secondary     = italic
            logo          = gazelle
            """;

    private static Style base(String value) throws ThemeParser.Invalid {
        return ThemeParser.parse("t.theme", "base = " + value + "\n" + REST).base();
    }

    @Test
    void readsNamedIndexedAndHexColours() throws Exception {
        assertThat(base("dark-gray")).isEqualTo(Style.EMPTY.fg(Color.DARK_GRAY));
        assertThat(base("LIGHT-CYAN")).isEqualTo(Style.EMPTY.fg(Color.LIGHT_CYAN));
        assertThat(base("130")).isEqualTo(Style.EMPTY.fg(Color.indexed(130)));
        assertThat(base("#2e3440")).isEqualTo(Style.EMPTY.fg(Color.hex("#2E3440")));
    }

    @Test
    void readsABackgroundAndModifiers() throws Exception {
        assertThat(base("black on yellow")).isEqualTo(Style.EMPTY.black().onYellow());
        assertThat(base("on 229")).isEqualTo(Style.EMPTY.bg(Color.indexed(229)));
        assertThat(base("bold")).isEqualTo(Style.EMPTY.bold());
        assertThat(base("#D8DEE9 on #2E3440 bold   underlined"))
                .isEqualTo(Style.EMPTY
                        .fg(Color.hex("#D8DEE9"))
                        .bg(Color.hex("#2E3440"))
                        .bold()
                        .underlined());
        assertThat(base("dim italic reversed"))
                .isEqualTo(Style.EMPTY.dim().italic().reversed());
        assertThat(base("none")).isEqualTo(Style.EMPTY);
    }

    @Test
    void skipsCommentsAndBlankLines() throws Exception {
        Palette palette = ThemeParser.parse("t.theme", "# a theme\n\n  # indented\nbase = none\n" + REST);

        assertThat(palette.base()).isEqualTo(Style.EMPTY);
        assertThat(palette.match()).isEqualTo(Style.EMPTY.black().onYellow());
        assertThat(palette.logo()).isEqualTo(Icons.bundled().find("", "gazelle"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            base                        | t.theme:1: expected role = value
            base =                      | t.theme:1: no style; none is the terminal's own
            base = purple               | t.theme:1: unknown colour 'purple'
            base = 256                  | t.theme:1: unknown colour '256'
            base = #2E344               | t.theme:1: unknown colour '#2E344'
            base = red on               | t.theme:1: no colour after on
            base = red blink            | t.theme:1: unknown modifier 'blink'
            base = red green            | t.theme:1: unknown modifier 'green'
            colour = red                | t.theme:1: unknown role 'colour'
            """)
    void reportsABadLineWithItsNumber(String line, String message) {
        assertThatThrownBy(() -> ThemeParser.parse("t.theme", line + "\n" + REST))
                .isInstanceOf(ThemeParser.Invalid.class)
                .hasMessageStartingWith(message);
    }

    @Test
    void reportsARoleSetTwice() {
        assertThatThrownBy(() -> ThemeParser.parse("t.theme", "base = none\n" + REST + "error = bold\n"))
                .isInstanceOf(ThemeParser.Invalid.class)
                .hasMessage("t.theme:20: error is set twice");
        assertThatThrownBy(() -> ThemeParser.parse("t.theme", "base = none\n" + REST + "logo = gazelle-mono\n"))
                .hasMessage("t.theme:20: logo is set twice");
    }

    @Test
    void refusesTheOldLogoRolesAndNamesTheNewOne() {
        assertThatThrownBy(() -> ThemeParser.parse("t.theme", "base = none\nlogo1 = red\n" + REST))
                .isInstanceOf(ThemeParser.Invalid.class)
                .hasMessage("t.theme:2: unknown role 'logo1': the logo is logo = <icon>, which has colours of its own");
    }

    @Test
    void refusesAnUnknownIcon() {
        assertThatThrownBy(() -> ThemeParser.parse("t.theme", "base = none\n" + REST.replace("= gazelle", "= zebra")))
                .isInstanceOf(ThemeParser.Invalid.class)
                .hasMessage("t.theme:19: no icon file zebra.colours among leia's own");
    }

    @Test
    void aThemeWithoutSortMarkerTakesItsAccent() throws Exception {
        // sortMarker came after ADR 0121, and is optional so that the user's themes keep loading (ADR 0130).
        Palette palette = ThemeParser.parse("t.theme", "base = none\n" + REST);

        assertThat(palette.sortMarker()).isEqualTo(Style.EMPTY.cyan());
    }

    @Test
    void readsSortMarker() throws Exception {
        Palette palette = ThemeParser.parse("t.theme", "base = none\nsortMarker = magenta bold\n" + REST);

        assertThat(palette.sortMarker()).isEqualTo(Style.EMPTY.magenta().bold());
    }

    @Test
    void refusesSortMarkerSetTwice() {
        assertThatThrownBy(
                        () -> ThemeParser.parse("t.theme", "base = none\nsortMarker = red\nsortMarker = blue\n" + REST))
                .isInstanceOf(ThemeParser.Invalid.class)
                .hasMessage("t.theme:3: sortMarker is set twice");
    }

    @Test
    void reportsEveryMissingRole() {
        assertThatThrownBy(() -> ThemeParser.parse("t.theme", "# nothing\nerror = red\n"))
                .isInstanceOf(ThemeParser.Invalid.class)
                .hasMessage("t.theme: missing base, warning, success, accent, jsonKey, jsonString, jsonNumber,"
                        + " jsonLiteral, match, muted, border, borderFocused, heading, label, selection,"
                        + " selectionUnfocused, secondary, logo");
    }
}
