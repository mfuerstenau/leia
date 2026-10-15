package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import java.util.ArrayList;
import java.util.List;

/**
 * The key legend of the footer, wrapped to the terminal's width. A legend is a list of entries
 * such as {@code q quit}, joined by three spaces; it breaks between entries only, never inside one.
 */
public final class KeyLegend {

    private static final String GAP = "   ";

    private KeyLegend() {}

    /**
     * {@code keys} on as many lines of {@code width} columns as it needs, each line starting with a
     * space. An entry wider than a line gets a line of its own, cut by the terminal.
     */
    public static List<Line> lines(String keys, int width) {
        List<Line> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (String entry : keys.strip().split(GAP)) {
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
}
