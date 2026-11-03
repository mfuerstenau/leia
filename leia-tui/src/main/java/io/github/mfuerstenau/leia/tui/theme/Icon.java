package io.github.mfuerstenau.leia.tui.theme;

import dev.tamboui.style.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An icon that a theme draws, such as the start screen's logo (ADR 0099): rows of cells, each a
 * character in a style of its own, read from two files. {@code <name>.chars} holds a row of
 * characters per line. {@code <name>.colours} holds a row of cells per line, separated by spaces,
 * each {@code fg/bg}, {@code fg} or {@code -}, where a colour is one of a theme file (ADR 0096) or
 * {@code -}, not set: what is beneath shows. A first line {@code chars = <other>} draws the
 * characters of {@code <other>.chars} instead.
 *
 * @param rows the rows of cells, all as wide, without the columns at the right that are blank in
 *     every row
 */
public record Icon(List<List<Cell>> rows) {

    /** A cell of an icon: one character, in its style. */
    public record Cell(String character, Style style) {}

    /** The first line of a colours file that names the characters it colours. */
    private static final Pattern CHARS = Pattern.compile("chars\\s*=\\s*(\\S+)");

    /** A colour, or a side of a cell, that isn't set. */
    private static final String NOT_SET = "-";

    public Icon {
        rows = rows.stream().map(List::copyOf).toList();
    }

    /** Columns the icon takes. */
    public int width() {
        return rows.getFirst().size();
    }

    /** Rows the icon takes. */
    public int height() {
        return rows.size();
    }

    /** The icon whose characters a colours file draws, if its first line names one. */
    static Optional<String> charsOf(String colours) {
        return colours.lines()
                .findFirst()
                .map(line -> CHARS.matcher(line.strip()))
                .filter(Matcher::matches)
                .map(m -> m.group(1));
    }

    /**
     * The icon of the text of a chars and a colours file. A row of characters may be shorter than
     * its cells, as an editor that strips trailing spaces leaves it: the rest are spaces.
     *
     * @param charsFile the chars file, as a message names it
     * @param coloursFile the colours file, as a message names it
     * @throws ThemeParser.Invalid if the files have other numbers of rows, a row of characters is
     *     longer than its cells, the rows of cells differ in width, a colour is unknown, or every
     *     cell is blank
     */
    static Icon parse(String charsFile, String chars, String coloursFile, String colours) throws ThemeParser.Invalid {
        List<String> characters = chars.lines().toList();
        List<String> cellRows = colours.lines().toList();
        int first = charsOf(colours).isPresent() ? 1 : 0;
        cellRows = cellRows.subList(first, cellRows.size());
        if (cellRows.size() != characters.size()) {
            throw new ThemeParser.Invalid(coloursFile + ": " + cellRows.size() + " rows of cells, but " + charsFile
                    + " has " + characters.size() + " rows of characters");
        }
        List<List<Cell>> rows = new ArrayList<>();
        int width = 0;
        for (int row = 0; row < cellRows.size(); row++) {
            String where = coloursFile + ":" + (row + first + 1) + ": ";
            String line = cellRows.get(row).strip();
            List<String> cells = line.isEmpty() ? List.of() : List.of(line.split("\\s+"));
            int[] codePoints = characters.get(row).codePoints().toArray();
            if (codePoints.length > cells.size()) {
                throw new ThemeParser.Invalid(where + cells.size() + " cells, but row " + (row + 1) + " of " + charsFile
                        + " has " + codePoints.length + " characters");
            }
            if (row > 0 && cells.size() != rows.getFirst().size()) {
                throw new ThemeParser.Invalid(where + cells.size() + " cells, but the first row has "
                        + rows.getFirst().size());
            }
            List<Cell> parsed = new ArrayList<>();
            for (int column = 0; column < cells.size(); column++) {
                String character = column < codePoints.length ? Character.toString(codePoints[column]) : " ";
                Cell cell = new Cell(character, style(where, cells.get(column)));
                if (!blank(cell)) {
                    width = Math.max(width, column + 1);
                }
                parsed.add(cell);
            }
            rows.add(parsed);
        }
        if (width == 0) {
            throw new ThemeParser.Invalid(coloursFile + ": the icon is empty");
        }
        int cut = width;
        return new Icon(rows.stream().map(row -> row.subList(0, cut)).toList());
    }

    /** A space whose background isn't set: nothing is drawn there. */
    private static boolean blank(Cell cell) {
        return cell.character().equals(" ") && cell.style().bg().isEmpty();
    }

    private static Style style(String where, String cell) throws ThemeParser.Invalid {
        int slash = cell.indexOf('/');
        String fg = slash < 0 ? cell : cell.substring(0, slash);
        String bg = slash < 0 ? NOT_SET : cell.substring(slash + 1);
        Style style = Style.EMPTY;
        if (!fg.equals(NOT_SET)) {
            style = style.fg(ThemeParser.colour(where, fg));
        }
        if (!bg.equals(NOT_SET)) {
            style = style.bg(ThemeParser.colour(where, bg));
        }
        return style;
    }
}
