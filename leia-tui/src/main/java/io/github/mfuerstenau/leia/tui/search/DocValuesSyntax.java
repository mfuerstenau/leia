package io.github.mfuerstenau.leia.tui.search;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The query line of the search screen's doc values mode (ADR 0033): one value, several values (a
 * set), or a range in Lucene's syntax (Rust's {@code doc_values.rs}).
 *
 * <pre>
 * 42          -1 0 1          "new york" boston
 * [10 TO 20]  {0 TO *]        [* TO "m"}
 * </pre>
 *
 * <p>Values are separated by whitespace. A value in quotes may hold whitespace, {@code \"} and
 * {@code \\}; an unquoted {@code *} is an open bound. A line that can't be read throws an {@link
 * IllegalArgumentException} with the message to show.
 */
public final class DocValuesSyntax {

    private DocValuesSyntax() {}

    /** What the line says, before it is typed for a field. */
    public sealed interface Parsed {
        record Values(List<String> values) implements Parsed {}

        /** A range; an empty bound is open. */
        record Range(Optional<String> lower, Optional<String> upper, boolean includeLower, boolean includeUpper)
                implements Parsed {}
    }

    /**
     * The query for {@code line} on {@code field}. {@code number} says how a number field encodes its
     * numbers, and is empty for a field of bytes, whose values are hex if {@code hex}.
     */
    public static DocValuesQuery query(String field, Optional<DocValuesNumber> number, boolean hex, String line) {
        boolean binary = hex && number.isEmpty();
        return switch (parse(line)) {
            case Parsed.Values values ->
                new DocValuesQuery(
                        field,
                        number,
                        Optional.of(values.values().stream()
                                .map(text -> value(text, binary))
                                .toList()),
                        Optional.empty());
            case Parsed.Range range ->
                new DocValuesQuery(
                        field,
                        number,
                        Optional.empty(),
                        Optional.of(new DocValuesRange(
                                range.lower().map(text -> value(text, binary)),
                                range.upper().map(text -> value(text, binary)),
                                range.includeLower(),
                                range.includeUpper())));
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
        List<String> values = tokens(line).stream().map(Token::text).toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Type a value, several values, or a range.");
        }
        return new Parsed.Values(values);
    }

    /** {@code value} as one value of the line: in quotes if it would read otherwise as it is. */
    public static String quote(String value) {
        boolean plain = !value.isEmpty()
                && !value.equals("*")
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
        Optional<String> bound() {
            return quoted || !text.equals("*") ? Optional.of(text) : Optional.empty();
        }
    }

    /** The whitespace-separated values of {@code text}, a quoted one taking {@code \"} and {@code \\}. */
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
            i++;
            while (true) {
                if (i >= chars.length) {
                    throw new IllegalArgumentException("A quote is not closed.");
                }
                int next = chars[i++];
                if (next == '"') {
                    break;
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
            if (i < chars.length && !isWhitespace(chars[i])) {
                throw new IllegalArgumentException("A closing quote is followed by a space or the end.");
            }
            tokens.add(new Token(token.toString(), true));
        }
        return tokens;
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
