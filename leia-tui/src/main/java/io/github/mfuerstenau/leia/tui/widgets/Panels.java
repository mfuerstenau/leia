package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import java.util.Locale;
import java.util.Optional;

/** The panels that the screens are made of, and how their parts are styled. */
public final class Panels {

    private Panels() {}

    /** A bordered block; the focused one has a thick border. */
    public static Block panel(String title, boolean focused) {
        Block.Builder block = Block.builder().borders(Borders.ALL).title(title);
        if (focused) {
            block.borderType(BorderType.THICK);
        }
        return block.build();
    }

    /** The selected row: reversed in the focused table, bold in the other. */
    public static Style highlight(boolean focused) {
        return focused ? Style.EMPTY.reversed() : Style.EMPTY.bold();
    }

    /** A label, bold and padded to 10 columns, and its value. */
    public static Line labelLine(String label, String value) {
        return Line.from(Span.styled(String.format(Locale.ROOT, "%-10s ", label), Style.EMPTY.bold()), Span.raw(value));
    }

    /**
     * A cell with {@code text} right-aligned in {@code width} columns. TamboUI's table ignores the
     * alignment of a cell's text, so the text is padded.
     */
    public static Cell right(String text, int width) {
        return Cell.from(" ".repeat(Math.max(width - CharWidth.of(text), 0)) + text);
    }

    /**
     * The row after the last one of a paged list: {@code …} while there are more, or why the next
     * page failed. Empty otherwise.
     */
    public static Optional<Row> tailRow(boolean more, Optional<String> error) {
        String text;
        if (error.isPresent()) {
            text = "Cannot read more: " + error.get();
        } else if (more) {
            text = "…";
        } else {
            return Optional.empty();
        }
        return Optional.of(Row.from(Cell.from(text)).style(Style.EMPTY.dim()));
    }

    /** How many lines {@code paragraph} takes at {@code width}: drawn aside, as it will be drawn. */
    public static int lineCount(Paragraph paragraph, int width) {
        int maxLines = 200;
        Buffer scratch = Buffer.empty(new Rect(0, 0, width, maxLines));
        paragraph.render(scratch.area(), scratch);
        for (int y = maxLines - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                String symbol = scratch.get(x, y).symbol();
                if (!symbol.isBlank()) {
                    return y + 1;
                }
            }
        }
        return 0;
    }
}
