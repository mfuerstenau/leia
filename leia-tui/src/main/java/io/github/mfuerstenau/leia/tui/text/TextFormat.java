package io.github.mfuerstenau.leia.tui.text;

import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Span;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * How text from the index is shown: control characters escaped, so that they can't break the
 * layout, and byte strings that are not UTF-8 as {@code 0x} and their hex.
 */
public final class TextFormat {

    /** How a match of a pattern is highlighted: black on yellow. */
    public static final Style MATCH = Style.EMPTY.black().onYellow();

    private TextFormat() {}

    /** {@code bytes} in lowercase hex, two digits each. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

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

    /**
     * Where matches given as UTF-8 byte offsets fall in {@code text}: each as its start and end in
     * {@code text}'s characters, in order. A match is cut to the text; one that is empty then, starts
     * before the one before it ends, or doesn't fall on characters' boundaries is left out.
     *
     * @param offset the byte offset of {@code text}'s start in the value the matches are offsets into
     */
    public static List<int[]> charRanges(String text, long offset, List<MatchSpan> matches) {
        // The character position of each byte offset that starts a character, and of the end; -1 inside one.
        int[] chars = new int[utf8Length(text) + 1];
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            int length = utf8Length(c);
            chars[bytes] = i;
            for (int b = 1; b < length; b++) {
                chars[bytes + b] = -1;
            }
            bytes += length;
            i += Character.charCount(c);
        }
        chars[bytes] = text.length();
        List<int[]> ranges = new ArrayList<>(matches.size());
        int end = 0;
        for (MatchSpan match : matches) {
            int from = Math.clamp(match.start() - offset, 0, bytes);
            int to = Math.clamp(match.end() - offset, 0, bytes);
            if (to <= from || chars[from] < end || chars[from] < 0 || chars[to] < 0) {
                continue;
            }
            ranges.add(new int[] {chars[from], chars[to]});
            end = chars[to];
        }
        return ranges;
    }

    /** How many bytes {@code text} takes in UTF-8. */
    public static int utf8Length(String text) {
        int length = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            length += utf8Length(c);
            i += Character.charCount(c);
        }
        return length;
    }

    private static int utf8Length(int codePoint) {
        return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    }

    /**
     * The characters {@code from} to {@code to} of {@code text} as spans, escaped, with the parts that
     * {@code ranges} (from {@link #charRanges}) cover highlighted as {@link #MATCH}.
     */
    public static List<Span> marked(String text, int from, int to, List<int[]> ranges) {
        List<Span> spans = new ArrayList<>();
        int pos = from;
        for (int[] range : ranges) {
            int start = Math.max(range[0], pos);
            int end = Math.min(range[1], to);
            if (end <= start) {
                continue;
            }
            if (start > pos) {
                spans.add(Span.raw(escapeControl(text.substring(pos, start))));
            }
            spans.add(Span.styled(escapeControl(text.substring(start, end)), MATCH));
            pos = end;
        }
        if (pos < to) {
            spans.add(Span.raw(escapeControl(text.substring(pos, to))));
        }
        return spans;
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

    /**
     * A score in at most {@code width} columns where it can: four decimals, fewer if it is too wide,
     * and below 0.001, where four decimals would show zeros, as {@code 1.1745e-5}, with as many
     * decimals as fit.
     */
    public static String score(double score, int width) {
        boolean fixed = score == 0 || !Double.isFinite(score) || Math.abs(score) >= 0.001;
        String text = "";
        for (int decimals = 4; decimals >= 0; decimals--) {
            text = fixed
                    ? String.format(Locale.ROOT, "%." + decimals + "f", score)
                    : String.format(Locale.ROOT, "%." + decimals + "e", score).replaceFirst("e-0*", "e-");
            if (text.length() <= width) {
                break;
            }
        }
        return text;
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

    /** A line of a wrapped text: the chars from {@code start} to {@code end}, exclusive. */
    public record LineRange(int start, int end) {}

    /**
     * Where {@code text} breaks into lines at most {@code width} columns wide, as ratatui wraps a
     * paragraph without trimming: at spaces, which the break drops, and inside a word only where the
     * word alone is wider. The first line keeps its indentation. At least one line, empty for an
     * empty text. TamboUI's own word wrap breaks a column early, before a word that ends in the last
     * column.
     */
    public static List<LineRange> wrapRanges(String text, int width) {
        int columns = Math.max(width, 1);
        List<LineRange> lines = new ArrayList<>();
        int start = 0;
        int end = 0;
        int lineWidth = 0;
        // The line has a word on it; until then, the first line holds its indentation.
        boolean open = false;
        int pos = 0;
        while (pos < text.length()) {
            int spaces = pos;
            while (pos < text.length() && text.charAt(pos) == ' ') {
                pos++;
            }
            int wordStart = pos;
            while (pos < text.length() && text.charAt(pos) != ' ') {
                pos++;
            }
            if (wordStart == pos) {
                break;
            }
            int gap = wordStart - spaces;
            int wordWidth = CharWidth.of(text.substring(wordStart, pos));
            boolean indented = !open && lines.isEmpty();
            if ((open || indented) && lineWidth + gap + wordWidth <= columns) {
                if (!open) {
                    start = spaces;
                }
                end = pos;
                lineWidth += gap + wordWidth;
                open = true;
                continue;
            }
            if (open) {
                lines.add(new LineRange(start, end));
            }
            start = wordStart;
            end = wordStart;
            lineWidth = 0;
            open = true;
            for (int i = wordStart; i < pos; ) {
                int c = text.codePointAt(i);
                int charWidth = CharWidth.of(c);
                if (lineWidth > 0 && lineWidth + charWidth > columns) {
                    lines.add(new LineRange(start, end));
                    start = i;
                    lineWidth = 0;
                }
                i += Character.charCount(c);
                end = i;
                lineWidth += charWidth;
            }
        }
        if (open || lines.isEmpty()) {
            lines.add(open ? new LineRange(start, end) : new LineRange(0, text.length()));
        }
        return lines;
    }
}
