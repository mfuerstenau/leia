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
import io.github.mfuerstenau.leia.tui.theme.Palette;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * leia's logo: ASCII art with a colour per character, read from the resource {@code leia.icon},
 * which holds a row per line, each character after an ANSI escape that sets its 24-bit foreground
 * colour. Next to it go leia's name, version and build.
 */
public final class Logo {

    /** An ANSI escape that sets a 24-bit foreground colour, and the character it colours. */
    private static final Pattern CELL = Pattern.compile("\u001b\\[38;2;(\\d+);(\\d+);(\\d+)m(.)");

    /** The ANSI escape that ends a row. */
    private static final String RESET = "\u001b[0m";

    /** The logo's rows, without the columns that are blank in every row at the right. */
    private static final List<Line> LINES = load();

    /** {@link #LINES} without their colours, for a theme without them (ADR 0095). */
    private static final List<Line> PLAIN = LINES.stream()
            .map(line -> Line.from(
                    line.spans().stream().map(span -> Span.raw(span.content())).toList()))
            .toList();

    /** Columns the logo takes. */
    public static final int WIDTH = LINES.getFirst().width();

    /** Rows the logo takes. */
    public static final int HEIGHT = LINES.size();

    /**
     * The fewest rows the logo is drawn with. Where there are fewer than {@link #HEIGHT}, its top
     * is cut off, down to these rows, which leave out only the sparse dots at the top.
     */
    public static final int MIN_HEIGHT = 18;

    /** Columns between the logo and the text next to it. */
    static final int GAP = 2;

    /** The fewest columns left for the text next to the logo. */
    static final int MIN_TEXT_WIDTH = 30;

    private Logo() {}

    /** The logo as {@link #HEIGHT} lines of {@link #WIDTH} cells. Blank cells leave the background. */
    static List<Line> lines() {
        return LINES;
    }

    private static List<Line> load() {
        List<String> rows;
        try (InputStream in = Logo.class.getResourceAsStream("leia.icon")) {
            if (in == null) {
                throw new IllegalStateException("leia.icon is missing");
            }
            rows = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<List<Span>> cells = new ArrayList<>();
        for (String row : rows) {
            cells.add(parse(row));
        }
        int width = 0;
        for (List<Span> row : cells) {
            for (int column = 0; column < row.size(); column++) {
                if (!row.get(column).content().isBlank()) {
                    width = Math.max(width, column + 1);
                }
            }
        }
        List<Line> lines = new ArrayList<>();
        for (List<Span> row : cells) {
            List<Span> spans = new ArrayList<>(row.subList(0, Math.min(width, row.size())));
            while (spans.size() < width) {
                spans.add(Span.raw(" "));
            }
            lines.add(Line.from(spans));
        }
        if (lines.isEmpty() || width == 0) {
            throw new IllegalStateException("leia.icon is empty");
        }
        return List.copyOf(lines);
    }

    /** The cells of one row of {@code leia.icon}; a space is uncoloured. */
    private static List<Span> parse(String row) {
        List<Span> spans = new ArrayList<>();
        Matcher matcher = CELL.matcher(row);
        int end = 0;
        while (matcher.find() && matcher.start() == end) {
            String character = matcher.group(4);
            spans.add(
                    character.equals(" ")
                            ? Span.raw(" ")
                            : Span.styled(
                                    character,
                                    Style.EMPTY.fg(Color.rgb(
                                            Integer.parseInt(matcher.group(1)),
                                            Integer.parseInt(matcher.group(2)),
                                            Integer.parseInt(matcher.group(3))))));
            end = matcher.end();
        }
        String rest = row.substring(end);
        if (!rest.isEmpty() && !rest.equals(RESET)) {
            throw new IllegalStateException("leia.icon: unexpected " + rest);
        }
        return spans;
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
     * Whether the logo, cut to {@link #MIN_HEIGHT} rows at the least, and {@code text} next to it,
     * wrapped, fit into {@code area}.
     */
    public static boolean fitsBeside(Rect area, List<Line> text) {
        int textWidth = area.width() - WIDTH - GAP;
        return area.height() >= MIN_HEIGHT
                && textWidth >= MIN_TEXT_WIDTH
                && Panels.wrap(text, textWidth).size() <= area.height();
    }

    /**
     * Draws the logo at the left of {@code area}, without the rows at its top that don't fit, and
     * {@code text} next to it, centred on the logo's height if it is shorter, wrapped if it is too
     * wide.
     */
    public static void renderBeside(Frame frame, Rect area, List<Line> text) {
        List<Rect> columns = Layout.horizontal()
                .constraints(Constraint.length(WIDTH), Constraint.length(GAP), Constraint.fill())
                .split(area);
        int height = Math.min(HEIGHT, area.height());
        List<Line> lines = Palette.current().colouredLogo() ? LINES : PLAIN;
        frame.renderWidget(Paragraph.from(Text.from(lines.subList(HEIGHT - height, HEIGHT))), columns.get(0));
        int offset = Math.max(height - Panels.wrap(text, columns.get(2).width()).size(), 0) / 2;
        Rect textArea = Layout.vertical()
                .constraints(Constraint.length(offset), Constraint.fill())
                .split(columns.get(2))
                .get(1);
        frame.renderWidget(Paragraph.from(Text.from(Panels.wrap(text, textArea.width()))), textArea);
    }
}
