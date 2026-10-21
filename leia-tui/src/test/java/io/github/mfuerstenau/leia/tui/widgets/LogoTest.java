package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
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

        // Top left: transparent above transparent.
        assertThat(lines.getFirst().spans().getFirst()).isEqualTo(Span.raw(" "));
        // Two colours in one cell.
        Span both = lines.getFirst().spans().get(4);
        assertThat(both.content()).isEqualTo("▀");
        assertThat(both.style().fg()).hasValue(Color.rgb(0xab, 0x9f, 0x90));
        assertThat(both.style().bg()).hasValue(Color.rgb(0x3c, 0x3c, 0x3c));
        // Opaque below transparent.
        Span below = lines.get(1).spans().get(1);
        assertThat(below.content()).isEqualTo("▄");
        assertThat(below.style().fg()).hasValue(Color.rgb(0x3c, 0x3c, 0x3c));
        // Opaque above transparent.
        Span above = lines.get(4).spans().getFirst();
        assertThat(above.content()).isEqualTo("▀");
        assertThat(above.style().fg()).hasValue(Color.rgb(0x3c, 0x3c, 0x3c));
        assertThat(above.style().bg()).isEmpty();
    }

    @Test
    void aboutNamesVersionAndBuild() {
        VersionResult version = new VersionResult("leia-server", "1", "abc", "t", new JavaRuntime("25", "v", "/h"));

        assertThat(Logo.aboutLines(version))
                .extracting(Line::rawContent)
                .containsExactly("leia 1", "Luke Evolved Index Analysis", "build abc", "t");

        StringBuilder bold = new StringBuilder();
        for (Span span : Logo.aboutLines(version).get(1).spans()) {
            if (span.style().addModifiers().contains(Modifier.BOLD)) {
                bold.append(span.content());
            }
        }
        assertThat(bold).hasToString("LEIA");
    }
}
