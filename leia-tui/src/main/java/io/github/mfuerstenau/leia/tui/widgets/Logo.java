package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * leia's logo: the pixel art of {@code docs/leia2.png}, drawn with half blocks so that one terminal
 * cell holds two pixels, one above the other, which keeps the pixels about square. Next to it go
 * leia's name, version and build.
 */
public final class Logo {

    /**
     * The pixels, one character each: {@code .} black, {@code #} white, {@code W} skin, {@code s}
     * hair, {@code H} grey, {@code g} transparent.
     */
    static final List<String> SPRITE = List.of(
            "ggggssssggggggg",
            "gsgssssssgsgggg",
            "ssssssssssssggg",
            "sssWWWWWWsssggg",
            "sssWW.W.Wsssggg",
            "sssWW.W.Wsssggg",
            "gsgWWWWWWgsgggg",
            "ggggWWWWggggggg",
            "ggg######gggHHH",
            "gg#########WHgg",
            "gg#########WWgg",
            "gg#WWHHHHgg#ggg",
            "gggWW####gggggg",
            "ggg#######ggggg",
            "gg########ggggg");

    /** Columns the logo takes. */
    public static final int WIDTH = 15;

    /** Rows the logo takes: two pixel rows each. */
    public static final int HEIGHT = 8;

    /** Columns between the logo and the text next to it. */
    static final int GAP = 2;

    private Logo() {}

    /** The colour of a pixel; empty for a transparent one. */
    static Optional<Color> color(char pixel) {
        return switch (pixel) {
            case '.' -> Optional.of(Color.rgb(0, 0, 0));
            case '#' -> Optional.of(Color.rgb(255, 255, 255));
            case 'W' -> Optional.of(Color.rgb(255, 232, 189));
            case 's' -> Optional.of(Color.rgb(105, 53, 0));
            case 'H' -> Optional.of(Color.rgb(126, 126, 126));
            default -> Optional.empty();
        };
    }

    private static Optional<Color> pixel(int row, int column) {
        if (row >= SPRITE.size()) {
            return Optional.empty();
        }
        return color(SPRITE.get(row).charAt(column));
    }

    /** The logo as {@link #HEIGHT} lines of {@link #WIDTH} cells. Transparent pixels leave the background. */
    static List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        for (int row = 0; row < HEIGHT; row++) {
            List<Span> spans = new ArrayList<>();
            for (int column = 0; column < WIDTH; column++) {
                Optional<Color> top = pixel(2 * row, column);
                Optional<Color> bottom = pixel(2 * row + 1, column);
                if (top.isPresent() && bottom.isPresent()) {
                    spans.add(Span.styled("▀", Style.EMPTY.fg(top.get()).bg(bottom.get())));
                } else if (top.isPresent()) {
                    spans.add(Span.styled("▀", Style.EMPTY.fg(top.get())));
                } else if (bottom.isPresent()) {
                    spans.add(Span.styled("▄", Style.EMPTY.fg(bottom.get())));
                } else {
                    spans.add(Span.raw(" "));
                }
            }
            lines.add(Line.from(spans));
        }
        return lines;
    }

    /**
     * leia's name and version, what the name stands for, then its build: {@code leia 0.1.0},
     * {@code Lucene Explorer for Index Analysis}, {@code build 9229a55}, the commit time. leia and
     * its backend are one build (ADR 0034), so the backend's version is leia's.
     */
    public static List<Line> aboutLines(VersionResult version) {
        return List.of(
                Line.from(Span.styled("leia", Style.EMPTY.bold()), Span.raw(" " + version.version())),
                acronym(),
                Line.from("build " + version.commit()),
                Line.from(version.commitTime()));
    }

    /** {@code Lucene Explorer for Index Analysis}, with the letters of leia's name bold and the rest dim. */
    static Line acronym() {
        List<Span> spans = new ArrayList<>();
        for (String word : List.of("Lucene", "Explorer", "for", "Index", "Analysis")) {
            if (!spans.isEmpty()) {
                spans.add(Span.styled(" ", Style.EMPTY.dim()));
            }
            if (word.equals("for")) {
                spans.add(Span.styled(word, Style.EMPTY.dim()));
            } else {
                spans.add(Span.styled(word.substring(0, 1), Style.EMPTY.bold()));
                spans.add(Span.styled(word.substring(1), Style.EMPTY.dim()));
            }
        }
        return Line.from(spans);
    }

    /** Columns that the logo and {@code text} next to it take. */
    public static int widthBeside(List<Line> text) {
        int textWidth = 0;
        for (Line line : text) {
            textWidth = Math.max(textWidth, line.width());
        }
        return WIDTH + GAP + textWidth;
    }

    /**
     * Draws the logo at the left of {@code area} and {@code text} next to it, centred on the logo's
     * height if it is shorter, wrapped if it is too wide.
     */
    public static void renderBeside(Frame frame, Rect area, List<Line> text) {
        List<Rect> columns = Layout.horizontal()
                .constraints(Constraint.length(WIDTH), Constraint.length(GAP), Constraint.fill())
                .split(area);
        frame.renderWidget(Paragraph.from(Text.from(lines())), columns.get(0));
        int offset = Math.max(HEIGHT - text.size(), 0) / 2;
        Rect textArea = Layout.vertical()
                .constraints(Constraint.length(offset), Constraint.fill())
                .split(columns.get(2))
                .get(1);
        frame.renderWidget(Paragraph.from(Text.from(Panels.wrap(text, textArea.width()))), textArea);
    }
}
