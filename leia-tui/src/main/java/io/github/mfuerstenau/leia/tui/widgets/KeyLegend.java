package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The key legend of the footer, wrapped to the terminal's width. A screen's legend is its {@link
 * Keys}, grouped (ADR 0120); a prompt's or a window's is a list of entries such as {@code q quit},
 * joined by three spaces. Either breaks between entries only, never inside one.
 */
public final class KeyLegend {

    private static final String GAP = "   ";
    private static final Pattern GAPS = Pattern.compile(" {3}");

    private KeyLegend() {}

    /**
     * {@code keys} on as many lines of {@code width} columns as it needs, each line starting with a
     * space. An entry wider than a line gets a line of its own, cut by the terminal.
     */
    public static List<Line> lines(String keys, int width) {
        List<Line> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (String entry : GAPS.split(keys.strip(), -1)) {
            String key = entry.strip();
            if (key.isEmpty()) {
                continue;
            }
            int keyWidth = CharWidth.of(key);
            if (!line.isEmpty() && lineWidth + GAP.length() + keyWidth > width) {
                lines.add(Line.from(line.toString()));
                line.setLength(0);
            }
            if (line.isEmpty()) {
                line.append(' ').append(key);
                lineWidth = 1 + keyWidth;
            } else {
                line.append(GAP).append(key);
                lineWidth += GAP.length() + keyWidth;
            }
        }
        lines.add(Line.from(line.toString()));
        return lines;
    }

    /**
     * The footer entries of {@code keys} as {@link #lines(String, int)} wraps a legend: each key in
     * bold, which reads under every theme, and a muted {@code │} between two groups. A line that
     * breaks at a group starts with it, without the bar.
     */
    public static List<Line> lines(Keys keys, int width) {
        List<Line> lines = new ArrayList<>();
        List<Span> line = new ArrayList<>();
        int lineWidth = 0;
        Style bold = Palette.current().label();
        for (Keys.Section section : keys.footer()) {
            boolean first = true;
            for (Keys.Key key : section.keys()) {
                String gap = first ? Keys.GROUP_GAP : Keys.GAP;
                int keyWidth = CharWidth.of(key.text());
                if (!line.isEmpty() && lineWidth + CharWidth.of(gap) + keyWidth > width) {
                    lines.add(Line.from(line));
                    line = new ArrayList<>();
                }
                if (line.isEmpty()) {
                    line.add(Span.raw(" "));
                    lineWidth = 1;
                } else if (first) {
                    line.add(Span.raw("  "));
                    line.add(Span.styled("│", Palette.current().muted()));
                    line.add(Span.raw("  "));
                    lineWidth += CharWidth.of(gap);
                } else {
                    line.add(Span.raw(gap));
                    lineWidth += gap.length();
                }
                line.add(Span.styled(key.key(), bold));
                line.add(Span.raw(" " + key.does()));
                lineWidth += keyWidth;
                first = false;
            }
        }
        lines.add(line.isEmpty() ? Line.from("") : Line.from(line));
        return lines;
    }
}
