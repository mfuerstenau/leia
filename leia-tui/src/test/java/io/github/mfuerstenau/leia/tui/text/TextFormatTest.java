package io.github.mfuerstenau.leia.tui.text;

import static io.github.mfuerstenau.leia.tui.text.TextFormat.bytesText;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.count;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.escapeControl;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.firstChars;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.size;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.utcTime;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.wrap;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.wrapHanging;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.wrapRanges;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextFormatTest {

    @Test
    void escapesControlCharacters() {
        assertThat(escapeControl("a\tb\nc\u0001")).isEqualTo("a\\tb\\nc\\u{1}");
        assertThat(escapeControl("grüße")).isEqualTo("grüße");
    }

    @Test
    void showsBinaryAsHex() {
        assertThat(bytesText("ff00", true)).isEqualTo("0xff00");
        assertThat(bytesText("ff\n", false)).isEqualTo("ff\\n");
    }

    @Test
    void wrapsAtSpaces() {
        assertThat(wrap("one two three", 7, 5)).containsExactly("one two", "three");
        assertThat(wrap("one two three", 13, 5)).containsExactly("one two three");
        assertThat(wrap("", 10, 5)).containsExactly("");
    }

    @Test
    void breaksWordsWiderThanTheLine() {
        assertThat(wrap("a /very/long/path b", 6, 5)).containsExactly("a", "/very/", "long/p", "ath b");
        // By columns: each of these characters takes two.
        assertThat(wrap("索引索引", 4, 5)).containsExactly("索引", "索引");
    }

    @Test
    void cutsAfterTheLastLine() {
        assertThat(wrap("one two three four", 7, 2)).containsExactly("one two", "three…");
        assertThat(wrap("one two three four", 5, 2)).containsExactly("one", "two…");
        assertThat(wrap("abcdefghij", 5, 1)).containsExactly("abcd…");
    }

    @Test
    void wrapsLikeRatatui() {
        // A word that ends in the last column stays on the line.
        assertThat(lines("aaaa bbbbb cc dd", 10)).containsExactly("aaaa bbbbb", "cc dd");
        assertThat(lines("aaaa bbbb cc", 10)).containsExactly("aaaa bbbb", "cc");
        assertThat(lines("", 10)).containsExactly("");
        assertThat(lines("   ", 10)).containsExactly("   ");
    }

    @Test
    void keepsTheIndentationOfTheFirstLineOnly() {
        assertThat(lines("  string  one two", 10)).containsExactly("  string", "one two");
        assertThat(lines("  ab  cd", 10)).containsExactly("  ab  cd");
    }

    @Test
    void breaksWordsWiderThanTheLineInRanges() {
        assertThat(lines("a /very/long/path b", 6)).containsExactly("a", "/very/", "long/p", "ath b");
        assertThat(lines("File exists: /tmp/x/java-74.json. again", 12))
                .containsExactly("File exists:", "/tmp/x/java-", "74.json.", "again");
        assertThat(lines("索引索引 x", 4)).containsExactly("索引", "索引", "x");
    }

    private static List<String> lines(String text, int width) {
        return wrapRanges(text, width).stream()
                .map(range -> text.substring(range.start(), range.end()))
                .toList();
    }

    @Test
    void wrapsTheFirstLineNarrower() {
        assertThat(wrapHanging("one two three four", 3, 9, 5)).containsExactly("one", "two three", "four");
        assertThat(wrapHanging("one two three four", 3, 9, 2)).containsExactly("one", "two thre…");
    }

    @Test
    void cutsAfterTheFirstCharacters() {
        assertThat(firstChars("grüße", 3)).isEqualTo("grü…");
        assertThat(firstChars("grüße", 5)).isEqualTo("grüße");
    }

    @Test
    void countsInTheSingularOrPlural() {
        assertThat(count(1, "doc")).isEqualTo("1 doc");
        assertThat(count(0, "doc")).isEqualTo("0 docs");
        assertThat(count(2, "segment")).isEqualTo("2 segments");
    }

    @Test
    void formatsSizes() {
        assertThat(size(0)).isEqualTo("0 B");
        assertThat(size(1023)).isEqualTo("1023 B");
        assertThat(size(1536)).isEqualTo("1.5 KiB");
        assertThat(size(5 * 1024 * 1024)).isEqualTo("5.0 MiB");
        assertThat(size(2L * 1024 * 1024 * 1024)).isEqualTo("2.0 GiB");
    }

    @Test
    void formatsUtcTimes() {
        assertThat(utcTime(0)).isEqualTo("1970-01-01 00:00:00 UTC");
        assertThat(utcTime(1_000_000_000_000L)).isEqualTo("2001-09-09 01:46:40 UTC");
        assertThat(utcTime(951_782_400_000L)).isEqualTo("2000-02-29 00:00:00 UTC");
        assertThat(utcTime(-1000)).isEqualTo("1969-12-31 23:59:59 UTC");
    }
}
