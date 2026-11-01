package io.github.mfuerstenau.leia.tui.widgets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.theme.Themes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PanelsTest {

    @AfterEach
    void resetTheme() {
        Palette.use(Themes.bundled().dark());
    }

    @Test
    void aBoxDrawsItsBorderAndTitleInTheThemesText() {
        // TamboUI keeps only a cell's background under a border: without the box's own style, nord's
        // borders and titles took the terminal's foreground, which hardly shows on a light terminal.
        Palette.use(Themes.bundled().find("nord").orElseThrow());
        Rect area = new Rect(0, 0, 10, 3);
        Buffer buffer = Buffer.empty(area);
        buffer.setStyle(area, Palette.current().base());

        Panels.box(" t ").render(area, buffer);

        Color text = Palette.current().base().fg().orElseThrow();
        assertThat(buffer.get(2, 0).symbol()).isEqualTo("t");
        assertThat(List.of(buffer.get(0, 0), buffer.get(2, 0), buffer.get(0, 1), buffer.get(9, 2)))
                .allSatisfy(cell -> assertThat(cell.style().fg()).contains(text));
    }

    @Test
    void textOutOfTheWayTakesTheThemesMutedStyleNotFaintText() {
        // A terminal may draw faint text (SGR 2) like normal text, as Windows Terminal does under One
        // Half Light, so the hints take the theme's muted style.
        for (String id : Themes.bundled().ids()) {
            Palette.use(Themes.bundled().find(id).orElseThrow());

            assertThat(Form.muted()).isEqualTo(Palette.current().muted());
        }
        Palette.use(Themes.bundled().find("light").orElseThrow());
        assertThat(Form.muted().fg()).contains(Color.indexed(245));
    }

    @Test
    void showsTheColumnsThatFitWhole() {
        int[] widths = {4, 3, 5};

        assertThat(Panels.shownColumns(widths, 2, 4 + 2 + 3 + 2 + 5, 1)).isEqualTo(3);
        assertThat(Panels.shownColumns(widths, 2, 4 + 2 + 3 + 2 + 4, 1)).isEqualTo(2);
        assertThat(Panels.shownColumns(widths, 2, 4 + 2 + 2, 1)).isEqualTo(1);
        // The kept columns are shown even when they don't fit.
        assertThat(Panels.shownColumns(widths, 2, 2, 1)).isEqualTo(1);
        assertThat(Panels.shownColumns(widths, 2, 2, 2)).isEqualTo(2);
    }

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
