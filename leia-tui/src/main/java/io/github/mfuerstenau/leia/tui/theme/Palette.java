package io.github.mfuerstenau.leia.tui.theme;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * The styles of a {@link Theme}, one per role (ADR 0095). Bold, dim and reversed, as the selection
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
 * @param colouredLogo whether the start screen's logo has its colours
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
        boolean colouredLogo) {

    /** The terminal's colours, as leia had them before themes. */
    static final Palette DARK = new Palette(
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
            true);

    /** Darker colours of the 256-colour palette, which read on a light background. */
    static final Palette LIGHT = new Palette(
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
            true);

    /** No colour at all, for {@code NO_COLOR} and monochrome terminals. */
    static final Palette MONO = new Palette(
            Style.EMPTY,
            Style.EMPTY.bold(),
            Style.EMPTY.bold(),
            Style.EMPTY,
            Style.EMPTY,
            Style.EMPTY,
            Style.EMPTY.dim(),
            Style.EMPTY,
            Style.EMPTY,
            Style.EMPTY.reversed(),
            Style.EMPTY.dim(),
            Style.EMPTY,
            Style.EMPTY,
            false);

    private static final Color NORD_BACKGROUND = Color.hex("#2E3440");
    private static final Color NORD_GREY = Color.hex("#4C566A");
    private static final Color NORD_FROST = Color.hex("#88C0D0");
    private static final Color NORD_YELLOW = Color.hex("#EBCB8B");
    private static final Color NORD_GREEN = Color.hex("#A3BE8C");

    /** The Nord palette (nordtheme.com), on its own background; it needs a truecolor terminal. */
    static final Palette NORD = new Palette(
            Style.EMPTY.fg(Color.hex("#D8DEE9")).bg(NORD_BACKGROUND),
            Style.EMPTY.fg(Color.hex("#BF616A")),
            Style.EMPTY.fg(NORD_YELLOW),
            Style.EMPTY.fg(NORD_GREEN),
            Style.EMPTY.fg(NORD_FROST),
            Style.EMPTY.fg(Color.hex("#8FBCBB")),
            Style.EMPTY.fg(NORD_GREEN),
            Style.EMPTY.fg(Color.hex("#B48EAD")),
            Style.EMPTY.fg(Color.hex("#81A1C1")),
            Style.EMPTY.fg(NORD_BACKGROUND).bg(NORD_YELLOW),
            Style.EMPTY.fg(NORD_GREY),
            Style.EMPTY.fg(NORD_GREY),
            Style.EMPTY.fg(NORD_FROST),
            true);

    private static volatile Theme current = Theme.DARK;

    private static Style indexed(int colour) {
        return Style.EMPTY.fg(Color.indexed(colour));
    }

    /** The palette in use. */
    public static Palette current() {
        return current.palette();
    }

    /** The theme in use. */
    public static Theme theme() {
        return current;
    }

    /** Makes {@code theme} the one in use, from the next frame on. */
    public static void use(Theme theme) {
        current = theme;
    }
}
