package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.theme.Icon;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.util.ArrayList;
import java.util.List;

/**
 * leia's logo: the {@link Icon} of the theme in use (ADR 0099), {@link Palette#logo}. Next to the
 * logo go leia's name, version and build.
 */
public final class Logo {

    /**
     * The fewest rows the logo is drawn with. Where there are fewer than its height, its top is cut
     * off, down to these rows, which leave out the tips of the gazelle's horns.
     */
    public static final int MIN_HEIGHT = 18;

    /** Columns between the logo and the text next to it. */
    static final int GAP = 2;

    /** The fewest columns left for the text next to the logo. */
    static final int MIN_TEXT_WIDTH = 30;

    private Logo() {}

    /** The logo of {@code palette} as a line per row. */
    static List<Line> lines(Palette palette) {
        List<Line> lines = new ArrayList<>();
        for (List<Icon.Cell> row : palette.logo().rows()) {
            lines.add(Line.from(row.stream()
                    .map(cell -> Span.styled(cell.character(), cell.style()))
                    .toList()));
        }
        return lines;
    }

    /**
     * leia's name and version, then its build: {@code leia 0.1.0}, {@code build 9229a55}, the commit
     * time. leia and its backend are one build (ADR 0034), so the backend's version is leia's.
     */
    public static List<Line> aboutLines(VersionResult version) {
        return List.of(
                Line.from(Span.styled("leia", Style.EMPTY.bold()), Span.raw(" " + version.version())),
                Line.from("build " + version.commit()),
                Line.from(version.commitTime()));
    }

    /**
     * Whether the logo of the theme in use, cut to {@link #MIN_HEIGHT} rows at the least, and {@code text} next to it,
     * wrapped, fit into {@code area}.
     */
    public static boolean fitsBeside(Rect area, List<Line> text) {
        Icon logo = Palette.current().logo();
        int textWidth = area.width() - logo.width() - GAP;
        return area.height() >= Math.min(MIN_HEIGHT, logo.height())
                && textWidth >= MIN_TEXT_WIDTH
                && Panels.wrap(text, textWidth).size() <= area.height();
    }

    /**
     * Draws the logo at the left of {@code area}, without the rows at its top that don't fit, and
     * {@code text} next to it, centred on the logo's height if it is shorter, wrapped if it is too
     * wide.
     */
    public static void renderBeside(Frame frame, Rect area, List<Line> text) {
        Icon logo = Palette.current().logo();
        List<Rect> columns = Layout.horizontal()
                .constraints(Constraint.length(logo.width()), Constraint.length(GAP), Constraint.fill())
                .split(area);
        int height = Math.min(logo.height(), area.height());
        List<Line> lines = lines(Palette.current());
        frame.renderWidget(
                Paragraph.from(Text.from(lines.subList(logo.height() - height, logo.height()))), columns.get(0));
        int offset = Math.max(height - Panels.wrap(text, columns.get(2).width()).size(), 0) / 2;
        Rect textArea = Layout.vertical()
                .constraints(Constraint.length(offset), Constraint.fill())
                .split(columns.get(2))
                .get(1);
        frame.renderWidget(Paragraph.from(Text.from(Panels.wrap(text, textArea.width()))), textArea);
    }
}
