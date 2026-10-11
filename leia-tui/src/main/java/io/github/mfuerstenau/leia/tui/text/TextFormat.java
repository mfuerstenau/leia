package io.github.mfuerstenau.leia.tui.text;

import dev.tamboui.text.CharWidth;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How text from the index is shown: control characters escaped, so that they can't break the
 * layout, and byte strings that are not UTF-8 as {@code 0x} and their hex.
 */
public final class TextFormat {

    private TextFormat() {}

    /** {@code text} with control characters, line breaks included, escaped as Rust's {@code escape_default} does. */
    public static String escapeControl(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (!Character.isISOControl(c)) {
                escaped.appendCodePoint(c);
                return;
            }
            switch (c) {
                case '\t' -> escaped.append("\\t");
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                default -> escaped.append("\\u{").append(Integer.toHexString(c)).append('}');
            }
        });
        return escaped.toString();
    }

    /** A byte string from the backend (ADR 0006): its text escaped, or {@code 0x} and the hex if {@code binary}. */
    public static String bytesText(String value, boolean binary) {
        return binary ? "0x" + value : escapeControl(value);
    }

    /** At most the first {@code max} characters of {@code text}, and {@code …} if it is longer. */
    public static String firstChars(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max)) + "…";
    }

    /** {@code n} and the noun, in the plural unless {@code n} is 1. */
    public static String count(long n, String noun) {
        return n == 1 ? "1 " + noun : n + " " + noun + "s";
    }

    /** A size in bytes, in the largest binary unit that keeps it at 1 or more, such as {@code 1.5 KiB}. */
    public static String size(long bytes) {
        double kib = 1024.0;
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / kib);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / kib / kib);
        }
        return String.format(Locale.ROOT, "%.1f GiB", bytes / kib / kib / kib);
    }

    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern(
                    "uuuu-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /** Milliseconds since the epoch as {@code 2001-09-09 01:46:40 UTC}. */
    public static String utcTime(long millis) {
        return UTC_TIME.format(Instant.ofEpochMilli(millis));
    }

    /**
     * {@code text} in lines at most {@code width} columns wide, broken at spaces, and inside a word
     * only where the word alone is wider; at most {@code max} lines, the last one ending in {@code …}
     * if the text was cut. At least one line, empty for an empty text.
     */
    public static List<String> wrap(String text, int width, int max) {
        return wrapHanging(text, width, width, max);
    }

    /** {@link #wrap}, with the first line {@code first} columns wide and the others {@code width}. */
    public static List<String> wrapHanging(String text, int first, int width, int max) {
        int maxLines = Math.max(max, 1);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (String word : text.split(" ", -1)) {
            int wordWidth = CharWidth.of(word);
            if (lineWidth > 0 && fits(lines, lineWidth + 1 + wordWidth, first, width)) {
                line.append(' ').append(word);
                lineWidth += 1 + wordWidth;
                continue;
            }
            if (lineWidth > 0) {
                lines.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
            }
            int[] chars = word.codePoints().toArray();
            for (int c : chars) {
                int charWidth = CharWidth.of(c);
                if (lineWidth > 0 && !fits(lines, lineWidth + charWidth, first, width)) {
                    lines.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                }
                line.appendCodePoint(c);
                lineWidth += charWidth;
            }
        }
        if (!line.isEmpty() || lines.isEmpty()) {
            lines.add(line.toString());
        }
        if (lines.size() > maxLines) {
            lines.subList(maxLines, lines.size()).clear();
            int lastWidth = Math.max(maxLines == 1 ? first : width, 1);
            String last = lines.getLast();
            while (!last.isEmpty() && CharWidth.of(last) + 1 > lastWidth) {
                last = last.substring(0, last.offsetByCodePoints(last.length(), -1));
            }
            lines.set(lines.size() - 1, last + "…");
        }
        return lines;
    }

    /** Whether {@code columns} fit on the next line: the first one is {@code first} wide, the others {@code width}. */
    private static boolean fits(List<String> lines, int columns, int first, int width) {
        return columns <= Math.max(lines.isEmpty() ? first : width, 1);
    }
}
