package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeysTest {

    @Test
    void theGroupsComeInTheirOrderWhateverTheOrderOfTheKeys() {
        Keys keys = Keys.builder()
                .key(Keys.Group.DO, "E", "export")
                .key(Keys.Group.MOVE, "←→", "doc")
                .key(Keys.Group.SORT, "s/S", "sort")
                .build();

        assertThat(keys.sections().stream().map(Keys.Section::group))
                .containsExactly(Keys.Group.MOVE, Keys.Group.SORT, Keys.Group.DO);
    }

    @Test
    void plusKeepsTheOrderAndJoinsAGroupThatMeets() {
        Keys stop = Keys.builder().key(Keys.Group.DO, "Esc", "stop").build();
        Keys screen = Keys.builder()
                .key(Keys.Group.MOVE, "←→", "doc")
                .key(Keys.Group.DO, "E", "export")
                .build();
        Keys tail = Keys.builder()
                .key(Keys.Group.DO, "x", "more")
                .key(Keys.Group.APP, "?", "keys")
                .build();

        Keys all = stop.plus(screen).plus(tail);

        assertThat(all.text()).isEqualTo(" Esc stop  │  ←→ doc  │  E export   x more  │  ? keys");
        assertThat(all.merged().text()).isEqualTo(" ←→ doc  │  Esc stop   E export   x more  │  ? keys");
    }

    @Test
    void theFooterLeavesOutTheOtherKeysAndTheGroupsLeftEmpty() {
        Keys keys = Keys.builder()
                .key(Keys.Group.MOVE, "←→", "doc")
                .more(Keys.Group.SHOW, "n", "number format")
                .keyIf(false, Keys.Group.DO, "E", "export")
                .moreIf(true, Keys.Group.DO, "I", "indexed terms")
                .build();

        assertThat(keys.text()).isEqualTo(" ←→ doc");
        assertThat(keys.footer()).hasSize(1);
        assertThat(keys.shows("←→")).isTrue();
        assertThat(keys.shows("n")).isFalse();
        assertThat(keys.sections().stream()
                        .flatMap(section -> section.keys().stream())
                        .map(Keys.Key::text))
                .isEqualTo(List.of("←→ doc", "n number format", "I indexed terms"));
    }
}
