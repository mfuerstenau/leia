package io.github.mfuerstenau.leia.tui.prompt;

import static io.github.mfuerstenau.leia.tui.TestScreens.alt;
import static io.github.mfuerstenau.leia.tui.TestScreens.altGr;
import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.tui.prompt.LineInput.Edit;
import io.github.mfuerstenau.leia.tui.prompt.LineInput.Visible;
import org.junit.jupiter.api.Test;

class LineInputTest {

    @Test
    void reportsWhetherTheTextChanged() {
        LineInput input = new LineInput("ab");

        assertThat(input.handleKey(key(KeyCode.LEFT), LineInput::wordStart)).isEqualTo(Edit.UNCHANGED);
        assertThat(input.handleKey(key('é'), LineInput::wordStart)).isEqualTo(Edit.CHANGED);
        assertThat(input.text()).isEqualTo("aéb");
        assertThat(input.handleKey(key(KeyCode.DELETE), LineInput::wordStart)).isEqualTo(Edit.CHANGED);
        assertThat(input.handleKey(key(KeyCode.DELETE), LineInput::wordStart)).isEqualTo(Edit.UNCHANGED);
        assertThat(input.handleKey(key(KeyCode.ENTER), LineInput::wordStart)).isEqualTo(Edit.UNCHANGED);
        assertThat(input.text()).isEqualTo("aé");
    }

    @Test
    void altGrTypesButCtrlOrAltAloneDoNot() {
        LineInput input = new LineInput("C:");

        input.handleKey(altGr('\\'), LineInput::wordStart);
        input.handleKey(alt('x'), LineInput::wordStart);
        input.handleKey(ctrl('x'), LineInput::wordStart);
        input.handleKey(altGr('@'), LineInput::wordStart);

        assertThat(input.text()).isEqualTo("C:\\@");
    }

    @Test
    void ctrlWDeletesTheWordBeforeTheCursor() {
        LineInput input = new LineInput("title:fox AND\u00a0body:qu*  ");

        input.handleKey(ctrl('w'), LineInput::wordStart);
        assertThat(input.text()).isEqualTo("title:fox AND\u00a0");
        input.handleKey(ctrl('w'), LineInput::wordStart);
        assertThat(input.text()).isEqualTo("title:fox ");
        input.handleKey(ctrl('a'), LineInput::wordStart);
        input.handleKey(ctrl('k'), LineInput::wordStart);
        assertThat(input.text()).isEmpty();
    }

    @Test
    void visibleTextKeepsTheCursorInView() {
        LineInput input = new LineInput("abcdefgh");
        assertThat(input.visible(4)).isEqualTo(new Visible("fgh", 3));
        assertThat(input.visible(20)).isEqualTo(new Visible("abcdefgh", 8));

        input.handleKey(key(KeyCode.HOME), LineInput::wordStart);
        assertThat(input.visible(4)).isEqualTo(new Visible("abcdefgh", 0));

        // Wide characters take two cells each.
        assertThat(new LineInput("日本語").visible(4)).isEqualTo(new Visible("語", 2));
    }

    @Test
    void movesOverCharactersOutsideTheBasicPlane() {
        LineInput input = new LineInput("a🔍b");

        input.handleKey(key(KeyCode.LEFT), LineInput::wordStart);
        input.handleKey(key(KeyCode.BACKSPACE), LineInput::wordStart);

        assertThat(input.text()).isEqualTo("ab");
    }
}
