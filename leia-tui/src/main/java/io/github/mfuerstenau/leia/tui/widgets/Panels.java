package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import java.util.Locale;

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
}
