package io.github.mfuerstenau.leia.tui.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.tui.search.DocValuesSyntax.Parsed;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The tests of {@code doc_values.rs}, ported. */
class DocValuesSyntaxTest {

    private static Parsed values(String... values) {
        return new Parsed.Values(List.of(values));
    }

    @Test
    void readsValuesAndQuotes() {
        assertThat(DocValuesSyntax.parse("42")).isEqualTo(values("42"));
        assertThat(DocValuesSyntax.parse("  -1 0\t1 ")).isEqualTo(values("-1", "0", "1"));
        assertThat(DocValuesSyntax.parse("\"new york\" boston \"say \\\"hi\\\"\" \"a\\\\b\" \"\""))
                .isEqualTo(values("new york", "boston", "say \"hi\"", "a\\b", ""));
        // A quote inside a value is just a character.
        assertThat(DocValuesSyntax.parse("a\"b")).isEqualTo(values("a\"b"));
        assertThat(DocValuesSyntax.parse("\"*\" \"a*\"")).isEqualTo(values("*", "a*"));
    }

    @Test
    void readsAPrefix() {
        assertThat(DocValuesSyntax.parse("kit*")).isEqualTo(new Parsed.Prefix("kit"));
        assertThat(DocValuesSyntax.parse(" kit** ")).isEqualTo(new Parsed.Prefix("kit*"));
        assertThat(DocValuesSyntax.parse("*")).isEqualTo(new Parsed.Prefix(""));
        assertThatThrownBy(() -> DocValuesSyntax.parse("kit* garden"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A prefix (ending in *) stands alone on the line.");
        assertThatThrownBy(() -> DocValuesSyntax.parse("garden *")).isInstanceOf(IllegalArgumentException.class);
        // In a range, a bound ending in * is a value.
        assertThat(DocValuesSyntax.parse("[a* TO b]"))
                .isEqualTo(new Parsed.Range(Optional.of("a*"), Optional.of("b"), true, true));
    }

    @Test
    void readsRanges() {
        assertThat(DocValuesSyntax.parse("[10 TO 20]"))
                .isEqualTo(new Parsed.Range(Optional.of("10"), Optional.of("20"), true, true));
        assertThat(DocValuesSyntax.parse("{* TO \"*\"}"))
                .isEqualTo(new Parsed.Range(Optional.empty(), Optional.of("*"), false, false));
        assertThat(DocValuesSyntax.parse("[ \"a b\"  TO * }"))
                .isEqualTo(new Parsed.Range(Optional.of("a b"), Optional.empty(), true, false));
    }

    @Test
    void saysWhatIsWrong() {
        for (String bad :
                List.of("", "   ", "[1 TO 2", "[1 to 2]", "[1 2]", "[1 \"TO\" 2]", "\"open", "\"a\"b", "\"\\n\"")) {
            assertThatThrownBy(() -> DocValuesSyntax.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void quotesWhatWouldReadOtherwise() {
        for (String value : List.of("plain", "-1.5", "a\"b", "new york", "", "*", "a*", "[x", "{x", "\"q", "a\\b")) {
            String line = DocValuesSyntax.quote(value);
            assertThat(DocValuesSyntax.parse(line)).as(line).isEqualTo(values(value));
        }
        assertThat(DocValuesSyntax.quote("plain")).isEqualTo("plain");
        assertThat(DocValuesSyntax.quote("new york")).isEqualTo("\"new york\"");
    }

    @Test
    void typesTheValuesForTheField() {
        DocValuesQuery query = DocValuesSyntax.query("price", Optional.of(DocValuesNumber.DOUBLE), true, "1.5 -2");
        assertThat(query.number()).hasValue(DocValuesNumber.DOUBLE);
        assertThat(query.values()).hasValue(List.of(new BytesValue("1.5", false), new BytesValue("-2", false)));
        assertThat(query.range()).isEmpty();

        query = DocValuesSyntax.query("checksum", Optional.empty(), true, "[80FF TO *]");
        assertThat(query.range())
                .hasValue(new DocValuesRange(Optional.of(new BytesValue("80ff", true)), Optional.empty(), true, true));
        assertThatThrownBy(() -> DocValuesSyntax.query("checksum", Optional.empty(), true, "8"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocValuesSyntax.query("checksum", Optional.empty(), true, "zz"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DocValuesSyntax.query("category", Optional.empty(), false, "zz")
                        .values())
                .isPresent();

        query = DocValuesSyntax.query("checksum", Optional.empty(), true, "80FF*");
        assertThat(query.prefix()).hasValue(new BytesValue("80ff", true));
        assertThat(query.values()).isEmpty();
        assertThat(query.range()).isEmpty();
        assertThat(DocValuesSyntax.query("category", Optional.empty(), false, "*")
                        .prefix())
                .hasValue(new BytesValue("", false));
        assertThatThrownBy(() -> DocValuesSyntax.query("checksum", Optional.empty(), true, "8*"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocValuesSyntax.query("price", Optional.of(DocValuesNumber.LONG), false, "1*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A prefix is for doc values of bytes; numbers take a range.");
    }
}
