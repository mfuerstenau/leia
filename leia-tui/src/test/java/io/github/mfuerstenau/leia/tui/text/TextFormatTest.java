package io.github.mfuerstenau.leia.tui.text;

import static io.github.mfuerstenau.leia.tui.text.TextFormat.bytesText;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.escapeControl;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.wrap;
import static io.github.mfuerstenau.leia.tui.text.TextFormat.wrapHanging;
import static org.assertj.core.api.Assertions.assertThat;

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
    void wrapsTheFirstLineNarrower() {
        assertThat(wrapHanging("one two three four", 3, 9, 5)).containsExactly("one", "two three", "four");
        assertThat(wrapHanging("one two three four", 3, 9, 2)).containsExactly("one", "two thre…");
    }
}
