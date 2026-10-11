package io.github.mfuerstenau.leia.tui.spike;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The spike of ADR 0034: one immediate-mode TamboUI app with a tab for each risk. Keys (every
 * {@link KeyEvent} logged, plus a text input), a table of 10,000 rows, wide characters, and the
 * clipboard with the threads. Deleted when the app shell is ported.
 */
public final class SpikeApp implements AutoCloseable {

    static final int TABLE_ROWS = 10_000;
    static final String CLIPBOARD_TEXT = "grüße 漢字 🔍";
    private static final int KEY_LOG_SIZE = 30;

    enum Tab {
        KEYS("Keys"),
        TABLE("Table"),
        TEXT("Wide text"),
        MISC("Clipboard & threads");

        final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    /** A result from the backend thread, dispatched to the UI thread so that the UI redraws. */
    record BackendDone(String result) implements Event {}

    /** The first frame is on the screen: launcher start to here is the startup time. */
    public record FirstFrame(Instant at) implements Event {}

    private final String backendDescription;
    private final Instant started;
    private final BufferedWriter keyLogFile;
    private final Deque<String> keyLog = new ArrayDeque<>();
    private final TextInput input = new TextInput();
    // Built on the first visit to the table, so that the startup time is the UI's, not the sample data's.
    private List<Row> rows;
    private final TableState tableState = new TableState();
    private final ExecutorService backend = Executors.newSingleThreadExecutor(r -> new Thread(r, "leia-backend"));

    private Tab tab = Tab.KEYS;
    private int keys;
    private int ticks;
    private int resizes = -1;
    private Rect lastArea;
    private int tableViewport = 1;
    private long renderNanos;
    private long maxRenderNanos;
    private Duration terminalReady;
    private Duration firstFrame;
    private String clipboard = "–";
    private String backendResult = "–";
    private int backendCalls;
    private int strayPrints;

    /**
     * @param keyLogFile where every key is also written, so that a run can be pasted back; null for
     *     none
     */
    public SpikeApp(String backendDescription, Path keyLogFile, Instant started, Instant terminalReady)
            throws IOException {
        this.backendDescription = backendDescription;
        this.started = started;
        this.terminalReady = Duration.between(started, terminalReady);
        this.keyLogFile = keyLogFile == null ? null : Files.newBufferedWriter(keyLogFile, StandardCharsets.UTF_8);
        tableState.select(0);
        writeKeyLog("# " + environment());
    }

    /** Handles an event on the UI thread; returns whether to redraw. */
    public boolean handle(Event event, TuiRunner runner) {
        return switch (event) {
            case TickEvent _ -> {
                // Stands in for the 100 ms poll of a running job (ADR 0034 §4).
                ticks++;
                yield tab == Tab.MISC;
            }
            case BackendDone done -> {
                backendResult = done.result();
                yield true;
            }
            case FirstFrame first -> {
                firstFrame = Duration.between(started, first.at());
                writeKeyLog("# startup: terminal ready " + terminalReady.toMillis() + " ms, first frame "
                        + firstFrame.toMillis() + " ms");
                yield true;
            }
            case KeyEvent key -> handleKey(key, runner);
            default -> false;
        };
    }

    private boolean handleKey(KeyEvent key, TuiRunner runner) {
        logKey(key);
        boolean ctrl = key.hasCtrl() && !key.hasAlt();
        if (ctrl && key.isChar('c')) {
            runner.quit();
            return false;
        }
        if (ctrl && key.isChar('t')) {
            tab = Tab.values()[(tab.ordinal() + 1) % Tab.values().length];
            return true;
        }
        if (tab == Tab.KEYS) {
            input.handleKey(key);
            return true;
        }
        if (key.code() == KeyCode.CHAR && !key.modifiers().hasAny()) {
            int c = key.codePoint();
            if (c >= '1' && c <= '0' + Tab.values().length) {
                tab = Tab.values()[c - '1'];
                return true;
            }
            if (c == 'q') {
                runner.quit();
                return false;
            }
        }
        switch (tab) {
            case TABLE -> moveSelection(key);
            case MISC -> misc(key, runner);
            default -> {}
        }
        return true;
    }

    private void moveSelection(KeyEvent key) {
        int selected = tableState.selected() == null ? 0 : tableState.selected();
        int target = switch (key.code()) {
            case UP -> selected - 1;
            case DOWN -> selected + 1;
            case PAGE_UP -> selected - tableViewport;
            case PAGE_DOWN -> selected + tableViewport;
            case HOME -> 0;
            case END -> rows().size() - 1;
            default -> selected;
        };
        tableState.select(Math.clamp(target, 0, rows().size() - 1));
    }

    private void misc(KeyEvent key, TuiRunner runner) {
        if (key.isChar('y')) {
            try {
                runner.backend().writeRaw(Osc52.of(CLIPBOARD_TEXT));
                runner.backend().flush();
                clipboard = "sent \"" + CLIPBOARD_TEXT + "\" at " + Instant.now();
            } catch (IOException e) {
                clipboard = "failed: " + e;
            }
        } else if (key.isChar('b')) {
            // The backend thread of ADR 0034 §3: the UI never waits; the result comes back as an event.
            int call = ++backendCalls;
            backendResult = "call " + call + " running…";
            backend.submit(() -> {
                Thread.sleep(300);
                runner.dispatch(new BackendDone(
                        "call " + call + " done on " + Thread.currentThread().getName()));
                return null;
            });
        } else if (key.isChar('p')) {
            // While the UI runs, both streams go to leia.log (ADR 0034 §7): nothing may show.
            strayPrints++;
            System.out.println("stray System.out print " + strayPrints);
            System.err.println("stray System.err print " + strayPrints);
        }
    }

    public void render(Frame frame) {
        long start = System.nanoTime();
        Rect area = frame.area();
        if (!area.equals(lastArea)) {
            resizes++;
            lastArea = area;
        }
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill(), Constraint.length(2))
                .split(area);
        frame.renderWidget(Paragraph.from(tabBar()), parts.get(0));
        switch (tab) {
            case KEYS -> renderKeys(frame, parts.get(1));
            case TABLE -> renderTable(frame, parts.get(1));
            case TEXT -> renderText(frame, parts.get(1));
            case MISC -> renderMisc(frame, parts.get(1));
        }
        frame.renderWidget(Paragraph.from(Text.from(status())), parts.get(2));
        renderNanos = System.nanoTime() - start;
        maxRenderNanos = Math.max(maxRenderNanos, renderNanos);
    }

    private Line tabBar() {
        List<Span> spans = new ArrayList<>();
        for (Tab t : Tab.values()) {
            String label = " " + (t.ordinal() + 1) + " " + t.title + " ";
            spans.add(t == tab ? Span.styled(label, Style.EMPTY.reversed()) : Span.raw(label));
        }
        spans.add(Span.raw("  Ctrl+T next tab · Ctrl+C quits · 1–4/q outside Keys")
                .dim());
        return Line.from(spans);
    }

    private List<Line> status() {
        String line1 = String.format(
                Locale.ROOT,
                "backend %s · Java %s · %dx%d · resizes %d",
                backendDescription,
                Runtime.version(),
                lastArea.width(),
                lastArea.height(),
                resizes);
        String line2 = String.format(
                Locale.ROOT,
                "render %.2f ms (max %.2f) · terminal ready %s · first frame %s",
                renderNanos / 1e6,
                maxRenderNanos / 1e6,
                millis(terminalReady),
                millis(firstFrame));
        return List.of(
                Line.from(Span.raw(line1).dim()), Line.from(Span.raw(line2).dim()));
    }

    private static String millis(Duration duration) {
        return duration == null ? "–" : duration.toMillis() + " ms";
    }

    private void renderKeys(Frame frame, Rect area) {
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(3))
                .split(area);
        List<Line> lines = new ArrayList<>();
        for (String entry : keyLog) {
            lines.add(Line.from(entry));
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(lines))
                        .block(block("Keys, newest last (" + keys + " so far, also in leia-spike-keys.log)"))
                        .build(),
                parts.get(0));
        String text = input.text();
        int cursor = input.cursor();
        String atCursor = cursor < text.length() ? Character.toString(text.codePointAt(cursor)) : " ";
        Line line = Line.from(
                Span.raw(text.substring(0, cursor)),
                Span.styled(atCursor, Style.EMPTY.reversed()),
                Span.raw(cursor < text.length() ? text.substring(cursor + atCursor.length()) : ""));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(line))
                        .block(block("Type here: AltGr characters, wide characters; Ctrl+A/E/U/K/W"))
                        .build(),
                parts.get(1));
    }

    private void renderTable(Frame frame, Rect area) {
        Table table = Table.builder()
                .rows(rows())
                .header(Row.from("#", "id", "title", "price", "tags").style(Style.EMPTY.bold()))
                .widths(
                        Constraint.length(6),
                        Constraint.length(10),
                        Constraint.fill(),
                        Constraint.length(9),
                        Constraint.length(24))
                .highlightStyle(Style.EMPTY.reversed())
                .highlightSymbol("> ")
                .block(block("Table: " + rows().size() + " rows · ↑↓ PgUp PgDn Home End"))
                .build();
        tableViewport = Math.max(1, table.viewportHeight(area));
        frame.renderStatefulWidget(table, area, tableState);
    }

    private void renderText(Frame frame, Rect area) {
        List<Row> samples = List.of(
                Row.from("ASCII", "abcdefghij", "end"),
                Row.from("Umlauts", "grüße äöü ß", "end"),
                Row.from("CJK", "漢字かなカナ", "end"),
                Row.from("Hangul", "한국어", "end"),
                Row.from("Emoji", "🔍📄✅", "end"),
                Row.from("ZWJ emoji", "👩‍💻 👨‍👩‍👧", "end"),
                Row.from("Combining", "é ä vs é ä", "end"),
                Row.from("Flags", "🇩🇪 🇯🇵", "end"),
                Row.from("Mixed", "doc-0001 漢 🔍 ü", "end"));
        Table table = Table.builder()
                .rows(samples)
                .widths(Constraint.length(12), Constraint.length(24), Constraint.length(4))
                .columnSpacing(1)
                .block(block("Wide text: every \"end\" and the right border must line up"))
                .build();
        Rect left = Layout.horizontal()
                .constraints(Constraint.length(45), Constraint.fill())
                .split(area)
                .get(0);
        frame.renderStatefulWidget(table, left, new TableState());
    }

    private void renderMisc(Frame frame, Rect area) {
        List<Line> lines = List.of(
                Line.from("y  copy \"" + CLIPBOARD_TEXT + "\" through OSC 52, then paste it elsewhere"),
                Line.from("   " + clipboard),
                Line.empty(),
                Line.from("b  a 300 ms call on the backend thread; the result comes back as an event"),
                Line.from("   " + backendResult),
                Line.empty(),
                Line.from("p  print to System.out and System.err: nothing may show here (" + strayPrints + " so far)"),
                Line.empty(),
                Line.from("ticks (100 ms, the job poll): " + ticks));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(lines))
                        .block(block("Clipboard & threads"))
                        .build(),
                area);
    }

    private static Block block(String title) {
        return Block.builder().title(" " + title + " ").borders(Borders.ALL).build();
    }

    private void logKey(KeyEvent key) {
        keys++;
        String entry = String.format(
                Locale.ROOT,
                "%4d  %-10s %-14s U+%04X %s",
                keys,
                key.code(),
                modifiers(key),
                key.codePoint(),
                key.code() == KeyCode.CHAR ? "'" + Character.toString(key.codePoint()) + "'" : "");
        keyLog.addLast(entry);
        while (keyLog.size() > KEY_LOG_SIZE) {
            keyLog.removeFirst();
        }
        writeKeyLog(entry);
    }

    private static String modifiers(KeyEvent key) {
        List<String> names = new ArrayList<>();
        if (key.hasCtrl()) {
            names.add("ctrl");
        }
        if (key.hasAlt()) {
            names.add("alt");
        }
        if (key.hasShift()) {
            names.add("shift");
        }
        return names.isEmpty() ? "-" : String.join("+", names);
    }

    private void writeKeyLog(String line) {
        if (keyLogFile == null) {
            return;
        }
        try {
            keyLogFile.write(line);
            keyLogFile.newLine();
            keyLogFile.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String environment() {
        return String.format(
                Locale.ROOT,
                "backend %s · Java %s (%s) · %s %s · TERM=%s · WT_SESSION=%s",
                backendDescription,
                Runtime.version(),
                System.getProperty("java.vendor"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getenv("TERM"),
                System.getenv("WT_SESSION") == null ? "unset" : "set");
    }

    private List<Row> rows() {
        if (rows == null) {
            rows = tableRows();
        }
        return rows;
    }

    private static List<Row> tableRows() {
        String[] titles = {"Lucene in Action", "Grüße aus Köln", "全文検索エンジン", "Search 🔍 tips", "Plain old text"};
        List<Row> rows = new ArrayList<>(TABLE_ROWS);
        for (int i = 0; i < TABLE_ROWS; i++) {
            rows.add(Row.from(
                    Integer.toString(i),
                    String.format(Locale.ROOT, "doc-%05d", i),
                    titles[i % titles.length] + " #" + i,
                    String.format(Locale.ROOT, "%,.2f", i * 1.37),
                    "tag" + (i % 7) + " tag" + (i % 11)));
        }
        return rows;
    }

    // For the tests.

    Tab tab() {
        return tab;
    }

    String inputText() {
        return input.text();
    }

    List<String> keyLog() {
        return List.copyOf(keyLog);
    }

    Integer selectedRow() {
        return tableState.selected();
    }

    String backendResult() {
        return backendResult;
    }

    @Override
    public void close() throws IOException {
        // No shutdownNow: interrupting a thread that reads an index breaks NIOFSDirectory (rule 7).
        backend.shutdown();
        if (keyLogFile != null) {
            keyLogFile.close();
        }
    }
}
