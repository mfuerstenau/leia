package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Modifier;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.github.mfuerstenau.leia.tui.theme.Palette;
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

    private static final Keys KEYS = Keys.builder()
            .key(Keys.Group.MOVE, "←→", "doc")
            .key(Keys.Group.MOVE, "i", "go to doc")
            .more(Keys.Group.MOVE, "P", "pin segment")
            .key(Keys.Group.SORT, "s/S", "sort")
            .key(Keys.Group.APP, "?", "keys")
            .build();

    private static List<String> lines(Keys keys, int width) {
        return KeyLegend.lines(keys, width).stream().map(Line::rawContent).toList();
    }

    @Test
    void groupsAreSeparatedByABarAndOnlyTheFooterKeysShow() {
        assertThat(lines(KEYS, 80)).containsExactly(" ←→ doc   i go to doc  │  s/S sort  │  ? keys");
        assertThat(KEYS.text()).isEqualTo(" ←→ doc   i go to doc  │  s/S sort  │  ? keys");
    }

    @Test
    void theKeysAreBoldAndTheBarIsMuted() {
        Line line = KeyLegend.lines(KEYS, 80).getFirst();
        Span key = line.spans().stream()
                .filter(span -> span.content().equals("s/S"))
                .findFirst()
                .orElseThrow();
        assertThat(key.style().addModifiers()).contains(Modifier.BOLD);
        Span bar = line.spans().stream()
                .filter(span -> span.content().equals("│"))
                .findFirst()
                .orElseThrow();
        assertThat(bar.style()).isEqualTo(Palette.current().muted());
        assertThat(line.spans().stream().filter(span -> span.content().equals(" doc")))
                .allSatisfy(span -> assertThat(span.style().addModifiers()).doesNotContain(Modifier.BOLD));
    }

    @Test
    void aLineThatBreaksAtAGroupStartsWithoutTheBar() {
        assertThat(lines(KEYS, 25)).containsExactly(" ←→ doc   i go to doc", " s/S sort  │  ? keys");
        assertThat(lines(KEYS, 12)).containsExactly(" ←→ doc", " i go to doc", " s/S sort", " ? keys");
    }

    @Test
    void noKeysAreOneEmptyLine() {
        assertThat(lines(Keys.NONE, 10)).containsExactly("");
    }
}
