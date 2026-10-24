package io.github.mfuerstenau.leia.tui.documents;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.ValueHighlight;
import io.github.mfuerstenau.leia.tui.Osc52;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The value viewer: the values of one field of a document, one at a time, over the whole documents
 * screen, as text, as a hex dump, as pretty-printed JSON, or split at a delimiter. A stored value
 * that {@code getDocument} cut (ADR 0008) is fetched whole when it is shown. A value from the regex
 * scan shows its matches highlighted in the text mode, and {@code m}/{@code M} jump between them
 * (ADR 0027).
 */
public final class Viewer {

    /** Spaces a tab is shown as. */
    private static final String TAB = "    ";

    /** From this inner width on, a hex row has 16 bytes instead of 8. */
    private static final int WIDE_HEX_WIDTH = 78;

    /** Spaces per JSON nesting level. */
    private static final String JSON_INDENT = "  ";

    /** Keys up to this display width are aligned in the delimited mode; longer ones are not padded. */
    static final int MAX_KEY_WIDTH = 32;

    static final Style JSON_KEY = Style.EMPTY.cyan();
    static final Style JSON_STRING = Style.EMPTY.green();
    static final Style JSON_NUMBER = Style.EMPTY.yellow();
    static final Style JSON_LITERAL = Style.EMPTY.magenta();
    static final Style KEY = Style.EMPTY.cyan();
    static final Style DIM = Style.EMPTY.dim();

    /** Checks that a value is JSON, and says where it isn't: no more than one value. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** What the viewer asks of the documents screen. */
    public sealed interface Event {

        /**
         * Fetch the whole stored value with this index, and report back with {@link Viewer#loaded}
         * or {@link Viewer#failed}.
         */
        record Fetch(int docId, String field, int index) implements Event {}

        /** Put this text on the clipboard. */
        record Copy(String text) implements Event {}

        /** Analyze the shown value's text on the analysis screen. */
        record Analyze(int docId, String field, String text) implements Event {}

        record Close() implements Event {}
    }

    /** How the shown value is shown. */
    public enum Mode {
        TEXT("text"),
        HEX("hex"),
        /** Pretty-printed, as text if it is not JSON. */
        JSON("json"),
        /** One item per line, split at the {@link Delimiters}. */
        DELIMITED("delimited");

        private final String name;

        Mode(String name) {
            this.name = name;
        }
    }

    /**
     * The delimiters of the delimited mode, as typed: {@code \t}, {@code \n}, {@code \r}, {@code
     * \0}, {@code \\}, {@code \xHH} and {@code &#92;u{H…}} stand for characters.
     *
     * @param items between two items; never empty
     * @param keyValue between an item's key and its value; empty if items have no keys
     */
    public record Delimiters(String items, String keyValue) {

        public static final Delimiters DEFAULT = new Delimiters(",", "");

        /** The delimiters with their escapes resolved; throws with what is wrong otherwise. */
        Split split() {
            String items;
            try {
                items = unescape(this.items);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Items: " + e.getMessage());
            }
            if (items.isEmpty()) {
                throw new IllegalArgumentException("Items: enter a delimiter.");
            }
            String keyValue;
            try {
                keyValue = unescape(this.keyValue);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Key/value: " + e.getMessage());
            }
            return new Split(items, keyValue.isEmpty() ? Optional.empty() : Optional.of(keyValue));
        }
    }

    /** {@link Delimiters} with their escapes resolved. */
    record Split(String items, Optional<String> keyValue) {}

    /** {@code typed} with its escapes resolved; throws with what is wrong otherwise. */
    static String unescape(String typed) {
        StringBuilder out = new StringBuilder();
        int[] chars = typed.codePoints().toArray();
        int i = 0;
        while (i < chars.length) {
            int c = chars[i++];
            if (c != '\\') {
                out.appendCodePoint(c);
                continue;
            }
            if (i == chars.length) {
                throw new IllegalArgumentException("a \\ at the end needs a character after it.");
            }
            int escape = chars[i++];
            switch (escape) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case '0' -> out.append('\0');
                case '\\' -> out.append('\\');
                case 'x' -> {
                    int end = Math.min(i + 2, chars.length);
                    out.append(hexEscape(new String(chars, i, end - i)));
                    i = end;
                }
                case 'u' -> {
                    int close = closingBrace(chars, i);
                    out.appendCodePoint(unicodeEscape(chars, i, close));
                    i = close + 1;
                }
                default ->
                    throw new IllegalArgumentException(
                            "unknown escape \\" + Character.toString(escape) + "; \\\\ is a backslash.");
            }
        }
        return out.toString();
    }

    /** The character of {@code \\x} and the two hex digits {@code hex}. */
    private static char hexEscape(String hex) {
        int value = hex.length() == 2 ? parseHex(hex) : -1;
        if (value < 0 || value > 0x7f) {
            throw new IllegalArgumentException("\\x takes two hex digits from 00 to 7f.");
        }
        return (char) value;
    }

    /** The index of the first closing brace from {@code from} on; -1 if none. */
    private static int closingBrace(int[] chars, int from) {
        for (int j = from; j < chars.length; j++) {
            if (chars[j] == '}') {
                return j;
            }
        }
        return -1;
    }

    /** The code point of {@code \\u} and the hex digits in braces from {@code open} to {@code close}. */
    private static int unicodeEscape(int[] chars, int open, int close) {
        int value = open < chars.length && chars[open] == '{' && close > open
                ? parseHex(new String(chars, open + 1, close - open - 1))
                : -1;
        if (value < 0
                || !Character.isValidCodePoint(value)
                || (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE)) {
            throw new IllegalArgumentException("\\u takes a character code in hex in braces, such as \\u{1f}.");
        }
        return value;
    }

    /** {@code hex} as a number, or -1 if it isn't one. */
    private static int parseHex(String hex) {
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /**
     * What a value holds: text, whose hex dump shows its UTF-8; bytes that are not UTF-8, whose text
     * shows them with replacement characters; or a number, its decimal text and the big-endian bytes
     * of its Java type.
     */
    // Never compared, hashed or used as a key (ADR 0088)
    @SuppressWarnings("java:S6218")
    record Content(Kind kind, String text, byte[] bytes) {

        enum Kind {
            TEXT,
            BYTES,
            NUMBER
        }

        static Content text(String text) {
            return new Content(Kind.TEXT, text, text.getBytes(StandardCharsets.UTF_8));
        }

        static Content bytes(byte[] bytes) {
            return new Content(Kind.BYTES, new String(bytes, StandardCharsets.UTF_8), bytes);
        }

        /** A byte string from the backend: text, or hex if {@code binary}. */
        static Content fromWire(String value, boolean binary) {
            if (binary) {
                try {
                    return bytes(HexFormat.of().parseHex(value));
                } catch (IllegalArgumentException _) {
                    // Shown as it came.
                }
            }
            return text(value);
        }

        /** A number's text, with the bytes {@code encode} makes of it; its text's bytes if it can't. */
        static Content number(String text, Encoder encode) {
            byte[] bytes;
            try {
                bytes = encode.bytes(text);
            } catch (NumberFormatException _) {
                bytes = text.getBytes(StandardCharsets.UTF_8);
            }
            return new Content(Kind.NUMBER, text, bytes);
        }
    }

    /** How a number's text becomes the bytes of its Java type. */
    @FunctionalInterface
    interface Encoder {
        byte[] bytes(String text);
    }

    /** A match of a pattern, as a UTF-8 byte range of the whole value. */
    record ByteRange(int from, int to) {}

    /** One value of the field. */
    public static final class Value {

        /** The stored value's index, as {@code getStoredValue} counts them; -1 for a doc value. */
        private final int stored;

        /** The type: {@code string}, {@code long}, {@code sorted set}, … */
        private final String kind;

        private Content content;

        /** The whole value's length in bytes, while only the cut value is in. */
        private OptionalLong fullLength;

        private boolean fetching;

        /** Why the whole value could not be fetched; or null. */
        private String error;

        /** Matches of a pattern, in order. */
        private List<ByteRange> matches = List.of();

        /** How many matches the value has; more than {@code matches} if not all are known. */
        private int matchCount;

        private Value(int stored, String kind, Content content, OptionalLong fullLength) {
            this.stored = stored;
            this.kind = kind;
            this.content = content;
            this.fullLength = fullLength;
        }

        /** The stored value with this index. */
        public static Value stored(int index, StoredValue value) {
            Content content = switch (value.type()) {
                case STRING, BINARY -> Content.fromWire(value.value(), value.binary());
                case INT ->
                    Content.number(
                            value.value(),
                            text -> ByteBuffer.allocate(4)
                                    .putInt(Integer.parseInt(text))
                                    .array());
                case LONG ->
                    Content.number(
                            value.value(),
                            text -> ByteBuffer.allocate(8)
                                    .putLong(Long.parseLong(text))
                                    .array());
                case FLOAT ->
                    Content.number(
                            value.value(),
                            text -> ByteBuffer.allocate(4)
                                    .putFloat(Float.parseFloat(text))
                                    .array());
                case DOUBLE ->
                    Content.number(
                            value.value(),
                            text -> ByteBuffer.allocate(8)
                                    .putDouble(Double.parseDouble(text))
                                    .array());
            };
            return new Value(index, storedType(value.type()), content, value.fullLength());
        }

        /** A byte string from doc values of type {@code kind}. */
        public static Value docValue(String kind, BytesValue value) {
            return new Value(
                    -1, kind + " doc value", Content.fromWire(value.value(), value.binary()), OptionalLong.empty());
        }

        /** A number from doc values of type {@code kind}, shown as {@code text}. */
        public static Value docValueNumber(String kind, long number, String text) {
            byte[] bytes = ByteBuffer.allocate(8).putLong(number).array();
            return new Value(
                    -1, kind + " doc value", new Content(Content.Kind.NUMBER, text, bytes), OptionalLong.empty());
        }

        /** A KNN vector: a component to a line, and the bytes of each component, big-endian. */
        public static Value vector(KnnVector vector) {
            String text = String.join("\n", Documents.vectorTexts(vector));
            ByteBuffer bytes = ByteBuffer.allocate(
                    vector.encoding() == VectorEncoding.FLOAT32 ? 4 * vector.dimension() : vector.dimension());
            vector.floats().forEach(bytes::putFloat);
            vector.bytes().forEach(bytes::put);
            return new Value(
                    -1,
                    Overview.vectorEncoding(vector.encoding()) + " vector",
                    new Content(Content.Kind.NUMBER, text, bytes.array()),
                    OptionalLong.empty());
        }

        String kind() {
            return kind;
        }

        Content content() {
            return content;
        }

        /** The size of the whole value in bytes. */
        private long size() {
            return fullLength.orElse(content.bytes().length);
        }

        /** Whether the value is a JSON object or array, or the beginning of one if it is cut. */
        private boolean looksLikeJson() {
            if (content.kind() != Content.Kind.TEXT) {
                return false;
            }
            String start = content.text().stripLeading();
            return (start.startsWith("{") || start.startsWith("["))
                    && (fullLength.isPresent() || jsonError(content.text()).isEmpty());
        }
    }

    public static String storedType(StoredType type) {
        return switch (type) {
            case STRING -> "string";
            case BINARY -> "binary";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
        };
    }

    /** A piece of a line with its style. */
    record Segment(Style style, String text) {}

    /**
     * The shown value in a mode other than hex, before it is wrapped.
     *
     * @param detail what the mode label at the bottom adds, such as the number of items; or null
     * @param notice why the value is not shown in the mode, such as JSON that does not parse; or null
     */
    record Formatted(List<List<Segment>> lines, String detail, String notice) {

        static Formatted text(String text, String notice) {
            List<List<Segment>> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                lines.add(List.of(new Segment(Style.EMPTY, stripCr(line))));
            }
            return new Formatted(lines, null, notice);
        }

        /**
         * {@code text} with {@code matches}, UTF-8 byte ranges in order, highlighted. A match that is
         * not within the text, or not between characters, is left out; one over a line break is
         * highlighted on both lines.
         */
        static Formatted highlighted(String text, List<ByteRange> matches) {
            int[] charAt = charIndexes(text);
            List<List<Segment>> lines = new ArrayList<>();
            int next = 0;
            int lineStart = 0;
            for (String line : text.split("\n", -1)) {
                String shown = stripCr(line);
                int end = lineStart + utf8Length(shown);
                // Matches that end before this line are done.
                while (next < matches.size() && matches.get(next).to() <= lineStart) {
                    next++;
                }
                List<Segment> segments = segments(text, charAt, matches.subList(next, matches.size()), lineStart, end);
                lines.add(segments);
                lineStart += utf8Length(line) + 1;
            }
            return new Formatted(lines, null, null);
        }

        /**
         * The segments of the line from byte {@code lineStart} to {@code end}, with the parts that
         * {@code matches} cover highlighted.
         */
        private static List<Segment> segments(
                String text, int[] charAt, List<ByteRange> matches, int lineStart, int end) {
            List<Segment> segments = new ArrayList<>();
            int pos = lineStart;
            for (ByteRange match : matches) {
                if (match.from() >= end) {
                    break;
                }
                int from = Math.max(match.from(), pos);
                int to = Math.min(match.to(), end);
                if (to > from && from >= 0 && charAt[from] >= 0 && charAt[to] >= 0) {
                    if (from > pos) {
                        segments.add(new Segment(Style.EMPTY, text.substring(charAt[pos], charAt[from])));
                    }
                    segments.add(new Segment(TextFormat.MATCH, text.substring(charAt[from], charAt[to])));
                    pos = to;
                }
            }
            if (pos < end || segments.isEmpty()) {
                segments.add(new Segment(Style.EMPTY, text.substring(charAt[pos], charAt[end])));
            }
            return segments;
        }

        /** The lines as plain text, as they are copied. */
        String plain() {
            List<String> plain = new ArrayList<>();
            for (List<Segment> line : lines) {
                StringBuilder text = new StringBuilder();
                line.forEach(segment -> text.append(segment.text()));
                plain.add(text.toString());
            }
            return String.join("\n", plain);
        }

        private static String stripCr(String line) {
            return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        }

        /**
         * For each UTF-8 byte offset into {@code text}, up to its length, the char index there; -1
         * inside a character.
         */
        private static int[] charIndexes(String text) {
            int[] charAt = new int[utf8Length(text) + 1];
            Arrays.fill(charAt, -1);
            int offset = 0;
            int i = 0;
            while (i < text.length()) {
                charAt[offset] = i;
                int c = text.codePointAt(i);
                offset += utf8Length(Character.toString(c));
                i += Character.charCount(c);
            }
            charAt[offset] = text.length();
            return charAt;
        }
    }

    /** The UTF-8 length of {@code text}, in bytes. */
    static int utf8Length(String text) {
        int length = 0;
        int i = 0;
        while (i < text.length()) {
            int c = text.codePointAt(i);
            if (c < 0x80) {
                length++;
            } else if (c < 0x800) {
                length += 2;
            } else if (c < 0x10000) {
                length += 3;
            } else {
                length += 4;
            }
            i += Character.charCount(c);
        }
        return length;
    }

    /**
     * The shown value formatted and wrapped at a width.
     *
     * @param matchRows the row each highlighted match starts on, in order
     */
    private record Wrapped(int width, List<Line> lines, List<Integer> matchRows, String detail, String notice) {}

    /** The form that asks for the delimiters of the delimited mode. */
    private static final class DelimiterForm {

        final StringBuilder items;
        final StringBuilder keyValue;

        /** Whether the key/value delimiter is being edited, not the items delimiter. */
        boolean onKeyValue;

        /** What is wrong with the delimiters; or null. */
        String error;

        DelimiterForm(Delimiters delimiters) {
            this.items = new StringBuilder(delimiters.items());
            this.keyValue = new StringBuilder(delimiters.keyValue());
        }

        Delimiters delimiters() {
            return new Delimiters(items.toString(), keyValue.toString());
        }
    }

    private final int docId;
    private final String field;
    private final List<Value> values;
    private int current;
    private Mode mode;
    private Delimiters delimiters;

    /** The delimiter form while it is open; or null. */
    private DelimiterForm form;

    /** The first row shown. */
    private int scroll;

    /** The rows that fit, as of the last render; PageUp and PageDown move by that. */
    private int page = 10;

    /** Null after the shown value, the mode or the delimiters changed. */
    private Wrapped wrapped;

    /** What the last key did, such as copying; cleared by the next key. Or null. */
    private String message;

    /** The match {@code m}/{@code M} last jumped to. */
    private int matchIndex;

    /** Whether the next render scrolls to the match at {@link #matchIndex}. */
    private boolean toMatch;

    /**
     * Views {@code values}, which must not be empty, from the first one on, splitting them at {@code
     * delimiters} in the delimited mode. {@link #fetch} then fetches the first value, if it is cut.
     */
    public Viewer(int docId, String field, List<Value> values, Delimiters delimiters) {
        this.docId = docId;
        this.field = field;
        this.values = values;
        this.delimiters = delimiters;
        // Bytes that are not UTF-8 are easier to read as hex, and JSON pretty-printed.
        Value first = values.getFirst();
        if (first.content.kind() == Content.Kind.BYTES) {
            mode = Mode.HEX;
        } else if (first.looksLikeJson()) {
            mode = Mode.JSON;
        } else {
            mode = Mode.TEXT;
        }
    }

    /**
     * Views {@code values} from the stored value with this index on, as text with {@code spans}
     * highlighted, scrolled to the first of them. {@code matchCount} counts all matches of the value,
     * also those after {@code spans}. {@link #fetch} then fetches the value, if it is cut.
     */
    public static Viewer withMatches(
            int docId,
            String field,
            List<Value> values,
            Delimiters delimiters,
            int index,
            List<MatchSpan> spans,
            int matchCount) {
        return withMatches(
                docId, field, values, delimiters, index, List.of(new ValueHighlight(index, matchCount, spans)));
    }

    /**
     * Views {@code values} from the stored value with this index on, as text with the matches of
     * each value in {@code highlights} highlighted, scrolled to the first of them (ADR 0043). {@link
     * #fetch} then fetches the value, if it is cut.
     */
    public static Viewer withMatches(
            int docId,
            String field,
            List<Value> values,
            Delimiters delimiters,
            int index,
            List<ValueHighlight> highlights) {
        Viewer viewer = new Viewer(docId, field, values, delimiters);
        for (ValueHighlight highlight : highlights) {
            for (Value value : values) {
                if (value.stored == highlight.valueIndex()) {
                    value.matches = highlight.spans().stream()
                            .filter(span -> span.start() >= 0 && span.end() >= 0)
                            .map(span -> new ByteRange(span.start(), span.end()))
                            .toList();
                    value.matchCount = Math.max(highlight.matchCount(), 0);
                }
            }
        }
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).stored == index) {
                viewer.current = i;
                break;
            }
        }
        viewer.mode = Mode.TEXT;
        viewer.toMatch = true;
        return viewer;
    }

    /** Fetches the shown value whole, if it is cut and not fetched yet. */
    public Optional<Event> fetch() {
        Value value = values.get(current);
        if (value.stored < 0 || value.fullLength.isEmpty() || value.fetching || value.error != null) {
            return Optional.empty();
        }
        value.fetching = true;
        return Optional.of(new Event.Fetch(docId, field, value.stored));
    }

    private Optional<Value> stored(int docId, String field, int index) {
        if (docId != this.docId || !field.equals(this.field)) {
            return Optional.empty();
        }
        return values.stream().filter(value -> value.stored == index).findFirst();
    }

    /** Shows the whole value instead of the cut one. */
    public void loaded(int docId, String field, int index, StoredValue value) {
        Value whole = Value.stored(0, value);
        stored(docId, field, index).ifPresent(shown -> {
            shown.content = whole.content;
            shown.fullLength = whole.fullLength;
            shown.fetching = false;
            wrapped = null;
        });
    }

    public void failed(int docId, String field, int index, String error) {
        stored(docId, field, index).ifPresent(shown -> {
            shown.fetching = false;
            shown.error = error;
        });
    }

    public Mode mode() {
        return mode;
    }

    /** The delimiters last applied, to open the next viewer with. */
    public Delimiters delimiters() {
        return delimiters;
    }

    /** The keys for the footer. */
    public String keys() {
        if (form != null) {
            return " type a delimiter   Tab/↑↓ other delimiter   ⏎ apply   Esc cancel";
        }
        if (!values.get(current).matches.isEmpty()) {
            return " Esc close   m/M next/previous match   ←/→ value   t text   h hex   J json   d delimited   y copy   "
                    + "a analyze   ↑↓ PgUp/PgDn Home/End";
        }
        return " Esc close   ←/→ value   t text   h hex   J json   d delimited   y copy   a analyze   "
                + "↑↓ PgUp/PgDn Home/End";
    }

    public Optional<Event> handleKey(KeyEvent key) {
        message = null;
        if (form != null) {
            formKey(key);
            return Optional.empty();
        }
        Value value = values.get(current);
        switch (key.code()) {
            case ESCAPE -> {
                return Optional.of(new Event.Close());
            }
            case LEFT -> {
                return previous();
            }
            case RIGHT -> {
                return next();
            }
            case DOWN -> scrollBy(1);
            case UP -> scrollBy(-1);
            case PAGE_DOWN -> scrollBy(page);
            case PAGE_UP -> scrollBy(-page);
            case HOME -> scroll = 0;
            // Clamped when rendered.
            case END -> scroll = Integer.MAX_VALUE;
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'q', 'v' -> {
                        return Optional.of(new Event.Close());
                    }
                    case 'p' -> {
                        return previous();
                    }
                    case 'n' -> {
                        return next();
                    }
                    case 'm', 'M' -> {
                        if (!value.matches.isEmpty()) {
                            int last = value.matches.size() - 1;
                            matchIndex = key.codePoint() == 'm'
                                    ? Math.min(matchIndex + 1, last)
                                    : Math.max(matchIndex - 1, 0);
                            if (mode != Mode.TEXT) {
                                setMode(Mode.TEXT);
                            }
                            toMatch = true;
                        }
                    }
                    case 't' -> setMode(Mode.TEXT);
                    case 'h' -> toggleMode(Mode.HEX);
                    case 'J' -> toggleMode(Mode.JSON);
                    case 'd' -> form = new DelimiterForm(delimiters);
                    case 'y' -> {
                        return copy();
                    }
                    case 'a' -> {
                        return Optional.of(new Event.Analyze(docId, field, value.content.text()));
                    }
                    case 'j' -> scrollBy(1);
                    case 'k' -> scrollBy(-1);
                    case ' ' -> scrollBy(page);
                    case 'g' -> scroll = 0;
                    case 'G' -> scroll = Integer.MAX_VALUE;
                    default -> {}
                }
            }
            default -> {}
        }
        return Optional.empty();
    }

    private Optional<Event> previous() {
        if (current == 0) {
            return Optional.empty();
        }
        show(current - 1);
        return fetch();
    }

    private Optional<Event> next() {
        if (current + 1 >= values.size()) {
            return Optional.empty();
        }
        show(current + 1);
        return fetch();
    }

    private void scrollBy(int rows) {
        scroll = Math.clamp((long) scroll + rows, 0, Integer.MAX_VALUE);
    }

    /** Edits the delimiter form. */
    private void formKey(KeyEvent key) {
        StringBuilder input = form.onKeyValue ? form.keyValue : form.items;
        switch (key.code()) {
            case ESCAPE -> form = null;
            case ENTER -> {
                Delimiters typed = form.delimiters();
                try {
                    typed.split();
                    form = null;
                    delimiters = typed;
                    setMode(Mode.DELIMITED);
                } catch (IllegalArgumentException e) {
                    form.error = e.getMessage();
                }
            }
            case TAB, UP, DOWN -> form.onKeyValue = !form.onKeyValue;
            case CHAR -> {
                input.appendCodePoint(key.codePoint());
                form.error = null;
            }
            case BACKSPACE -> {
                if (!input.isEmpty()) {
                    input.setLength(input.offsetByCodePoints(input.length(), -1));
                }
                form.error = null;
            }
            default -> {}
        }
    }

    /** Switches to {@code mode}, or back to text if it is shown already. */
    private void toggleMode(Mode mode) {
        setMode(this.mode == mode ? Mode.TEXT : mode);
    }

    private void setMode(Mode mode) {
        this.mode = mode;
        scroll = 0;
        wrapped = null;
    }

    private void show(int index) {
        current = index;
        scroll = 0;
        wrapped = null;
        // Each value's matches from its first on.
        matchIndex = 0;
        toMatch = !values.get(index).matches.isEmpty();
    }

    /**
     * Copies the shown value as it is shown: the text, the bytes in hex, the pretty-printed JSON,
     * or the items one per line.
     */
    private Optional<Event> copy() {
        Value value = values.get(current);
        if (value.fullLength.isPresent()) {
            message = "Not copied: the whole value is not in.";
            return Optional.empty();
        }
        String text = switch (mode) {
            case TEXT -> value.content.text();
            case HEX -> TextFormat.hex(value.content.bytes());
            case JSON, DELIMITED -> {
                Formatted formatted = format(value.content, mode, delimiters);
                yield formatted.notice() != null ? value.content.text() : formatted.plain();
            }
        };
        int length = utf8Length(text);
        // The terminal may drop a long escape sequence without a word: tmux takes about 1 MiB.
        String caveat = length > Osc52.LARGE_COPY ? "; terminals may drop copies this large" : "";
        message = "Copied " + TextFormat.size(length) + " as " + mode.name + " (OSC 52)" + caveat + ".";
        return Optional.of(new Event.Copy(text));
    }

    public void render(Frame frame, Rect area) {
        Value value = values.get(current);
        String title = " " + field + " · " + (current + 1) + " of " + values.size() + " · " + value.kind + " · "
                + TextFormat.size(value.size()) + " ";
        Block.Builder block = Block.builder().borders(Borders.ALL).title(title).borderStyle(Style.EMPTY.bold());
        Rect inner = block.build().inner(area);
        int height = inner.height();
        page = Math.max(height - 1, 1);

        int bytesPerRow = inner.width() >= WIDE_HEX_WIDTH ? 16 : 8;
        int rows = mode == Mode.HEX
                ? Math.max(Math.ceilDiv(value.content.bytes().length, bytesPerRow), 1)
                : wrapped(inner.width()).lines().size();
        if (toMatch && mode == Mode.TEXT) {
            scrollToMatch(value);
        }
        scroll = Math.clamp(scroll, 0, Math.max(rows - height, 0));
        int start = scroll;
        int end = Math.min(rows, scroll + height);

        Wrapped shown = mode == Mode.HEX ? null : wrapped;
        List<Line> lines = new ArrayList<>();
        if (shown == null) {
            for (int row = start; row < end; row++) {
                lines.add(hexRow(value.content.bytes(), row, bytesPerRow));
            }
        } else {
            lines.addAll(shown.lines().subList(start, end));
        }

        String status = status(value, shown);
        String modeLabel = shown != null && shown.detail() != null ? mode.name + " · " + shown.detail() : mode.name;
        String position = " " + modeLabel + " · rows " + (start + 1) + "–" + end + " of " + rows + " ";
        block.titleBottom(Title.from(position).right());
        frame.renderWidget(Clear.INSTANCE, area);
        frame.renderWidget(
                Paragraph.builder().text(Text.from(lines)).block(block.build()).build(), area);
        // TamboUI's block has one bottom title: the status goes left of the position by hand.
        int room = area.width() - 2 - CharWidth.of(position) - 1;
        if (!status.isEmpty() && room > 0 && area.height() > 1) {
            String text = CharWidth.substringByWidth(" " + TextFormat.escapeControl(status) + " ", room);
            frame.buffer().setString(area.x() + 1, area.bottom() - 1, text, Style.EMPTY);
        }
        if (form != null) {
            renderForm(frame, area, form);
        }
    }

    /** Scrolls to the match that {@code m} or {@code M} chose, once the text has it. */
    private void scrollToMatch(Value value) {
        List<Integer> matchRows = wrapped.matchRows();
        if (matchIndex < matchRows.size()) {
            // Two rows of context above the match.
            scroll = Math.max(matchRows.get(matchIndex) - 2, 0);
            toMatch = false;
        } else if (value.fullLength.isEmpty() || value.error != null) {
            // Otherwise the match is past the cut: once the whole value is in.
            toMatch = false;
        }
    }

    /** What the bottom border says left of the position. */
    private String status(Value value, Wrapped shown) {
        if (message != null) {
            return message;
        }
        if (value.error != null) {
            return "Cut; cannot fetch it whole: " + value.error;
        }
        if (value.fullLength.isPresent()) {
            return "Cut at " + TextFormat.size(value.content.bytes().length) + " of "
                    + TextFormat.size(value.fullLength.getAsLong()) + "; fetching it whole…";
        }
        if (shown != null && shown.notice() != null) {
            return shown.notice();
        }
        if (value.matches.isEmpty()) {
            return "";
        }
        int marked = value.matches.size();
        String more = value.matchCount > marked ? " (" + marked + " of " + value.matchCount + " marked)" : "";
        String hidden = mode == Mode.TEXT ? "" : "; t shows them";
        return "match " + (matchIndex + 1) + " of " + marked + more + hidden;
    }

    /** The shown value formatted in the mode and wrapped at {@code width}, formatted again only when needed. */
    private Wrapped wrapped(int width) {
        if (wrapped == null || wrapped.width() != width) {
            Value value = values.get(current);
            Formatted formatted = mode == Mode.TEXT && !value.matches.isEmpty()
                    ? Formatted.highlighted(value.content.text(), value.matches)
                    : format(value.content, mode, delimiters);
            List<Line> lines = new ArrayList<>();
            List<Integer> matchRows = new ArrayList<>();
            for (List<Segment> line : formatted.lines()) {
                wrap(line, width, lines, matchRows);
            }
            wrapped = new Wrapped(width, lines, matchRows, formatted.detail(), formatted.notice());
        }
        return wrapped;
    }

    private static void renderForm(Frame frame, Rect area, DelimiterForm form) {
        int width = Math.min(area.width(), 56);
        int height = 5;
        Rect popup = new Rect(
                area.x() + (area.width() - width) / 2,
                area.y() + Math.max(area.height() - height, 0) / 2,
                width,
                Math.min(height, area.height()));
        String message = form.error != null ? form.error : "Escapes \\t \\n \\xHH \\u{H…}; no key/value: empty";
        frame.renderWidget(Clear.INSTANCE, popup);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(
                                formLine("Items", form.items.toString(), !form.onKeyValue),
                                formLine("Key/value", form.keyValue.toString(), form.onKeyValue),
                                Line.from(Span.styled(message, DIM))))
                        .block(Block.builder()
                                .borders(Borders.ALL)
                                .title(" Delimited ")
                                .build())
                        .build(),
                popup);
    }

    private static Line formLine(String label, String input, boolean active) {
        return Line.from(
                Span.styled(String.format(Locale.ROOT, "%-10s ", label), active ? Style.EMPTY.bold() : DIM),
                Span.raw(input + (active ? "▏" : "")));
    }

    /** {@code content} in {@code mode}, which must not be hex. */
    static Formatted format(Content content, Mode mode, Delimiters delimiters) {
        String text = content.text();
        return switch (mode) {
            case TEXT, HEX -> Formatted.text(text, null);
            case JSON -> {
                Optional<String> error = jsonError(text);
                yield error.isPresent()
                        ? Formatted.text(text, "Not JSON: " + error.get() + ".")
                        : new Formatted(jsonLines(text), null, null);
            }
            case DELIMITED -> {
                try {
                    yield delimited(text, delimiters.split());
                } catch (IllegalArgumentException e) {
                    yield Formatted.text(text, e.getMessage());
                }
            }
        };
    }

    /** Why {@code text} is not one JSON value, as Jackson says it and where; empty if it is one. */
    static Optional<String> jsonError(String text) {
        try {
            JSON.readValue(text, Object.class);
            return Optional.empty();
        } catch (JacksonException e) {
            TokenStreamLocation location = e.getLocation();
            String where = location == null || location.getLineNr() < 1
                    ? ""
                    : " at line " + location.getLineNr() + " column " + location.getColumnNr();
            return Optional.of(TextFormat.escapeControl(e.getOriginalMessage() + where));
        }
    }

    /**
     * {@code text}, which must be JSON, pretty-printed as it is written: keys stay in their order,
     * numbers and strings keep their text, and empty objects and arrays stay on one line.
     */
    // Clearer than a loop with one exit (ADR 0088)
    @SuppressWarnings("java:S135")
    static List<List<Segment>> jsonLines(String text) {
        List<List<Segment>> lines = new ArrayList<>();
        List<Segment> line = new ArrayList<>();
        // Whether each open container is an object.
        List<Boolean> open = new ArrayList<>();
        boolean expectKey = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isJsonSpace(c)) {
                i++;
                continue;
            }
            switch (c) {
                case '{', '[' -> {
                    char close = closing(c);
                    int next = skipJsonSpace(text, i + 1);
                    if (next < text.length() && text.charAt(next) == close) {
                        line.add(new Segment(Style.EMPTY, "" + c + close));
                        i = next + 1;
                        continue;
                    }
                    line.add(new Segment(Style.EMPTY, String.valueOf(c)));
                    open.add(c == '{');
                    expectKey = c == '{';
                    line = newLine(lines, line, open.size());
                    i++;
                }
                case '}', ']' -> {
                    open.removeLast();
                    line = newLine(lines, line, open.size());
                    line.add(new Segment(Style.EMPTY, String.valueOf(c)));
                    i++;
                }
                case ',' -> {
                    line.add(new Segment(Style.EMPTY, ","));
                    expectKey = !open.isEmpty() && open.getLast();
                    line = newLine(lines, line, open.size());
                    i++;
                }
                case ':' -> {
                    line.add(new Segment(Style.EMPTY, ": "));
                    expectKey = false;
                    i++;
                }
                case '"' -> {
                    int end = jsonStringEnd(text, i);
                    line.add(new Segment(expectKey ? JSON_KEY : JSON_STRING, text.substring(i, end + 1)));
                    i = end + 1;
                }
                default -> {
                    int end = jsonLiteralEnd(text, i);
                    line.add(new Segment(jsonLiteralStyle(c), text.substring(i, end)));
                    i = end;
                }
            }
        }
        lines.add(line);
        return lines;
    }

    /** The bracket that closes {@code open}. */
    private static char closing(char open) {
        return open == '{' ? '}' : ']';
    }

    private static int skipJsonSpace(String text, int from) {
        int next = from;
        while (next < text.length() && isJsonSpace(text.charAt(next))) {
            next++;
        }
        return next;
    }

    /** The index of the quote that closes the string at {@code start}, or of the last character. */
    private static int jsonStringEnd(String text, int start) {
        int end = start + 1;
        while (end < text.length() && text.charAt(end) != '"') {
            end += text.charAt(end) == '\\' ? 2 : 1;
        }
        return Math.min(end, text.length() - 1);
    }

    /** The index after the number or literal at {@code start}. */
    private static int jsonLiteralEnd(String text, int start) {
        int end = start;
        while (end < text.length() && !isJsonSpace(text.charAt(end)) && ",:]}".indexOf(text.charAt(end)) < 0) {
            end++;
        }
        return end;
    }

    private static Style jsonLiteralStyle(char first) {
        return first == '-' || (first >= '0' && first <= '9') ? JSON_NUMBER : JSON_LITERAL;
    }

    private static boolean isJsonSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** Ends {@code line}, and returns the next one, indented {@code depth} levels. */
    private static List<Segment> newLine(List<List<Segment>> lines, List<Segment> line, int depth) {
        lines.add(line);
        List<Segment> next = new ArrayList<>();
        next.add(new Segment(Style.EMPTY, JSON_INDENT.repeat(depth)));
        return next;
    }

    /**
     * {@code text} split at the delimiters, one item per line: numbered, or with aligned keys if
     * items have keys. Control characters in items are escaped, so an item stays on its line.
     */
    static Formatted delimited(String text, Split split) {
        List<String> items = splitAll(text, split.items());
        String detail = items.size() == 1 ? "1 item" : items.size() + " items";
        List<List<Segment>> lines = new ArrayList<>();
        Optional<String> keyValues = split.keyValue();
        if (keyValues.isEmpty()) {
            String number = "%" + Integer.toString(items.size()).length() + "d  ";
            for (int i = 0; i < items.size(); i++) {
                lines.add(List.of(
                        new Segment(DIM, String.format(Locale.ROOT, number, i + 1)),
                        new Segment(Style.EMPTY, TextFormat.escapeControl(items.get(i)))));
            }
            return new Formatted(lines, detail, null);
        }
        String keyValue = keyValues.get();
        // Each item's key, and its value; null for an item without one.
        List<String[]> pairs = new ArrayList<>();
        for (String item : items) {
            int at = item.indexOf(keyValue);
            pairs.add(
                    at < 0
                            ? new String[] {TextFormat.escapeControl(item), null}
                            : new String[] {
                                TextFormat.escapeControl(item.substring(0, at)),
                                TextFormat.escapeControl(item.substring(at + keyValue.length()))
                            });
        }
        int keyWidth = pairs.stream()
                .filter(pair -> pair[1] != null)
                .mapToInt(pair -> CharWidth.of(pair[0]))
                .filter(width -> width <= MAX_KEY_WIDTH)
                .max()
                .orElse(0);
        String separator = " " + TextFormat.escapeControl(keyValue) + " ";
        for (String[] pair : pairs) {
            if (pair[1] == null) {
                lines.add(List.of(new Segment(Style.EMPTY, pair[0])));
            } else {
                lines.add(List.of(
                        new Segment(KEY, pair[0]),
                        new Segment(Style.EMPTY, " ".repeat(Math.max(keyWidth - CharWidth.of(pair[0]), 0))),
                        new Segment(DIM, separator),
                        new Segment(Style.EMPTY, pair[1])));
            }
        }
        return new Formatted(lines, detail, null);
    }

    /** {@code text} split at every {@code delimiter}, empty pieces included, as Rust's {@code split}. */
    private static List<String> splitAll(String text, String delimiter) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        int at;
        while ((at = text.indexOf(delimiter, start)) >= 0) {
            pieces.add(text.substring(start, at));
            start = at + delimiter.length();
        }
        pieces.add(text.substring(start));
        return pieces;
    }

    /**
     * Appends {@code line} to {@code out} as lines no wider than {@code width}: tabs as spaces,
     * control characters escaped. Appends the row each highlighted match starts on to {@code
     * matchRows}.
     */
    static void wrap(List<Segment> line, int width, List<Line> out, List<Integer> matchRows) {
        LineWrapper wrapper = new LineWrapper(Math.max(width, 1), out, matchRows);
        for (Segment segment : line) {
            wrapper.add(segment);
        }
        out.add(Line.from(wrapper.spans));
    }

    /** Wraps a line at {@code columns}, segment after segment, noting the row each match starts on. */
    private static final class LineWrapper {
        private final int columns;
        private final List<Line> out;
        private final List<Integer> matchRows;
        private List<Span> spans = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private int used;

        LineWrapper(int columns, List<Line> out, List<Integer> matchRows) {
            this.columns = columns;
            this.out = out;
            this.matchRows = matchRows;
        }

        void add(Segment segment) {
            boolean first = segment.style().equals(TextFormat.MATCH);
            for (int c : segment.text().codePoints().toArray()) {
                for (int s : shown(c).codePoints().toArray()) {
                    int w = CharWidth.of(s);
                    if (used + w > columns && used > 0) {
                        breakLine(segment.style());
                    }
                    if (first) {
                        matchRows.add(out.size());
                        first = false;
                    }
                    current.appendCodePoint(s);
                    used += w;
                }
            }
            flush(segment.style());
        }

        private void breakLine(Style style) {
            flush(style);
            out.add(Line.from(spans));
            spans = new ArrayList<>();
            used = 0;
        }

        private void flush(Style style) {
            if (!current.isEmpty()) {
                spans.add(Span.styled(current.toString(), style));
                current.setLength(0);
            }
        }

        /** A tab as {@link #TAB}, and a control character escaped. */
        private static String shown(int c) {
            if (c == '\t') {
                return TAB;
            }
            String text = Character.toString(c);
            return Character.isISOControl(c) ? TextFormat.escapeControl(text) : text;
        }
    }

    /** One row of the hex dump: the offset, the bytes in hex, and the printable ASCII ones. */
    private static Line hexRow(byte[] bytes, int row, int bytesPerRow) {
        int start = row * bytesPerRow;
        int from = Math.min(start, bytes.length);
        int to = Math.min(start + bytesPerRow, bytes.length);
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < bytesPerRow; i++) {
            if (i == 8) {
                hex.append(' ');
            }
            if (from + i < to) {
                int b = bytes[from + i] & 0xff;
                hex.append(String.format(Locale.ROOT, "%02x ", b));
                ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
            } else {
                hex.append("   ");
            }
        }
        return Line.from(
                Span.styled(String.format(Locale.ROOT, "%08x  ", start), DIM),
                Span.raw(hex.toString()),
                Span.styled(" |", DIM),
                Span.raw(ascii.toString()),
                Span.styled("|", DIM));
    }

    /** {@link GetDocumentResult#MAX_VALUE_LENGTH} for messages. */
    static String cutSize() {
        return TextFormat.size(GetDocumentResult.MAX_VALUE_LENGTH);
    }
}
