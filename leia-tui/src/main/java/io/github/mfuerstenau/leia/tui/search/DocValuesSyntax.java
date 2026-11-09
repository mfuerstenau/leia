package io.github.mfuerstenau.leia.tui.search;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The query line of the search screen's doc values mode (ADR 0033): one value, several values (a
 * set), a range in Lucene's syntax, or a prefix (ADR 0036).
 *
 * <pre>
 * 42          -1 0 1          "new york" boston
 * [10 TO 20]  {0 TO *]        [* TO "m"}
 * kit*        *
 * </pre>
 *
 * <p>Values are separated by whitespace. A value in quotes may hold whitespace, {@code \"} and
 * {@code \\}; an unquoted {@code *} is an open bound. An unquoted value ending in {@code *} is a
 * prefix, which stands alone on the line; a lone {@code *} is the empty prefix, which matches any
 * value. A line that can't be read throws an {@link
 * IllegalArgumentException} with the message to show.
 */
public final class DocValuesSyntax {

    private DocValuesSyntax() {}

    /** What the line says, before it is typed for a field. */
    public sealed interface Parsed {
        record Values(List<String> values) implements Parsed {}

        /** A range; a null bound is open. */
        record Range(@Nullable String lower, @Nullable String upper, boolean includeLower, boolean includeUpper)
                implements Parsed {}

        /** The start of a value, for a field of bytes (ADR 0036). */
        record Prefix(String prefix) implements Parsed {}
    }

    /**
     * The query for {@code line} on {@code field}. {@code number} says how a number field encodes its
     * numbers, and is null for a field of bytes, whose values are hex if {@code hex}.
     *
     * @throws IllegalArgumentException if the line doesn't read, is a prefix for a number field, or
     *     has a value that is not hex
     */
    public static DocValuesQuery query(String field, @Nullable DocValuesNumber number, boolean hex, String line) {
        boolean binary = hex && number == null;
        return switch (parse(line)) {
            case Parsed.Values values ->
                new DocValuesQuery(
                        field,
                        Optional.ofNullable(number),
                        Optional.of(values.values().stream()
                                .map(text -> value(text, binary))
                                .toList()),
                        Optional.empty(),
                        Optional.empty());
            case Parsed.Range range ->
                new DocValuesQuery(
                        field,
                        Optional.ofNullable(number),
                        Optional.empty(),
                        Optional.of(new DocValuesRange(
                                Optional.ofNullable(range.lower()).map(text -> value(text, binary)),
                                Optional.ofNullable(range.upper()).map(text -> value(text, binary)),
                                range.includeLower(),
                                range.includeUpper())),
                        Optional.empty());
            case Parsed.Prefix prefix -> {
                if (number != null) {
                    throw new IllegalArgumentException("A prefix is for doc values of bytes; numbers take a range.");
                }
                yield new DocValuesQuery(
                        field,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(value(prefix.prefix(), binary)));
            }
        };
    }

    private static BytesValue value(String text, boolean binary) {
        if (binary) {
            checkHex(text);
            return new BytesValue(text.toLowerCase(Locale.ROOT), true);
        }
        return new BytesValue(text, false);
    }

    /** Reads the line as values or a range. */
    public static Parsed parse(String typed) {
        String line = typed.strip();
        if (line.startsWith("[") || line.startsWith("{")) {
            boolean includeLower = line.startsWith("[");
            String rest = line.substring(1);
            boolean includeUpper;
            if (rest.endsWith("]")) {
                includeUpper = true;
            } else if (rest.endsWith("}")) {
                includeUpper = false;
            } else {
                throw new IllegalArgumentException("A range ends with ] or }.");
            }
            List<Token> tokens = tokens(rest.substring(0, rest.length() - 1));
            if (tokens.size() != 3
                    || tokens.get(1).quoted()
                    || !tokens.get(1).text().equals("TO")) {
                throw new IllegalArgumentException("A range is [lower TO upper], with * for no bound.");
            }
            return new Parsed.Range(tokens.get(0).bound(), tokens.get(2).bound(), includeLower, includeUpper);
        }
        List<Token> tokens = tokens(line);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Type a value, several values, a range, or a prefix.");
        }
        if (tokens.stream().anyMatch(Token::prefix)) {
            if (tokens.size() > 1) {
                throw new IllegalArgumentException("A prefix (ending in *) stands alone on the line.");
            }
            String text = tokens.getFirst().text();
            return new Parsed.Prefix(text.substring(0, text.length() - 1));
        }
        return new Parsed.Values(tokens.stream().map(Token::text).toList());
    }

    /** {@code value} as one value of the line: in quotes if it would read otherwise as it is. */
    public static String quote(String value) {
        boolean plain = !value.isEmpty()
                && !value.endsWith("*")
                && !value.startsWith("[")
                && !value.startsWith("{")
                && !value.startsWith("\"")
                && value.codePoints().noneMatch(c -> isWhitespace(c) || c == '"' || c == '\\');
        if (plain) {
            return value;
        }
        StringBuilder quoted = new StringBuilder("\"");
        value.codePoints().forEach(c -> {
            if (c == '"' || c == '\\') {
                quoted.append('\\');
            }
            quoted.appendCodePoint(c);
        });
        return quoted.append('"').toString();
    }

    private record Token(String text, boolean quoted) {
        /** The bound the token stands for: empty for an unquoted {@code *}. */
        @Nullable
        String bound() {
            return quoted || !text.equals("*") ? text : null;
        }

        /** Whether the token is a prefix outside a range: unquoted and ending in {@code *}. */
        boolean prefix() {
            return !quoted && text.endsWith("*");
        }
    }

    /** The whitespace-separated values of {@code text}, a quoted one taking {@code \"} and {@code \\}. */
    // Clearer than a loop with one exit (ADR 0088)
    @SuppressWarnings("java:S135")
    private static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        int[] chars = text.codePoints().toArray();
        int i = 0;
        while (i < chars.length) {
            int c = chars[i];
            if (isWhitespace(c)) {
                i++;
                continue;
            }
            StringBuilder token = new StringBuilder();
            if (c != '"') {
                while (i < chars.length && !isWhitespace(chars[i])) {
                    token.appendCodePoint(chars[i++]);
                }
                tokens.add(new Token(token.toString(), false));
                continue;
            }
            i = quoted(chars, i + 1, token);
            if (i < chars.length && !isWhitespace(chars[i])) {
                throw new IllegalArgumentException("A closing quote is followed by a space or the end.");
            }
            tokens.add(new Token(token.toString(), true));
        }
        return tokens;
    }

    /**
     * Appends to {@code token} the text in quotes from {@code i} on, after the opening quote, and
     * returns the index after the closing one.
     */
    private static int quoted(int[] chars, int i, StringBuilder token) {
        while (true) {
            if (i >= chars.length) {
                throw new IllegalArgumentException("A quote is not closed.");
            }
            int next = chars[i++];
            if (next == '"') {
                return i;
            }
            if (next == '\\') {
                if (i < chars.length && (chars[i] == '"' || chars[i] == '\\')) {
                    token.appendCodePoint(chars[i++]);
                    continue;
                }
                throw new IllegalArgumentException("In quotes, \\ is followed by \" or \\.");
            }
            token.appendCodePoint(next);
        }
    }

    /** Unicode's White_Space, as Rust's {@code char::is_whitespace}. */
    private static boolean isWhitespace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
    }

    private static void checkHex(String text) {
        boolean hex = text.length() % 2 == 0
                && text.chars()
                        .allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
        if (!hex) {
            throw new IllegalArgumentException("'" + text + "' is not hex: two of 0-9 and a-f per byte.");
        }
    }
}
