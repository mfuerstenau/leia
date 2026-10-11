package io.github.mfuerstenau.leia.tui.widgets;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static io.github.mfuerstenau.leia.tui.widgets.Selection.moved;
import static io.github.mfuerstenau.leia.tui.widgets.Selection.numberWidth;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class SelectionTest {

    @Test
    void movesWithinTheRows() {
        assertThat(moved(0, 5, key(KeyCode.DOWN))).hasValue(1);
        assertThat(moved(4, 5, key('j'))).hasValue(4);
        assertThat(moved(0, 5, key(KeyCode.UP))).hasValue(0);
        assertThat(moved(2, 30, key(KeyCode.PAGE_DOWN))).hasValue(12);
        assertThat(moved(2, 30, key(KeyCode.PAGE_UP))).hasValue(0);
        assertThat(moved(7, 30, key('g'))).hasValue(0);
        assertThat(moved(7, 30, key(KeyCode.END))).hasValue(29);
        assertThat(moved(0, 0, key('G'))).hasValue(0);
    }

    @Test
    void otherKeysDontMove() {
        assertThat(moved(0, 5, key('x'))).isEmpty();
        assertThat(moved(0, 5, key(KeyCode.ENTER))).isEmpty();
    }

    @Test
    void numberColumnsAreAtLeastFourWide() {
        assertThat(numberWidth(LongStream.of(1, 22))).isEqualTo(4);
        assertThat(numberWidth(LongStream.of(123456))).isEqualTo(6);
        assertThat(numberWidth(LongStream.empty())).isEqualTo(4);
    }
}
