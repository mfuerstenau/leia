package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.text.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyLegendTest {

    private static List<String> lines(String keys, int width) {
        return KeyLegend.lines(keys, width).stream().map(Line::rawContent).toList();
    }

    @Test
    void aLegendThatFitsStaysOnOneLine() {
        assertThat(lines(" q quit   o open   X jars", 40)).containsExactly(" q quit   o open   X jars");
        assertThat(lines(" q quit   o open   X jars", 25)).containsExactly(" q quit   o open   X jars");
    }

    @Test
    void breaksBetweenEntriesOnly() {
        assertThat(lines(" q quit   o open   X jars", 24)).containsExactly(" q quit   o open", " X jars");
        assertThat(lines(" q quit   ⏎ expand / points / open doc   ↑↓", 20))
                .containsExactly(" q quit", " ⏎ expand / points / open doc", " ↑↓");
    }

    @Test
    void everyLineStartsWithASpace() {
        assertThat(lines("/ query   m mode   t count", 12)).containsExactly(" / query", " m mode", " t count");
    }

    @Test
    void anEmptyLegendIsOneEmptyLine() {
        assertThat(lines("", 10)).containsExactly("");
        assertThat(lines(" ", 10)).containsExactly("");
    }
}
