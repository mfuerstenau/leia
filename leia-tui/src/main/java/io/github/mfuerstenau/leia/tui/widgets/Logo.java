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
 * leia's logo: pixel art in seven shades of grey and cream, drawn with half blocks so that one
 * terminal cell holds two pixels, one above the other, which keeps the pixels about square. Next to
 * it go leia's name, version and build.
 */
public final class Logo {

    /**
     * The pixels, one character each: the digits {@code 0} to {@code 6} from darkest to lightest,
     * {@code g} transparent.
     */
    static final List<String> SPRITE = List.of(
            "ggg023233323233233233200ggg",
            "ggg000001111110111100000ggg",
            "gg00464513445454331655400gg",
            "g0055335401112121064333600g",
            "105320006011211110500003510",
            "053305500065150650305651350",
            "053062551034031330355223250",
            "053150340010110110053051250",
            "055301450255555542055211340",
            "g0543350012222222200533350g",
            "gg06560g03333333320g06650gg",
            "gg00000gg011111110gg00000gg",
            "ggggggggg034555320ggggggggg",
            "ggggggggg000000000ggggggggg");

    /** Columns the logo takes. */
    public static final int WIDTH = 27;

    /** Rows the logo takes: two pixel rows each. */
    public static final int HEIGHT = 7;

    /** Columns between the logo and the text next to it. */
    static final int GAP = 2;

    private Logo() {}

    /** The colour of a pixel; empty for a transparent one. */
    static Optional<Color> color(char pixel) {
        return switch (pixel) {
            case '0' -> Optional.of(Color.rgb(0x3c, 0x3c, 0x3c));
            case '1' -> Optional.of(Color.rgb(0x5d, 0x5b, 0x58));
            case '2' -> Optional.of(Color.rgb(0xab, 0x9f, 0x90));
            case '3' -> Optional.of(Color.rgb(0xcf, 0xc1, 0xac));
            case '4' -> Optional.of(Color.rgb(0xf7, 0xeb, 0xd4));
            case '5' -> Optional.of(Color.rgb(0xfc, 0xf7, 0xe6));
            case '6' -> Optional.of(Color.rgb(0xfe, 0xfe, 0xfd));
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
     * {@code Luke Evolved Index Analysis}, {@code build 9229a55}, the commit time. leia and
     * its backend are one build (ADR 0034), so the backend's version is leia's.
     */
    public static List<Line> aboutLines(VersionResult version) {
        return List.of(
                Line.from(Span.styled("leia", Style.EMPTY.bold()), Span.raw(" " + version.version())),
                acronym(),
                Line.from("build " + version.commit()),
                Line.from(version.commitTime()));
    }

    /** {@code Luke Evolved Index Analysis}, with the letters of leia's name bold and the rest dim. */
    static Line acronym() {
        List<Span> spans = new ArrayList<>();
        for (String word : List.of("Luke", "Evolved", "Index", "Analysis")) {
            if (!spans.isEmpty()) {
                spans.add(Span.styled(" ", Style.EMPTY.dim()));
            }
            spans.add(Span.styled(word.substring(0, 1), Style.EMPTY.bold()));
            spans.add(Span.styled(word.substring(1), Style.EMPTY.dim()));
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
