package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * leia's logo (ADR 0097): characters and slots, read from the resource {@code leia.logo}, which
 * holds a row of characters per line, a blank line, then a row of digits {@code 0} to {@code 5} per
 * line, the slot of each cell. The theme colours the slots through {@link Palette#logo}. Next to
 * the logo go leia's name, version and build.
 */
public final class Logo {

    /** The slots, which {@link Palette} has a role for each. */
    private static final int SLOTS = 6;

    /** What {@code leia.logo} holds: a row of characters and a row of slots per line, as read. */
    record Grid(List<String> characters, List<String> slots) {}

    /** The logo's characters and slots, without the columns that are blank in every row at the right. */
    private static final Grid GRID = load();

    /** Columns the logo takes. */
    public static final int WIDTH = GRID.characters().getFirst().length();

    /** Rows the logo takes. */
    public static final int HEIGHT = GRID.characters().size();

    /**
     * The fewest rows the logo is drawn with. Where there are fewer than {@link #HEIGHT}, its top
     * is cut off, down to these rows, which leave out only the sparse dots at the top.
     */
    public static final int MIN_HEIGHT = 18;

    /** Columns between the logo and the text next to it. */
    static final int GAP = 2;

    /** The fewest columns left for the text next to the logo. */
    static final int MIN_TEXT_WIDTH = 30;

    private Logo() {}

    /** The logo as {@link #HEIGHT} lines of {@link #WIDTH} cells, in the styles of {@code palette}. */
    static List<Line> lines(Palette palette) {
        List<Line> lines = new ArrayList<>();
        for (int row = 0; row < HEIGHT; row++) {
            String characters = GRID.characters().get(row);
            String slots = GRID.slots().get(row);
            List<Span> spans = new ArrayList<>();
            for (int column = 0; column < WIDTH; column++) {
                spans.add(Span.styled(
                        String.valueOf(characters.charAt(column)), palette.logo(slots.charAt(column) - '0')));
            }
            lines.add(Line.from(spans));
        }
        return lines;
    }

    private static Grid load() {
        try (InputStream in = Logo.class.getResourceAsStream("leia.logo")) {
            if (in == null) {
                throw new IllegalStateException("leia.logo is missing");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads the text of a {@code leia.logo}, and cuts the columns that are blank in every row at the
     * right.
     *
     * @throws IllegalStateException if the blocks differ in size, a slot is not 0 to 5, or a space
     *     is not exactly the cells of slot 0: a bug of the build, as a broken bundled theme is
     */
    static Grid parse(String text) {
        List<String> rows = text.lines().toList();
        int blank = rows.indexOf("");
        if (blank <= 0) {
            throw new IllegalStateException("leia.logo: no characters before a blank line");
        }
        List<String> characters = rows.subList(0, blank);
        List<String> slots = rows.subList(blank + 1, rows.size());
        if (slots.size() != characters.size()) {
            throw new IllegalStateException(
                    "leia.logo: " + characters.size() + " rows of characters, " + slots.size() + " of slots");
        }
        int width = 0;
        for (int row = 0; row < characters.size(); row++) {
            String cells = characters.get(row);
            String slotRow = slots.get(row);
            if (slotRow.length() != cells.length()) {
                throw new IllegalStateException("leia.logo: row " + (row + 1) + " has " + cells.length()
                        + " characters and " + slotRow.length() + " slots");
            }
            for (int column = 0; column < cells.length(); column++) {
                int slot = slotRow.charAt(column) - '0';
                if (slot < 0 || slot >= SLOTS) {
                    throw new IllegalStateException(
                            "leia.logo: row " + (row + 1) + ", column " + (column + 1) + ": slot is 0 to 5");
                }
                boolean space = cells.charAt(column) == ' ';
                if (space != (slot == 0)) {
                    throw new IllegalStateException("leia.logo: row " + (row + 1) + ", column " + (column + 1)
                            + ": a space is slot 0 and nothing else is");
                }
                if (!space) {
                    width = Math.max(width, column + 1);
                }
            }
        }
        if (width == 0) {
            throw new IllegalStateException("leia.logo is empty");
        }
        int cut = width;
        return new Grid(
                characters.stream().map(row -> row.substring(0, cut)).toList(),
                slots.stream().map(row -> row.substring(0, cut)).toList());
    }

    /**
     * leia's name and version, then its build: {@code leia 0.1.0}, {@code build 9229a55}, the commit
     * time. leia and its backend are one build (ADR 0034), so the backend's version is leia's.
     */
    public static List<Line> aboutLines(VersionResult version) {
        return List.of(
                Line.from(Span.styled("leia", Style.EMPTY.bold()), Span.raw(" " + version.version())),
                Line.from("build " + version.commit()),
                Line.from(version.commitTime()));
    }

    /**
     * Whether the logo, cut to {@link #MIN_HEIGHT} rows at the least, and {@code text} next to it,
     * wrapped, fit into {@code area}.
     */
    public static boolean fitsBeside(Rect area, List<Line> text) {
        int textWidth = area.width() - WIDTH - GAP;
        return area.height() >= MIN_HEIGHT
                && textWidth >= MIN_TEXT_WIDTH
                && Panels.wrap(text, textWidth).size() <= area.height();
    }

    /**
     * Draws the logo at the left of {@code area}, without the rows at its top that don't fit, and
     * {@code text} next to it, centred on the logo's height if it is shorter, wrapped if it is too
     * wide.
     */
    public static void renderBeside(Frame frame, Rect area, List<Line> text) {
        List<Rect> columns = Layout.horizontal()
                .constraints(Constraint.length(WIDTH), Constraint.length(GAP), Constraint.fill())
                .split(area);
        int height = Math.min(HEIGHT, area.height());
        List<Line> lines = lines(Palette.current());
        frame.renderWidget(Paragraph.from(Text.from(lines.subList(HEIGHT - height, HEIGHT))), columns.get(0));
        int offset = Math.max(height - Panels.wrap(text, columns.get(2).width()).size(), 0) / 2;
        Rect textArea = Layout.vertical()
                .constraints(Constraint.length(offset), Constraint.fill())
                .split(columns.get(2))
                .get(1);
        frame.renderWidget(Paragraph.from(Text.from(Panels.wrap(text, textArea.width()))), textArea);
    }
}
