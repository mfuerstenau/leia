package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.theme.Themes;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogoTest {

    private static Style fg(String hex) {
        return Style.EMPTY.fg(Color.hex(hex));
    }

    @Test
    void theLogoIsTrimmedAtTheRight() {
        List<Line> lines = Logo.lines(Palette.current());
        assertThat(Logo.HEIGHT).isEqualTo(30);
        assertThat(Logo.WIDTH).isEqualTo(37);
        assertThat(lines).hasSize(Logo.HEIGHT);
        assertThat(lines).allMatch(line -> line.spans().size() == Logo.WIDTH && line.width() == Logo.WIDTH);
    }

    @Test
    void everyCellHasItsSlotsStyleInThePalette() {
        Palette dark = Themes.bundled().dark().palette();
        Palette nord = Themes.bundled().find("nord").orElseThrow().palette();

        // Row 14 starts " =+$$=+", with the slots "0324432".
        assertThat(Logo.lines(dark).get(13).spans().get(1)).isEqualTo(Span.styled("=", fg("#9E6038")));
        assertThat(Logo.lines(dark).get(13).spans().get(2)).isEqualTo(Span.styled("+", fg("#563F38")));
        assertThat(Logo.lines(nord).get(13).spans().get(2)).isEqualTo(Span.styled("+", fg("#B48EAD")));
        // Slot 0 is a space in logo0's style: none leaves the screen's background, a style shows.
        assertThat(Logo.lines(dark).getFirst().spans().getFirst()).isEqualTo(Span.styled(" ", Style.EMPTY));
        Palette onRed = new Palette(
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY.onRed(),
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY,
                Style.EMPTY);
        assertThat(Logo.lines(onRed).getFirst().spans().getFirst()).isEqualTo(Span.styled(" ", Style.EMPTY.onRed()));
    }

    @Test
    void parseCutsTheColumnsThatAreBlankInEveryRow() {
        Logo.Grid grid = Logo.parse("a  \n b \n\n100\n010\n");

        assertThat(grid.characters()).containsExactly("a ", " b");
        assertThat(grid.slots()).containsExactly("10", "01");
    }

    @Test
    void parseRefusesABrokenFile() {
        assertThat(refusal("a\n1\n")).isEqualTo("leia.logo: no characters before a blank line");
        assertThat(refusal("\n1\n")).isEqualTo("leia.logo: no characters before a blank line");
        assertThat(refusal("a\n\n1\n1\n")).isEqualTo("leia.logo: 1 rows of characters, 2 of slots");
        assertThat(refusal("ab\n\n1\n")).isEqualTo("leia.logo: row 1 has 2 characters and 1 slots");
        assertThat(refusal("a \n\n16\n")).isEqualTo("leia.logo: row 1, column 2: slot is 0 to 5");
        assertThat(refusal("a \n\n1x\n")).isEqualTo("leia.logo: row 1, column 2: slot is 0 to 5");
        assertThat(refusal("a \n\n12\n"))
                .isEqualTo("leia.logo: row 1, column 2: a space is slot 0 and nothing else is");
        assertThat(refusal("ab\n\n01\n"))
                .isEqualTo("leia.logo: row 1, column 1: a space is slot 0 and nothing else is");
        assertThat(refusal("  \n\n00\n")).isEqualTo("leia.logo is empty");
    }

    private static String refusal(String text) {
        return assertThatThrownBy(() -> Logo.parse(text))
                .isInstanceOf(IllegalStateException.class)
                .actual()
                .getMessage();
    }

    @Test
    void fitsBesideWithItsTopCutOffDownToTheMinimum() {
        List<Line> text = List.of(Line.from("leia 1"));
        int width = Logo.WIDTH + Logo.GAP + Logo.MIN_TEXT_WIDTH;

        assertThat(Logo.fitsBeside(new Rect(0, 0, width, Logo.MIN_HEIGHT), text))
                .isTrue();
        assertThat(Logo.fitsBeside(new Rect(0, 0, width, Logo.MIN_HEIGHT - 1), text))
                .isFalse();
        assertThat(Logo.fitsBeside(new Rect(0, 0, width - 1, Logo.HEIGHT), text))
                .isFalse();
        // Not where the wrapped text is taller than the area.
        List<Line> tall = Collections.nCopies(Logo.MIN_HEIGHT + 1, Line.from("x"));
        assertThat(Logo.fitsBeside(new Rect(0, 0, width, Logo.MIN_HEIGHT), tall))
                .isFalse();
    }

    @Test
    void aboutNamesVersionAndBuild() {
        VersionResult version = new VersionResult("leia-server", "1", "abc", "t", new JavaRuntime("25", "v", "/h"));

        assertThat(Logo.aboutLines(version)).extracting(Line::rawContent).containsExactly("leia 1", "build abc", "t");
    }
}
