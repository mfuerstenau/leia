package io.github.mfuerstenau.leia.tui.theme;

import dev.tamboui.style.Style;

/**
 * The styles of a {@link Theme}, one per role (ADR 0095), as a theme file sets them (ADR 0096). Bold, dim and reversed, as the selection
 * and the focused tab use them, are no roles: they read well under every theme.
 *
 * <p>The palette in use is {@link #current()}, read at render time. The TUI renders on one thread,
 * and a theme passed through every screen's render call would change dozens of signatures for
 * nothing.
 *
 * @param base the whole screen, painted first: empty for the terminal's own colours
 * @param error what failed
 * @param warning what needs attention
 * @param success what went well
 * @param accent what stands out without a meaning of its own: segment names, field names, formats
 * @param jsonKey a key in the viewer's JSON
 * @param jsonString a string in the viewer's JSON
 * @param jsonNumber a number in the viewer's JSON
 * @param jsonLiteral {@code true}, {@code false} and {@code null} in the viewer's JSON
 * @param match a highlighted match in a text
 * @param muted what is there but out of the way
 * @param border a panel's border
 * @param borderFocused the focused panel's border
 * @param logo0 the logo's cells of slot 0, the space around the gazelle: its background is the transparency (ADR 0097)
 * @param logo1 the logo's cells of slot 1
 * @param logo2 the logo's cells of slot 2
 * @param logo3 the logo's cells of slot 3
 * @param logo4 the logo's cells of slot 4
 * @param logo5 the logo's cells of slot 5
 */
public record Palette(
        Style base,
        Style error,
        Style warning,
        Style success,
        Style accent,
        Style jsonKey,
        Style jsonString,
        Style jsonNumber,
        Style jsonLiteral,
        Style match,
        Style muted,
        Style border,
        Style borderFocused,
        Style logo0,
        Style logo1,
        Style logo2,
        Style logo3,
        Style logo4,
        Style logo5) {

    private static volatile Theme current;

    /** The style of the logo's cells of {@code slot}, 0 to 5 (ADR 0097). */
    public Style logo(int slot) {
        return switch (slot) {
            case 0 -> logo0;
            case 1 -> logo1;
            case 2 -> logo2;
            case 3 -> logo3;
            case 4 -> logo4;
            case 5 -> logo5;
            default -> throw new IllegalArgumentException("logo slot " + slot + ": 0 to 5");
        };
    }

    /** The palette in use; {@code dark}'s until {@link #use} says otherwise. */
    public static Palette current() {
        return theme().palette();
    }

    /** The theme in use. */
    public static Theme theme() {
        Theme theme = current;
        return theme != null ? theme : Themes.bundled().dark();
    }

    /** Makes {@code theme} the one in use, from the next frame on. */
    public static void use(Theme theme) {
        current = theme;
    }
}
