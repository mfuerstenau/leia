package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;

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

    /** A half block whose two halves have the colour {@code hex}. */
    private static Style both(String hex) {
        return Style.EMPTY.fg(Color.hex(hex)).bg(Color.hex(hex));
    }

    private static Palette bundled(String id) {
        return Themes.bundled().find(id).orElseThrow().palette();
    }

    @Test
    void drawsTheIconOfThePaletteARowALine() {
        List<Line> lines = Logo.lines(Palette.current());

        assertThat(lines).hasSize(30);
        assertThat(lines).allMatch(line -> line.spans().size() == 38 && line.width() == 38);
        // dark and light draw the same gazelle.
        assertThat(Logo.lines(bundled("light"))).isEqualTo(lines);
    }

    @Test
    void everyCellHasTheColoursOfItsIcon() {
        Palette dark = bundled("dark");
        Palette nord = bundled("nord");

        // Row 14 of the gazelle starts with half blocks whose halves have one colour.
        assertThat(Logo.lines(dark).get(13).spans().get(1)).isEqualTo(Span.styled("▄", both("#9E6038")));
        assertThat(Logo.lines(dark).get(13).spans().get(2)).isEqualTo(Span.styled("▄", both("#523D38")));
        assertThat(Logo.lines(nord).get(13).spans().get(2)).isEqualTo(Span.styled("▄", both("#B48EAD")));
        // mono's gazelle is of its own, in the terminal's foreground.
        assertThat(Logo.lines(bundled("mono")).get(13).spans().get(2)).isEqualTo(Span.styled("█", Style.EMPTY));
        // A space with no colours leaves the screen's background.
        assertThat(Logo.lines(dark).getFirst().spans().getFirst()).isEqualTo(Span.styled(" ", Style.EMPTY));
    }

    @Test
    void fitsBesideWithItsTopCutOffDownToTheMinimum() {
        List<Line> text = List.of(Line.from("leia 1"));
        int width = Palette.current().logo().width() + Logo.GAP + Logo.MIN_TEXT_WIDTH;

        assertThat(Logo.fitsBeside(new Rect(0, 0, width, Logo.MIN_HEIGHT), text))
                .isTrue();
        assertThat(Logo.fitsBeside(new Rect(0, 0, width, Logo.MIN_HEIGHT - 1), text))
                .isFalse();
        assertThat(Logo.fitsBeside(
                        new Rect(0, 0, width - 1, Palette.current().logo().height()), text))
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
