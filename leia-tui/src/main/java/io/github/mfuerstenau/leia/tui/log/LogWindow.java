package io.github.mfuerstenau.leia.tui.log;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.client.log.LogBuffer;
import io.github.mfuerstenau.leia.tui.Osc52;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.widgets.KeyLegend;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

/**
 * The log window (ADR 0087), opened with {@code L} on any screen: the records of a {@link
 * LogBuffer} at or above a level, newest at the bottom, which it follows as they come. Stepping the
 * level with {@code -} and {@code +} also sets the level logged at, so that a more verbose level
 * shows records from then on.
 */
public final class LogWindow {

    /** The levels {@code +} and {@code -} step through, least verbose first. */
    static final List<Level> LEVELS =
            List.of(Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE, Level.FINER, Level.FINEST);

    /** How far ← and → scroll sideways. */
    private static final int COLUMN_STEP = 20;

    /** What continues a record on the lines below its first. */
    private static final String INDENT = "    ";

    private static final String KEYS = "↑↓←→ scroll   g/G start/end   -/+ level   y copy   Esc close";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** What a key did to the window. */
    public sealed interface Event {}

    /** Nothing for the caller to do. */
    public record Nothing() implements Event {}

    /** Esc: close the window. */
    public record Close() implements Event {}

    /** {@code y}: put the shown records on the clipboard, as {@code leia.log} has them. */
    public record Copy(String text) implements Event {}

    /** A line of the window: a record's first line, or one that continues it. */
    record Row(String text, Style style) {}

    private final LogBuffer buffer;
    private final ZoneId zone;
    private Level filter;

    /** The first row shown. */
    private int scroll;

    /** Whether the window shows the last row as records arrive. */
    private boolean follow = true;

    /** The first column shown, for rows wider than the window. */
    private int column;

    /** How many rows the last frame showed, for paging and following. */
    private int rows = 1;

    /** What the last copy did, until the next key. */
    private Optional<String> message = Optional.empty();

    /** The serial and filter that {@link #shown} and {@link #rowsShown} were built for. */
    private long cachedSerial = -1;

    private Level cachedFilter;
    private List<LogBuffer.Entry> shown = List.of();
    private List<Row> rowsShown = List.of();

    /** The serial of the buffer that the last frame showed. */
    private long renderedSerial = -1;

    /** A window on {@code buffer} that shows the records at or above {@code filter}. */
    public LogWindow(LogBuffer buffer, Level filter) {
        this(buffer, filter, ZoneId.systemDefault());
    }

    LogWindow(LogBuffer buffer, Level filter, ZoneId zone) {
        this.buffer = buffer;
        this.zone = zone;
        this.filter = nearest(filter);
    }

    /** The first of {@link #LEVELS} that shows {@code level}'s records, or FINEST for one below it. */
    static Level nearest(Level level) {
        return LEVELS.stream()
                .filter(step -> step.intValue() <= level.intValue())
                .findFirst()
                .orElse(Level.FINEST);
    }

    /** The level shown, which the app keeps for the next window. */
    public Level filter() {
        return filter;
    }

    /** Whether records came since the last frame, so that it is drawn again. */
    public boolean changed() {
        return buffer.serial() != renderedSerial;
    }

    public Event handleKey(KeyEvent key) {
        message = Optional.empty();
        switch (key.code()) {
            case ESCAPE -> {
                return new Close();
            }
            case DOWN -> scrollTo(scroll + 1);
            case UP -> scrollTo(scroll - 1);
            case PAGE_DOWN -> scrollTo(scroll + rows);
            case PAGE_UP -> scrollTo(scroll - rows);
            case HOME -> scrollTo(0);
            case END -> scrollTo(Integer.MAX_VALUE);
            case RIGHT -> column += COLUMN_STEP;
            case LEFT -> column = Math.max(column - COLUMN_STEP, 0);
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'j' -> scrollTo(scroll + 1);
                    case 'k' -> scrollTo(scroll - 1);
                    case 'g' -> scrollTo(0);
                    case 'G' -> scrollTo(Integer.MAX_VALUE);
                    case '-' -> step(1);
                    case '+', '=' -> step(-1);
                    case 'y' -> {
                        return copy();
                    }
                    default -> {}
                }
            }
            default -> {}
        }
        return new Nothing();
    }

    private void scrollTo(int to) {
        int last = lastScroll();
        scroll = Math.clamp(to, 0, last);
        follow = scroll >= last;
    }

    /** The scroll that shows the last row at the bottom. */
    private int lastScroll() {
        return Math.max(rows().size() - rows, 0);
    }

    /** One level more verbose for {@code by} 1, one less for -1, and the log follows. */
    private void step(int by) {
        int at = LEVELS.indexOf(filter);
        filter = LEVELS.get(Math.clamp((long) at + by, 0, LEVELS.size() - 1));
        buffer.logFrom(filter);
    }

    private Event copy() {
        List<LogBuffer.Entry> entries = entries();
        if (entries.isEmpty()) {
            message = Optional.of("Nothing to copy.");
            return new Nothing();
        }
        String text = copyText(entries);
        int length = TextFormat.utf8Length(text);
        String caveat = length > Osc52.LARGE_COPY ? "; terminals may drop copies this large" : "";
        message = Optional.of("Copied " + TextFormat.count(entries.size(), "record") + ", " + TextFormat.size(length)
                + " (OSC 52)" + caveat + ".");
        return new Copy(text);
    }

    /** {@code entries} as {@code leia.log}'s default format writes them, with the loggers' full names. */
    String copyText(List<LogBuffer.Entry> entries) {
        StringBuilder text = new StringBuilder();
        for (LogBuffer.Entry entry : entries) {
            text.append(head(entry, entry.logger())).append(entry.message()).append('\n');
            String trace = entry.trace();
            if (trace != null) {
                text.append(trace);
                if (!trace.endsWith("\n")) {
                    text.append('\n');
                }
            }
        }
        return text.toString();
    }

    /** The time, the level and the logger, as the default format writes them before the message. */
    private String head(LogBuffer.Entry entry, String logger) {
        return TIME.format(entry.instant().atZone(zone)) + " "
                + String.format("%-7s", entry.level().getLocalizedName()) + " [" + logger + "] ";
    }

    /** The records at or above the filter, oldest first. */
    List<LogBuffer.Entry> entries() {
        refresh();
        return shown;
    }

    /** The rows of the records at or above the filter. */
    List<Row> rows() {
        refresh();
        return rowsShown;
    }

    private void refresh() {
        long serial = buffer.serial();
        if (serial == cachedSerial && filter.equals(cachedFilter)) {
            return;
        }
        cachedSerial = serial;
        cachedFilter = filter;
        shown = buffer.entries().stream()
                .filter(entry -> entry.level().intValue() >= filter.intValue())
                .toList();
        List<Row> built = new ArrayList<>();
        for (LogBuffer.Entry entry : shown) {
            Style style = style(entry.level());
            String logger = entry.logger().substring(entry.logger().lastIndexOf('.') + 1);
            List<String> lines = new ArrayList<>(entry.message().lines().toList());
            String trace = entry.trace();
            if (trace != null) {
                lines.addAll(trace.lines().toList());
            }
            String head = head(entry, logger);
            for (int i = 0; i < Math.max(lines.size(), 1); i++) {
                String line = i < lines.size() ? lines.get(i).replace("\t", INDENT) : "";
                built.add(new Row(i == 0 ? head + line : INDENT + line, style));
            }
        }
        rowsShown = List.copyOf(built);
    }

    /** SEVERE in red, WARNING in yellow, and FINE and below dim. */
    static Style style(Level level) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return Palette.current().error();
        }
        if (level.intValue() >= Level.WARNING.intValue()) {
            return Palette.current().warning();
        }
        return level.intValue() < Level.CONFIG.intValue() ? Palette.current().muted() : Style.EMPTY;
    }

    /** The window's title, for the rows that the last frame showed. */
    String title() {
        List<Row> all = rows();
        StringBuilder title = new StringBuilder(" Log: " + filter.getName() + " and above");
        if (all.isEmpty()) {
            title.append(", no records");
        } else {
            title.append(", lines ")
                    .append(scroll + 1)
                    .append('–')
                    .append(Math.min(scroll + rows, all.size()))
                    .append(" of ")
                    .append(all.size());
        }
        long dropped = buffer.dropped();
        if (dropped > 0) {
            title.append(" (").append(TextFormat.count(dropped, "older record")).append(" dropped)");
        }
        Level logged = buffer.logged();
        if (logged.intValue() != filter.intValue()) {
            title.append(", logging at ").append(logged.getName());
        }
        return title.append(' ').toString();
    }

    /** Draws the window over most of {@code area}. */
    public void render(Frame frame, Rect area) {
        renderedSerial = buffer.serial();
        int width = Math.max(area.width() - 4, 0);
        int height = Math.max(area.height() - 2, 0);
        Rect window = Panels.centered(area, width, height);
        Panels.clear(frame, window);
        Block probe = Panels.box().build();
        Rect inner = probe.inner(window);
        if (inner.width() <= 0 || inner.height() <= 0) {
            frame.renderWidget(probe, window);
            return;
        }
        List<Line> footer = new ArrayList<>();
        message.ifPresent(text ->
                footer.add(Line.from(Span.styled(" " + text, Palette.current().muted()))));
        footer.addAll(KeyLegend.lines(KEYS, inner.width()));
        int footerCap = Math.max(inner.height() / 3, 1);
        int footerHeight = Math.min(footer.size(), footerCap);
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(footerHeight))
                .split(inner);
        Rect list = parts.get(0);

        List<Row> all = rows();
        rows = Math.max(list.height(), 1);
        if (follow) {
            scroll = lastScroll();
        }
        scroll = Math.min(scroll, lastScroll());
        int widest = all.stream().mapToInt(row -> codePoints(row.text())).max().orElse(0);
        // TamboUI's Paragraph doesn't scroll sideways, so the rows are cut here.
        column = Math.clamp(column, 0, Math.max(widest - list.width(), 0));
        List<Line> lines = new ArrayList<>();
        if (all.isEmpty()) {
            lines.add(Line.from(Span.styled(
                    "No records at " + filter.getName() + " and above yet",
                    Palette.current().muted())));
        }
        for (Row row : all.subList(scroll, Math.min(scroll + rows, all.size()))) {
            lines.add(Line.styled(fromColumn(row.text(), column), row.style()));
        }
        frame.renderWidget(Panels.box().title(title()).build(), window);
        frame.renderWidget(Paragraph.from(Text.from(lines)), list);
        frame.renderWidget(Paragraph.from(Text.from(footer)), parts.get(1));
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** {@code text} from its code point {@code column} on. */
    private static String fromColumn(String text, int column) {
        return column >= codePoints(text) ? "" : text.substring(text.offsetByCodePoints(0, column));
    }
}
