package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The panels that the screens are made of, and how their parts are styled. */
public final class Panels {

    private Panels() {}

    /** A bordered block; the focused one has a thick border. Its border has the theme's colour. */
    public static Block panel(String title, boolean focused) {
        Palette palette = Palette.current();
        Block.Builder block = box().title(title).borderStyle(focused ? palette.borderFocused() : palette.border());
        if (focused) {
            block.borderType(BorderType.THICK);
        }
        return block.build();
    }

    /**
     * A block with a border all round, for a popup or a frame, in the theme's {@link Palette#base()
     * base}. TamboUI draws a border and its titles in the border style's foreground alone, not the
     * cell's, so without it {@code nord} would show them in the terminal's foreground on its own
     * background, which on a light terminal hardly shows.
     */
    public static Block.Builder box() {
        return Block.builder()
                .borders(Borders.ALL)
                .borderStyle(Palette.current().base());
    }

    /** A {@link #box()} with {@code title}. */
    public static Block box(String title) {
        return box().title(title).build();
    }

    /**
     * Clears {@code area} for a popup, and paints it with the theme's {@link Palette#base() base}:
     * {@link Clear} alone resets the cells to the terminal's colours.
     */
    public static void clear(Frame frame, Rect area) {
        frame.renderWidget(Clear.INSTANCE, area);
        frame.buffer().setStyle(area, Palette.current().base());
    }

    /** The selected row: reversed in the focused table, bold in the other. */
    public static Style highlight(boolean focused) {
        return focused ? Style.EMPTY.reversed() : Style.EMPTY.bold();
    }

    /**
     * A {@code width} × {@code height} rectangle in the middle of {@code area}, as ratatui's {@code
     * Rect::centered} placed it: an odd column or row to spare goes before it.
     */
    public static Rect centered(Rect area, int width, int height) {
        int w = Math.min(width, area.width());
        int h = Math.min(height, area.height());
        return new Rect(area.x() + (area.width() - w + 1) / 2, area.y() + (area.height() - h + 1) / 2, w, h);
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
     * How many columns of {@code widths}, from the left and {@code spacing} apart, fit into {@code
     * available} cells, and at least {@code kept}. A column is shown whole or not at all, so that a
     * number is never cut: on a narrow terminal the columns on the right go first.
     */
    public static int shownColumns(int[] widths, int spacing, int available, int kept) {
        int used = 0;
        for (int i = 0; i < widths.length; i++) {
            used += (i == 0 ? 0 : spacing) + widths[i];
            if (used > available && i >= kept) {
                return i;
            }
        }
        return widths.length;
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
        return Optional.of(Row.from(Cell.from(text)).style(Palette.current().muted()));
    }

    /**
     * {@code lines} wrapped at {@code width} columns by {@link TextFormat#wrapRanges}, each part of a
     * line keeping the style of its span. Drawn without TamboUI's word wrap, they take as many rows
     * as the list is long, which is what a panel scrolls by.
     */
    public static List<Line> wrap(List<Line> lines, int width) {
        List<Line> wrapped = new ArrayList<>();
        for (Line line : lines) {
            String text = line.rawContent();
            for (TextFormat.LineRange range : TextFormat.wrapRanges(text, width)) {
                List<Span> spans = new ArrayList<>();
                int offset = 0;
                for (Span span : line.spans()) {
                    String content = span.content();
                    int from = Math.max(range.start(), offset);
                    int to = Math.min(range.end(), offset + content.length());
                    if (from < to) {
                        spans.add(Span.styled(content.substring(from - offset, to - offset), span.style()));
                    }
                    offset += content.length();
                }
                wrapped.add(Line.from(spans));
            }
        }
        return wrapped;
    }

    /** A paragraph of {@code lines} in {@code block}, {@link #wrap wrapped} to fit inside it in {@code area}. */
    public static Paragraph wrapped(List<Line> lines, Block block, Rect area) {
        return Paragraph.builder()
                .text(Text.from(wrap(lines, block.inner(area).width())))
                .block(block)
                .build();
    }

    /** {@link #wrapped(List, Block, Rect)} for a text, which may have several lines. */
    public static Paragraph wrapped(String text, Block block, Rect area) {
        return wrapped(Text.from(text).lines(), block, area);
    }
}
