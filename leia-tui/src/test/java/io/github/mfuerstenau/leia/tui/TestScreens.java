package io.github.mfuerstenau.leia.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import io.github.mfuerstenau.leia.tui.widgets.Keys;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Draws into a buffer and reads it back as text, and makes the keys that tests press. */
public final class TestScreens {

    /** Ctrl and Alt together, as Windows reports AltGr. */
    public static final KeyModifiers ALT_GR = KeyModifiers.of(true, true, false);

    private TestScreens() {}

    /** What {@code draw} puts on a screen of {@code width} by {@code height}. */
    public static Buffer buffer(Consumer<Frame> draw, int width, int height) {
        Buffer buffer = Buffer.empty(new Rect(0, 0, width, height));
        draw.accept(Frame.forTesting(buffer));
        return buffer;
    }

    /** The text of {@code buffer}, one line per row. */
    public static String text(Buffer buffer) {
        Rect area = buffer.area();
        List<String> rows = new ArrayList<>();
        for (int y = area.y(); y < area.bottom(); y++) {
            StringBuilder row = new StringBuilder();
            for (int x = area.x(); x < area.right(); x++) {
                row.append(buffer.get(x, y).symbol());
            }
            rows.add(row.toString());
        }
        return String.join("\n", rows);
    }

    /** The style of every cell of {@code buffer} that shows {@code symbol}, row by row. */
    public static List<Style> stylesOf(Buffer buffer, String symbol) {
        Rect area = buffer.area();
        List<Style> styles = new ArrayList<>();
        for (int y = area.y(); y < area.bottom(); y++) {
            for (int x = area.x(); x < area.right(); x++) {
                if (buffer.get(x, y).symbol().equals(symbol)) {
                    styles.add(buffer.get(x, y).style());
                }
            }
        }
        return styles;
    }

    /** The text that {@code draw} puts on a screen of {@code width} by {@code height}. */
    public static String render(Consumer<Frame> draw, int width, int height) {
        return text(buffer(draw, width, height));
    }

    /** Every key of {@code keys}, footer or not, as the footer would show them. */
    public static String allKeys(Keys keys) {
        return keys.sections().stream()
                .flatMap(section -> section.keys().stream())
                .map(Keys.Key::text)
                .collect(java.util.stream.Collectors.joining("   "));
    }

    public static KeyEvent key(KeyCode code) {
        return KeyEvent.ofKey(code);
    }

    public static KeyEvent key(char c) {
        return KeyEvent.ofChar(c);
    }

    public static KeyEvent ctrl(char c) {
        return KeyEvent.ofChar(c, KeyModifiers.CTRL);
    }

    public static KeyEvent alt(char c) {
        return KeyEvent.ofChar(c, KeyModifiers.ALT);
    }

    public static KeyEvent altGr(char c) {
        return KeyEvent.ofChar(c, ALT_GR);
    }
}
