package io.github.mfuerstenau.leia.tui.documents;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.tui.Osc52;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.documents.Viewer.ByteRange;
import io.github.mfuerstenau.leia.tui.documents.Viewer.Delimiters;
import io.github.mfuerstenau.leia.tui.documents.Viewer.Event;
import io.github.mfuerstenau.leia.tui.documents.Viewer.Formatted;
import io.github.mfuerstenau.leia.tui.documents.Viewer.Mode;
import io.github.mfuerstenau.leia.tui.documents.Viewer.Segment;
import io.github.mfuerstenau.leia.tui.documents.Viewer.Split;
import io.github.mfuerstenau.leia.tui.documents.Viewer.Value;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The tests of {@code viewer.rs}, ported. */
class ViewerTest {

    private static StoredValue stored(StoredType type, String value, boolean binary) {
        return new StoredValue(type, value, binary);
    }

    private static StoredValue text(String value) {
        return stored(StoredType.STRING, value, false);
    }

    private static StoredValue cut(String value, long fullLength) {
        return new StoredValue(
                StoredType.STRING, value, false, OptionalLong.of(fullLength), OptionalLong.of(fullLength));
    }

    private static String render(Viewer viewer, int width, int height) {
        return TestScreens.render(frame -> viewer.render(frame, frame.area()), width, height);
    }

    private static Viewer viewer(Value... values) {
        Viewer viewer = new Viewer(4, "body", List.of(values), Delimiters.DEFAULT);
        assertThat(viewer.fetch()).isEmpty();
        return viewer;
    }

    private static List<Line> wrap(List<Segment> line, int width, List<Integer> rows) {
        List<Line> lines = new ArrayList<>();
        Viewer.wrap(line, width, lines, rows);
        return lines;
    }

    private static String plain(Line line) {
        return line.spans().stream().map(Span::content).collect(Collectors.joining());
    }

    private static List<String> plain(List<List<Segment>> lines) {
        return lines.stream()
                .map(line -> line.stream().map(Segment::text).collect(Collectors.joining()))
                .toList();
    }

    private static Segment segment(Style style, String text) {
        return new Segment(style, text);
    }

    @Test
    void highlightsMatchesThroughTabsControlCharactersAndLineBreaks() {
        // "red" at 2..5, "re\u0001d" at 6..10, and one over the line break at 4..7.
        String text = "a\tred\nre\u0001d x";
        Formatted formatted = Formatted.highlighted(text, List.of(new ByteRange(2, 5), new ByteRange(6, 10)));
        List<Line> lines = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        for (List<Segment> line : formatted.lines()) {
            Viewer.wrap(line, 40, lines, rows);
        }

        List<List<String>> marked = lines.stream()
                .map(line -> line.spans().stream()
                        .map(span -> span.content()
                                + (span.style().equals(Palette.current().match()) ? "*" : ""))
                        .toList())
                .toList();
        assertThat(marked).containsExactly(List.of("a    ", "red*"), List.of("re\\u{1}d*", " x"));
        assertThat(rows).containsExactly(0, 1);

        Formatted over = Formatted.highlighted("ab\ncd", List.of(new ByteRange(1, 4)));
        assertThat(over.lines())
                .containsExactly(
                        List.of(
                                segment(Style.EMPTY, "a"),
                                segment(Palette.current().match(), "b")),
                        List.of(segment(Palette.current().match(), "c"), segment(Style.EMPTY, "d")));
        // Out of range or inside a character: left out.
        Formatted odd = Formatted.highlighted("äx", List.of(new ByteRange(1, 2), new ByteRange(5, 9)));
        assertThat(odd.lines()).containsExactly(List.of(segment(Style.EMPTY, "äx")));
    }

    @Test
    void opensOnTheValueWithMatchesAndJumpsBetweenThem() {
        String longText = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> i == 20 || i == 26 ? "a red line" : "line")
                .collect(Collectors.joining("\n"));
        int start = longText.indexOf("red");
        int second = longText.lastIndexOf("red");
        Viewer viewer = Viewer.withMatches(
                4,
                "body",
                List.of(Value.stored(0, text("{\"json\": true}")), Value.stored(1, text(longText))),
                Delimiters.DEFAULT,
                1,
                List.of(new MatchSpan(start, start + 3), new MatchSpan(second, second + 3)),
                5);
        assertThat(viewer.fetch()).isEmpty();
        assertThat(viewer.mode()).isEqualTo(Mode.TEXT);
        assertThat(viewer.keys().text()).contains("m/M next/previous match");

        String screen = render(viewer, 70, 8);
        assertThat(screen).contains("body · 2 of 2");
        // Two rows of context above the first match, on row 20.
        assertThat(screen).contains("rows 18–23 of 30", "match 1 of 2 (2 of 5 marked)");

        viewer.handleKey(key('m'));
        screen = render(viewer, 70, 8);
        assertThat(screen).contains("rows 24–29 of 30", "match 2 of 2");
        // The last match stays the last.
        viewer.handleKey(key('m'));
        viewer.handleKey(key('M'));
        assertThat(render(viewer, 70, 8)).contains("rows 18–23 of 30");

        // From another mode, m comes back to the text.
        viewer.handleKey(key('h'));
        viewer.handleKey(key('m'));
        assertThat(viewer.mode()).isEqualTo(Mode.TEXT);
    }

    @Test
    void valuesWithoutMatchesHaveNoMatchKeys() {
        Viewer viewer = viewer(Value.stored(0, text("red")));

        assertThat(viewer.keys().text()).doesNotContain("match");
        assertThat(viewer.handleKey(key('m'))).isEmpty();
        assertThat(render(viewer, 40, 6)).doesNotContain("match");
    }

    @Test
    void showsTextWithItsLines() {
        Viewer viewer = viewer(Value.stored(0, text("one\r\ntwo\tthree\u0001")));

        String screen = render(viewer, 40, 6);
        assertThat(screen)
                .contains("body · 1 of 1 · string · 15 B", "│one ", "│two    three\\u{1}", "text · rows 1–2 of 2");
    }

    /** {@code text} in the text mode, wrapped at {@code width}. */
    private static List<String> wrapText(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (List<Segment> line : Formatted.text(text, null).lines()) {
            wrap(line, width, new ArrayList<>()).forEach(wrapped -> lines.add(plain(wrapped)));
        }
        return lines;
    }

    @Test
    void wrapsAtTheWidth() {
        assertThat(wrapText("abcdef", 4)).containsExactly("abcd", "ef");
        assertThat(wrapText("", 4)).containsExactly("");
        assertThat(wrapText("a\n\nb", 4)).containsExactly("a", "", "b");
        // Wide characters take two columns.
        assertThat(wrapText("日本語", 4)).containsExactly("日本", "語");
    }

    @Test
    void wrapsAcrossStyles() {
        List<Segment> line = List.of(
                segment(Palette.current().accent(), "key"),
                segment(Viewer.dim(), " = "),
                segment(Style.EMPTY, "value"));

        assertThat(wrap(line, 5, new ArrayList<>()))
                .containsExactly(
                        Line.from(Span.styled("key", Palette.current().accent()), Span.styled(" =", Viewer.dim())),
                        Line.from(Span.styled(" ", Viewer.dim()), Span.styled("valu", Style.EMPTY)),
                        Line.from(Span.styled("e", Style.EMPTY)));
    }

    @Test
    void showsAHexDumpAndSwitchesBack() {
        Viewer viewer = viewer(Value.stored(0, text("grüße, Welt! 0123456789")));
        assertThat(viewer.handleKey(key('h'))).isEmpty();

        String screen = render(viewer, 80, 6);
        assertThat(screen)
                .contains(
                        "00000000  67 72 c3 bc c3 9f 65 2c  20 57 65 6c 74 21 20 30  |gr....e, Welt! 0|",
                        "00000010  31 32 33 34 35 36 37 38  39",
                        "|123456789|");
        // 8 bytes a row when 16 don't fit.
        assertThat(render(viewer, 60, 6)).contains("00000008  20 57 65 6c 74 21 20 30  | Welt! 0|");

        viewer.handleKey(key('h'));
        assertThat(render(viewer, 80, 6)).contains("grüße, Welt!");
    }

    @Test
    void opensBytesAsHexAndNumbersWithTheirBits() {
        Viewer viewer = viewer(
                Value.stored(0, stored(StoredType.BINARY, "ff0041", true)),
                Value.stored(1, stored(StoredType.INT, "-2", false)),
                Value.stored(2, stored(StoredType.DOUBLE, "NaN", false)),
                Value.docValueNumber("sorted numeric", 4612811918334230528L, "2.5"));
        assertThat(viewer.mode()).isEqualTo(Mode.HEX);
        assertThat(render(viewer, 80, 5)).contains("ff 00 41");

        viewer.handleKey(key(KeyCode.RIGHT));
        assertThat(render(viewer, 80, 5)).contains("2 of 4 · int · 4 B", "ff ff ff fe");
        viewer.handleKey(key(KeyCode.RIGHT));
        assertThat(render(viewer, 80, 5)).contains("7f f8 00 00 00 00 00 00");
        viewer.handleKey(key(KeyCode.RIGHT));
        assertThat(render(viewer, 80, 5)).contains("sorted numeric doc value · 8 B", "40 04 00 00 00 00 00 00");
        // The last value: → stays.
        assertThat(viewer.handleKey(key(KeyCode.RIGHT))).isEmpty();
        viewer.handleKey(key('h'));
        assertThat(render(viewer, 80, 5)).contains("│2.5");
        // Bytes that are not UTF-8 as text.
        viewer.handleKey(key(KeyCode.HOME));
        for (int i = 0; i < 3; i++) {
            viewer.handleKey(key(KeyCode.LEFT));
        }
        assertThat(render(viewer, 80, 5)).contains("│�\\u{0}A");
    }

    @Test
    void scrollsWithinTheValue() {
        String lines = IntStream.rangeClosed(1, 50).mapToObj(n -> "line " + n).collect(Collectors.joining("\n"));
        Viewer viewer = viewer(Value.stored(0, text(lines)));
        // 10 rows inside the border.
        render(viewer, 40, 12);

        viewer.handleKey(key(KeyCode.PAGE_DOWN));
        assertThat(render(viewer, 40, 12)).contains("│line 10 ", "rows 10–19 of 50");
        viewer.handleKey(key(KeyCode.END));
        assertThat(render(viewer, 40, 12)).contains("│line 50", "rows 41–50 of 50");
        viewer.handleKey(key(KeyCode.UP));
        // g opens the go-to menu, whose g sends Home.
        assertThat(viewer.handleKey(key('g'))).contains(new Viewer.Event.GoTo());
        viewer.handleKey(key(KeyCode.HOME));
        assertThat(render(viewer, 40, 12)).contains("rows 1–10 of 50");
    }

    @Test
    void fetchesACutValueWhole() {
        Viewer viewer = new Viewer(
                4,
                "body",
                List.of(
                        Value.stored(0, text("small")),
                        Value.stored(1, cut("abc", 70_000)),
                        Value.docValue("sorted set", new BytesValue("a", false))),
                Delimiters.DEFAULT);
        assertThat(viewer.fetch()).isEmpty();

        assertThat(viewer.handleKey(key(KeyCode.RIGHT))).hasValue(new Event.Fetch(4, "body", 1));
        assertThat(render(viewer, 80, 6)).contains("string · 68.4 KiB", "Cut at 3 B of 68.4 KiB; fetching it whole…");
        assertThat(viewer.handleKey(key('y'))).isEmpty();
        assertThat(render(viewer, 80, 6)).contains("Not copied: the whole value is not in.");
        // Fetched once.
        viewer.handleKey(key(KeyCode.LEFT));
        assertThat(viewer.handleKey(key(KeyCode.RIGHT))).isEmpty();

        // Answers for another document or field are dropped.
        viewer.loaded(5, "body", 1, text("wrong"));
        viewer.loaded(4, "title", 1, text("wrong"));
        viewer.loaded(4, "body", 1, text("abcdefg"));
        String screen = render(viewer, 80, 6);
        assertThat(screen).contains("│abcdefg", "string · 7 B").doesNotContain("Cut");
        assertThat(viewer.handleKey(key('y'))).hasValue(new Event.Copy("abcdefg"));
        assertThat(render(viewer, 80, 6)).contains("Copied 7 B as text (OSC 52).");
    }

    @Test
    void showsWhyACutValueIsNotWhole() {
        Viewer viewer = new Viewer(1, "f", List.of(Value.stored(0, cut("abc", 70_000))), Delimiters.DEFAULT);
        assertThat(viewer.fetch()).hasValue(new Event.Fetch(1, "f", 0));

        viewer.failed(1, "f", 0, "gone");
        assertThat(render(viewer, 80, 6)).contains("Cut; cannot fetch it whole: gone");
    }

    @Test
    void warnsThatALargeCopyMayBeDropped() {
        Viewer viewer = viewer(Value.stored(0, text("x".repeat(Osc52.LARGE_COPY + 1))));
        assertThat(viewer.handleKey(key('y'))).get().isInstanceOf(Event.Copy.class);
        assertThat(render(viewer, 120, 6))
                .contains("Copied 100.0 KiB as text (OSC 52); terminals may drop copies this large.");
    }

    @Test
    void copiesHexAndCloses() {
        Viewer viewer = viewer(Value.stored(0, text("hi")));
        viewer.handleKey(key('h'));
        assertThat(viewer.handleKey(key('y'))).hasValue(new Event.Copy("6869"));
        assertThat(viewer.handleKey(key(KeyCode.ESCAPE))).hasValue(new Event.Close());
        assertThat(viewer.handleKey(key('q'))).hasValue(new Event.Close());
        assertThat(viewer.handleKey(key('v'))).hasValue(new Event.Close());
    }

    @Test
    void prettyPrintsJsonAsItIsWritten() {
        String json = " {\"b\":1,\"a\":[1, 2.50, true,null],\"e\":{ },\"s\":\"x\\\"y,}\",\"n\":[]} ";
        assertThat(Viewer.jsonError(json)).isEmpty();
        List<List<Segment>> lines = Viewer.jsonLines(json);
        assertThat(plain(lines))
                .containsExactly(
                        "{",
                        "  \"b\": 1,",
                        "  \"a\": [",
                        "    1,",
                        "    2.50,",
                        "    true,",
                        "    null",
                        "  ],",
                        "  \"e\": {},",
                        "  \"s\": \"x\\\"y,}\",",
                        "  \"n\": []",
                        "}");
        assertThat(lines.get(1).get(1)).isEqualTo(segment(Palette.current().jsonKey(), "\"b\""));
        assertThat(lines.get(1).get(3)).isEqualTo(segment(Palette.current().jsonNumber(), "1"));
        assertThat(lines.get(5).get(1)).isEqualTo(segment(Palette.current().jsonLiteral(), "true"));
        assertThat(lines.get(9).get(3)).isEqualTo(segment(Palette.current().jsonString(), "\"x\\\"y,}\""));
        // Strings in arrays are values, keys of nested objects are keys.
        lines = Viewer.jsonLines("[{\"k\":\"v\"},\"w\"]");
        assertThat(lines.get(2).get(1)).isEqualTo(segment(Palette.current().jsonKey(), "\"k\""));
        assertThat(lines.get(2).get(3)).isEqualTo(segment(Palette.current().jsonString(), "\"v\""));
        assertThat(lines.get(4).get(1)).isEqualTo(segment(Palette.current().jsonString(), "\"w\""));

        assertThat(plain(Viewer.jsonLines("-1.5e3"))).containsExactly("-1.5e3");
        assertThat(Viewer.jsonError("{\"a\":1,}")).get().asString().contains("line 1 column 8");
        assertThat(Viewer.jsonError("{} x")).isPresent();
        assertThat(Viewer.jsonError("{} {}")).isPresent();
        assertThat(Viewer.jsonError("")).isPresent();
    }

    @Test
    void opensJsonPrettyPrintedAndSaysWhenAValueIsNotJson() {
        Viewer viewer =
                viewer(Value.stored(0, text("{\"id\":7,\"tags\":[\"a\",\"b\"]}")), Value.stored(1, text("plain")));
        assertThat(viewer.mode()).isEqualTo(Mode.JSON);
        assertThat(render(viewer, 60, 10)).contains("│  \"id\": 7,", "│    \"a\",", "json · rows 1–7 of 7");
        assertThat(viewer.handleKey(key('y')))
                .hasValue(new Event.Copy("{\n  \"id\": 7,\n  \"tags\": [\n    \"a\",\n    \"b\"\n  ]\n}"));

        viewer.handleKey(key(KeyCode.RIGHT));
        assertThat(render(viewer, 100, 10)).contains("│plain", "Not JSON: Unrecognized token 'plain'");
        // Copies it as it is shown: as text.
        assertThat(viewer.handleKey(key('y'))).hasValue(new Event.Copy("plain"));

        viewer.handleKey(key(KeyCode.LEFT));
        viewer.handleKey(key('J'));
        assertThat(viewer.mode()).isEqualTo(Mode.TEXT);
        assertThat(render(viewer, 60, 10)).contains("│{\"id\":7,\"tags\":[\"a\",\"b\"]}");
        viewer.handleKey(key('J'));
        assertThat(viewer.mode()).isEqualTo(Mode.JSON);
        viewer.handleKey(key('t'));
        assertThat(viewer.mode()).isEqualTo(Mode.TEXT);
    }

    @Test
    void opensACutJsonValueAsJson() {
        Viewer source =
                new Viewer(1, "_source", List.of(Value.stored(0, cut("{\"id\":7,\"bo", 70_000))), Delimiters.DEFAULT);
        assertThat(source.mode()).isEqualTo(Mode.JSON);
        // Text that only starts like JSON stays text.
        assertThat(viewer(Value.stored(0, text("[1] and more"))).mode()).isEqualTo(Mode.TEXT);
    }

    private static Split split(String items, String keyValue) {
        return new Split(items, Optional.ofNullable(keyValue));
    }

    @Test
    void numbersDelimitedItems() {
        Formatted formatted = Viewer.delimited("a,b\tc,,d", split(",", null));
        assertThat(formatted.detail()).isEqualTo("4 items");
        assertThat(plain(formatted.lines())).containsExactly("1  a", "2  b\\tc", "3  ", "4  d");

        String items = IntStream.rangeClosed(1, 10).mapToObj(Integer::toString).collect(Collectors.joining("\n"));
        formatted = Viewer.delimited(items, split("\n", null));
        assertThat(plain(formatted.lines()).getFirst()).isEqualTo(" 1  1");
        assertThat(plain(formatted.lines()).get(9)).isEqualTo("10  10");
        assertThat(Viewer.delimited("x", split(",", null)).detail()).isEqualTo("1 item");
    }

    @Test
    void alignsKeysOfDelimitedItems() {
        String longKey = "k".repeat(Viewer.MAX_KEY_WIDTH + 1);
        String text = "id=7;name=a=b;junk;" + longKey + "=x;tab\t=y";
        Formatted formatted = Viewer.delimited(text, split(";", "="));
        assertThat(plain(formatted.lines()))
                .containsExactly("id    = 7", "name  = a=b", "junk", longKey + " = x", "tab\\t = y");
        assertThat(formatted.lines().getFirst().get(0))
                .isEqualTo(segment(Palette.current().accent(), "id"));
        assertThat(formatted.lines().getFirst().get(2)).isEqualTo(segment(Viewer.dim(), " = "));
    }

    @Test
    void resolvesEscapesInDelimiters() {
        assertThat(Viewer.unescape("a\\t\\n\\r\\0\\\\b")).isEqualTo("a\t\n\r\0\\b");
        assertThat(Viewer.unescape("\\x1f|\\u{1E}\\u{e9}x")).isEqualTo("\u001f|\u001eéx");
        for (String bad : List.of("\\x8", "\\x80", "\\u{110000}", "\\u1f", "\\q", "\\")) {
            assertThatThrownBy(() -> Viewer.unescape(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(new Delimiters("\\x01", "").split()).isEqualTo(split("\u0001", null));
        assertThatThrownBy(() -> new Delimiters("", "=").split()).hasMessage("Items: enter a delimiter.");
    }

    @Test
    void asksForTheDelimitersAndSplitsAtThem() {
        Viewer viewer = viewer(Value.stored(0, text("a=1|b=22")));
        assertThat(viewer.handleKey(key('d'))).isEmpty();
        assertThat(render(viewer, 80, 12)).contains("Delimited", "Items      ,▏");
        assertThat(viewer.promptKeys())
                .hasValueSatisfying(keys -> assertThat(keys).contains("Tab/↑↓ other delimiter   ⏎ apply   Esc cancel"));

        // The form takes the keys, q included.
        viewer.handleKey(key(KeyCode.BACKSPACE));
        assertThat(viewer.handleKey(key(KeyCode.ENTER))).isEmpty();
        assertThat(render(viewer, 80, 12)).contains("Items: enter a delimiter.");
        viewer.handleKey(key('|'));
        viewer.handleKey(key(KeyCode.TAB));
        viewer.handleKey(key('='));
        assertThat(render(viewer, 80, 12)).contains("Key/value  =▏");
        assertThat(viewer.mode()).isEqualTo(Mode.TEXT);
        viewer.handleKey(key(KeyCode.ENTER));
        assertThat(viewer.mode()).isEqualTo(Mode.DELIMITED);
        assertThat(render(viewer, 80, 12)).contains("│a = 1", "│b = 22", "delimited · 2 items · rows 1–2 of 2");
        assertThat(viewer.delimiters()).isEqualTo(new Delimiters("|", "="));
        assertThat(viewer.handleKey(key('y'))).hasValue(new Event.Copy("a = 1\nb = 22"));

        // Esc leaves the form, not the viewer, and keeps the delimiters.
        viewer.handleKey(key('d'));
        viewer.handleKey(key('x'));
        assertThat(viewer.handleKey(key(KeyCode.ESCAPE))).isEmpty();
        assertThat(viewer.delimiters().items()).isEqualTo("|");
        assertThat(viewer.mode()).isEqualTo(Mode.DELIMITED);
        assertThat(viewer.handleKey(key(KeyCode.ESCAPE))).hasValue(new Event.Close());
    }

    @Test
    void formatsSizes() {
        assertThat(Viewer.cutSize()).isEqualTo("64.0 KiB");
    }

    @Test
    void docValueTypesNameTheValue() {
        Value value = Value.docValue(Overview.docValues(DocValuesType.BINARY), new BytesValue("ff", true));
        assertThat(value.kind()).isEqualTo("binary doc value");
        assertThat(value.content().kind()).isEqualTo(Viewer.Content.Kind.BYTES);
        assertThat(value.content().bytes()).containsExactly((byte) 0xff);
    }
}
