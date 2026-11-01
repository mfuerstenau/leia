package io.github.mfuerstenau.leia.tui.widgets;

import static io.github.mfuerstenau.leia.tui.TestScreens.ctrl;
import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoMenuTest {

    private static final GoMenu MENU = new GoMenu(List.of(
            new GoMenu.Target(Jump.TERMS, "terms of title"), new GoMenu.Target(Jump.ANALYSIS, "analyze title")));

    @Test
    void aLetterOfAJumpPicksItListedOrNot() {
        assertThat(MENU.handleKey(key('t'))).isEqualTo(new GoMenu.Picked(Jump.TERMS));
        assertThat(MENU.handleKey(key('a'))).isEqualTo(new GoMenu.Picked(Jump.ANALYSIS));
        // Not listed: the screen says why it can't go there.
        assertThat(MENU.handleKey(key('m'))).isEqualTo(new GoMenu.Picked(Jump.SIMILAR));
    }

    @Test
    void gGoesToTheTopAndAnyOtherKeyCloses() {
        assertThat(MENU.handleKey(key('g'))).isEqualTo(new GoMenu.Top());
        assertThat(MENU.handleKey(key('x'))).isEqualTo(new GoMenu.Close());
        assertThat(MENU.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new GoMenu.Close());
        assertThat(MENU.handleKey(key(KeyCode.DOWN))).isEqualTo(new GoMenu.Close());
        assertThat(MENU.handleKey(ctrl('t'))).isEqualTo(new GoMenu.Close());
    }

    @Test
    void listsTheTargetsAndTheTop() {
        assertThat(MENU.lines().stream().map(Line::rawContent))
                .containsExactly(" t  terms of title ", " a  analyze title ", " g  top ");
    }

    @Test
    void showsAtTheBottomLeft() {
        String screen = TestScreens.render(frame -> MENU.render(frame, frame.area()), 40, 8);

        List<String> rows = screen.lines().toList();
        assertThat(rows.get(3)).startsWith("┌ Go to ");
        assertThat(rows.get(4)).startsWith("│ t  terms of title");
        assertThat(rows.get(7)).startsWith("└");
    }

    @Test
    void theLettersAreTheSameEverywhere() {
        assertThat(Jump.of('t')).hasValue(Jump.TERMS);
        assertThat(Jump.of('s')).hasValue(Jump.SEARCH);
        assertThat(Jump.of('a')).hasValue(Jump.ANALYSIS);
        assertThat(Jump.of('c')).hasValue(Jump.COMMIT);
        assertThat(Jump.of('d')).hasValue(Jump.DOCUMENT);
        assertThat(Jump.of('m')).hasValue(Jump.SIMILAR);
        assertThat(Jump.of('n')).hasValue(Jump.NEAREST);
        assertThat(Jump.of('x')).isEmpty();
    }
}
