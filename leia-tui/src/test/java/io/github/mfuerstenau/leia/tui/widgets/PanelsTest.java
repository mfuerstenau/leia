package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PanelsTest {

    @Test
    void wrapsWithTheStylesOfTheSpans() {
        Style red = Style.EMPTY.red();
        List<Line> wrapped =
                Panels.wrap(List.of(Line.from(Span.raw("aaaa "), Span.styled("bbbbb cc", red), Span.raw(" dd"))), 10);

        assertThat(wrapped).extracting(Line::rawContent).containsExactly("aaaa bbbbb", "cc dd");
        assertThat(wrapped.get(0).spans()).extracting(Span::style).containsExactly(Style.EMPTY, red);
        assertThat(wrapped.get(1).spans()).extracting(Span::style).containsExactly(red, Style.EMPTY);
    }

    @Test
    void drawsALineForEachWrappedLine() {
        // A word that ends in the last column stays on its line, which TamboUI's word wrap breaks early.
        List<Line> wrapped = Panels.wrap(List.of(Line.from("aaaa bbbbb cc dd"), Line.empty(), Line.from("x")), 10);

        assertThat(rows(wrapped, 10, 5)).containsExactly("aaaa bbbbb", "cc dd", "", "x", "");
    }

    private static List<String> rows(List<Line> lines, int width, int height) {
        Rect area = new Rect(0, 0, width, height);
        Buffer buffer = Buffer.empty(area);
        Paragraph.from(Text.from(lines)).render(area, buffer);
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                row.append(buffer.get(x, y).symbol());
            }
            rows.add(row.toString().stripTrailing());
        }
        return rows;
    }
}
