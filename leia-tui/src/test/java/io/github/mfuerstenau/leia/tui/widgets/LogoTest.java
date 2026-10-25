package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogoTest {

    @Test
    void theIconIsTrimmedAtTheRight() {
        List<Line> lines = Logo.lines();
        assertThat(Logo.HEIGHT).isEqualTo(30);
        assertThat(Logo.WIDTH).isEqualTo(37);
        assertThat(lines).hasSize(Logo.HEIGHT);
        assertThat(lines).allMatch(line -> line.spans().size() == Logo.WIDTH && line.width() == Logo.WIDTH);
    }

    @Test
    void everyCharacterHasItsColour() {
        List<Line> lines = Logo.lines();
        assertThat(lines.get(13).spans().get(3)).isEqualTo(Span.styled("X", Style.EMPTY.fg(Color.rgb(171, 136, 101))));
        // A space leaves the background.
        assertThat(lines.getFirst().spans().getFirst()).isEqualTo(Span.raw(" "));
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
