package io.github.mfuerstenau.leia.tui.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.github.mfuerstenau.leia.protocol.version.JavaRuntime;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogoTest {

    @Test
    void everyPixelHasAKnownCharacter() {
        for (String row : Logo.SPRITE) {
            assertThat(row).hasSize(Logo.WIDTH);
            assertThat(row.chars())
                    .allMatch(c -> c == 'g' || Logo.color((char) c.intValue()).isPresent());
        }
        assertThat((Logo.SPRITE.size() + 1) / 2).isEqualTo(Logo.HEIGHT);
    }

    @Test
    void twoPixelsGoInOneCell() {
        List<Line> lines = Logo.lines();
        assertThat(lines).hasSize(Logo.HEIGHT);
        assertThat(lines).allMatch(line -> line.width() == Logo.WIDTH);

        // The eyes: black above black.
        Span eye = lines.get(2).spans().get(5);
        assertThat(eye.content()).isEqualTo("▀");
        assertThat(eye.style().fg()).hasValue(Color.rgb(0, 0, 0));
        assertThat(eye.style().bg()).hasValue(Color.rgb(0, 0, 0));

        // Top left: transparent above transparent.
        assertThat(lines.getFirst().spans().getFirst()).isEqualTo(Span.raw(" "));
        // The dress above nothing, in the last row, which has only one pixel row.
        Span hem = lines.get(7).spans().get(2);
        assertThat(hem.content()).isEqualTo("▀");
        assertThat(hem.style().fg()).hasValue(Color.rgb(255, 255, 255));
        assertThat(hem.style().bg()).isEmpty();
        // Hair below nothing: the bun's top.
        Span bun = lines.getFirst().spans().get(1);
        assertThat(bun.content()).isEqualTo("▄");
        assertThat(bun.style().fg()).hasValue(Color.rgb(105, 53, 0));
    }

    @Test
    void aboutNamesVersionAndBuild() {
        VersionResult version = new VersionResult("leia-server", "1", "abc", "t", new JavaRuntime("25", "v", "/h"));

        assertThat(Logo.aboutLines(version)).extracting(Line::rawContent).containsExactly("leia 1", "build abc", "t");
    }
}
