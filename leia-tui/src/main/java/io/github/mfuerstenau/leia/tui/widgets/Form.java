package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.List;

/**
 * What the popups of the analysis and search screens share: how a key ends them, how a number row
 * takes digits, and how they are drawn (Rust's {@code form.rs}).
 */
public final class Form {

    private Form() {}

    /** What a key did to a popup that edits a draft of {@code T}. */
    // Each variant uses T (ADR 0088)
    @SuppressWarnings("java:S2326")
    public sealed interface Event<T> {

        /** The popup stays open. */
        record None<T>() implements Event<T> {}

        /** The popup closes, and the draft is taken. */
        record Apply<T>(T draft) implements Event<T> {}

        /** The popup closes without a change. */
        record Cancel<T>() implements Event<T> {}
    }

    /**
     * A digit typed into a number: appended while the number stays within {@code max}, otherwise the
     * number starts again from the digit.
     */
    public static int typeDigit(int number, char digit, int max) {
        int value = Character.isDigit(digit) ? Character.digit(digit, 10) : 0;
        long typed = number * 10L + value;
        return typed <= max ? (int) typed : Math.min(value, max);
    }

    /** A popup row: {@code label}, padded, then {@code value}; reversed while selected. */
    public static Line row(String label, String value, boolean selected) {
        Line line = Line.from(padded(label, 22) + " " + value);
        return selected ? line.patchStyle(Style.EMPTY.reversed()) : line;
    }

    /** {@code text} cut or padded to {@code width} characters, as Rust's {@code {:<w.w}}. */
    public static String padded(String text, int width) {
        int length = text.codePointCount(0, text.length());
        if (length > width) {
            return text.substring(0, text.offsetByCodePoints(0, width));
        }
        return text + " ".repeat(width - length);
    }

    /**
     * Draws {@code lines} in a bordered popup in the middle of {@code area}, at most {@code width}
     * wide, scrolled so that line {@code selectedLine} shows. Returns where the popup is.
     */
    public static Rect renderPopup(
            Frame frame, Rect area, String title, List<Line> lines, int selectedLine, int width) {
        int popupWidth = Math.min(area.width(), width);
        int height = Math.min(lines.size() + 2, area.height());
        Rect popup = new Rect(
                area.x() + (area.width() - popupWidth) / 2,
                area.y() + (area.height() - height) / 2,
                popupWidth,
                height);
        int innerHeight = Math.max(height - 2, 0);
        int scroll = Math.max(selectedLine + 1 - innerHeight, 0);
        Panels.clear(frame, popup);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(lines))
                        .scroll(scroll)
                        .block(Block.builder().borders(Borders.ALL).title(title).build())
                        .build(),
                popup);
        return popup;
    }

    public static Style dim() {
        return Style.EMPTY.dim();
    }
}
