package io.github.mfuerstenau.leia.tui.runtimes;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.gauge.LineGauge;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.spinner.Spinner;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.KeyLegend;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The dialogue of {@code leia --download-runtimes} (ADR 0079): the user checks the runtimes to
 * download, none at first, sees what each one downloads from where, and watches the download, which
 * Esc cancels. It offers only the runtimes that need a download; {@link RuntimeInstall#install}
 * refreshes the others without asking.
 *
 * <p>The install runs on a thread of its own, which writes into a {@link Progress} that the ticks
 * draw, and ends with a {@link Finished} event. Everything else is touched on the UI thread only.
 */
public final class RuntimeDialog {

    /** Below this width the details go below the list instead of beside it. */
    private static final int WIDE = 90;

    private static final String CHOOSE_KEYS =
            "␣ check   a all   n none   ↑↓ move   [/] details   ⏎ download   Esc/q quit";
    private static final String NOTHING_CHECKED = "Nothing checked: Space checks a runtime; Esc quits";
    private static final String[] SPINNER = Spinner.dots().frames();
    private static final Style FAILED = Style.EMPTY.red();

    private enum Phase {
        CHOOSE,
        INSTALLING,
        DONE
    }

    /** The install has ended. */
    record Finished(RuntimeInstall.Outcome outcome) implements Event {}

    private final RuntimeInstall install;
    private final RuntimeInstall.Plan plan;
    private final List<RuntimeInstall.RuntimePlan> offered;
    private final Set<RuntimeFolders.Known> checked = new LinkedHashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<RuntimeInstall.Outcome> finished = new AtomicReference<>();

    private Phase phase = Phase.CHOOSE;
    private int cursor;
    private int detailsScroll;
    private Optional<String> hint = Optional.empty();
    private Progress progress;
    private Thread installer;
    private int ticks;

    /**
     * @param install installs the runtimes
     * @param plan what {@code install} found
     * @param initiallyChecked the runtimes checked at first, such as those of {@code --runtime}
     */
    public RuntimeDialog(RuntimeInstall install, RuntimeInstall.Plan plan, Set<RuntimeFolders.Known> initiallyChecked) {
        this.install = install;
        this.plan = plan;
        this.offered = plan.needingDownload();
        offered.stream()
                .map(RuntimeInstall.RuntimePlan::known)
                .filter(initiallyChecked::contains)
                .forEach(checked::add);
    }

    /**
     * Runs the dialogue on a terminal of {@code config}.
     *
     * @return how the install ended, or empty if the user downloaded nothing
     */
    public static Optional<RuntimeInstall.Outcome> run(
            RuntimeInstall install,
            RuntimeInstall.Plan plan,
            Set<RuntimeFolders.Known> initiallyChecked,
            TuiConfig config)
            throws Exception {
        RuntimeDialog dialog = new RuntimeDialog(install, plan, initiallyChecked);
        try (TuiRunner runner = TuiRunner.create(config)) {
            runner.run(dialog::handle, dialog::render);
        }
        return dialog.awaitOutcome();
    }

    /**
     * How the install ended, once the dialogue is closed; an install that still runs, such as after
     * Ctrl+C, is cancelled and waited for. Empty if nothing was installed.
     */
    public Optional<RuntimeInstall.Outcome> awaitOutcome() throws InterruptedException {
        if (installer == null) {
            return Optional.empty();
        }
        cancelled.set(true);
        installer.join();
        return Optional.of(finished.get());
    }

    /** Handles {@code event}; true if the screen needs drawing. */
    public boolean handle(Event event, TuiRunner runner) {
        return switch (event) {
            case KeyEvent key -> {
                handleKey(key, runner);
                yield true;
            }
            case Finished done -> {
                phase = Phase.DONE;
                progress.finish(done.outcome());
                yield true;
            }
            case TickEvent _ -> {
                ticks++;
                yield phase == Phase.INSTALLING;
            }
            default -> false;
        };
    }

    private void handleKey(KeyEvent key, TuiRunner runner) {
        switch (phase) {
            case CHOOSE -> choose(key, runner);
            case INSTALLING -> {
                if (key.code() == KeyCode.ESCAPE || key.isCtrlC()) {
                    cancelled.set(true);
                }
            }
            case DONE -> {
                if (key.code() == KeyCode.ESCAPE || key.code() == KeyCode.ENTER || key.isChar('q') || key.isCtrlC()) {
                    runner.quit();
                }
            }
        }
    }

    private void choose(KeyEvent key, TuiRunner runner) {
        hint = Optional.empty();
        if (key.code() == KeyCode.ESCAPE || key.isChar('q') || key.isCtrlC()) {
            runner.quit();
            return;
        }
        if (key.code() == KeyCode.ENTER) {
            if (checked.isEmpty()) {
                hint = Optional.of(NOTHING_CHECKED);
            } else {
                start(runner);
            }
            return;
        }
        if (key.isChar(' ') && !offered.isEmpty()) {
            RuntimeFolders.Known known = offered.get(cursor).known();
            if (!checked.remove(known)) {
                checked.add(known);
            }
        } else if (key.isChar('a')) {
            offered.forEach(runtime -> checked.add(runtime.known()));
        } else if (key.isChar('n')) {
            checked.clear();
        } else if (key.isChar(']') || (key.hasShift() && key.code() == KeyCode.DOWN)) {
            detailsScroll++;
        } else if (key.isChar('[') || (key.hasShift() && key.code() == KeyCode.UP)) {
            detailsScroll = Math.max(detailsScroll - 1, 0);
        } else {
            OptionalInt moved = Selection.moved(cursor, offered.size(), key);
            if (moved.isPresent() && moved.getAsInt() != cursor) {
                cursor = moved.getAsInt();
                detailsScroll = 0;
            }
        }
    }

    /** Starts the install of the checked runtimes. */
    private void start(TuiRunner runner) {
        Set<RuntimeFolders.Known> chosen = Set.copyOf(checked);
        progress = new Progress(offered.stream()
                .filter(runtime -> chosen.contains(runtime.known()))
                .toList());
        phase = Phase.INSTALLING;
        installer = Thread.ofPlatform().daemon().name("leia-runtime-install").start(() -> {
            RuntimeInstall.Outcome outcome;
            try {
                outcome = install.install(plan, chosen, progress, cancelled::get);
            } catch (RuntimeException e) {
                Map<RuntimeFolders.Known, RuntimeInstall.Result> failed = new LinkedHashMap<>();
                chosen.forEach(known -> failed.put(known, new RuntimeInstall.Result.Failed(String.valueOf(e))));
                outcome = new RuntimeInstall.Outcome(failed);
            }
            finished.set(outcome);
            runner.dispatch(new Finished(outcome));
        });
    }

    public void render(Frame frame) {
        Rect area = frame.area();
        List<Line> header = new ArrayList<>();
        List<Line> footer;
        switch (phase) {
            case CHOOSE -> {
                header.add(Line.from(install.source() + ", and checks each against the SHA-256 and size that leia's"
                        + " build recorded."));
                header.add(Line.from(Span.styled(checkedTotal(), Style.EMPTY.bold())));
                footer = new ArrayList<>();
                hint.ifPresent(text -> footer.add(Line.from(Span.styled(" " + text, Style.EMPTY.yellow()))));
                footer.addAll(KeyLegend.lines(CHOOSE_KEYS, area.width()));
            }
            case INSTALLING -> {
                header.add(Line.from(Span.styled(
                        cancelled.get()
                                ? "Cancelling…"
                                : "Downloading " + TextFormat.count(progress.jarCount(), "jar") + " into "
                                        + install.runtimesDir(),
                        Style.EMPTY.bold())));
                footer = KeyLegend.lines("Esc cancel", area.width());
            }
            default -> {
                header.add(Line.from(Span.styled("Done: " + install.runtimesDir(), Style.EMPTY.bold())));
                for (String line : progress.outcome().summary()) {
                    header.add(line.contains(": failed: ") ? Line.from(Span.styled(line, FAILED)) : Line.from(line));
                }
                footer = KeyLegend.lines("⏎/Esc close", area.width());
            }
        }
        List<Line> wrappedHeader = Panels.wrap(header, Math.max(area.width() - 1, 1));
        int headerHeight = Math.min(wrappedHeader.size(), Math.max(area.height() / 3, 1));
        int footerHeight = Math.min(footer.size(), Math.max(area.height() / 3, 1));
        int gaugeHeight = phase == Phase.CHOOSE ? 0 : 1;
        List<Rect> rows = Layout.vertical()
                .constraints(
                        Constraint.length(headerHeight),
                        Constraint.length(gaugeHeight),
                        Constraint.fill(),
                        Constraint.length(footerHeight))
                .split(area);
        frame.renderWidget(Paragraph.from(Text.from(wrappedHeader)), rows.get(0));
        if (phase == Phase.CHOOSE) {
            renderChoose(frame, rows.get(2));
        } else {
            frame.renderWidget(gauge(), rows.get(1));
            renderProgress(frame, rows.get(2));
        }
        frame.renderWidget(Paragraph.from(Text.from(footer)), rows.get(3));
    }

    /** The jars and bytes of the checked runtimes. */
    private String checkedTotal() {
        List<RuntimeInstall.RuntimePlan> runtimes = checkedRuntimes();
        int jars = runtimes.stream()
                .mapToInt(runtime -> runtime.downloads().size())
                .sum();
        long size = runtimes.stream()
                .mapToLong(RuntimeInstall.RuntimePlan::downloadSize)
                .sum();
        return "Checked: " + TextFormat.count(runtimes.size(), "runtime") + ", " + TextFormat.count(jars, "jar") + ", "
                + TextFormat.size(size);
    }

    private List<RuntimeInstall.RuntimePlan> checkedRuntimes() {
        return offered.stream()
                .filter(runtime -> checked.contains(runtime.known()))
                .toList();
    }

    private void renderChoose(Frame frame, Rect area) {
        Block listBlock = Panels.panel(" Runtimes to download ", true);
        List<String> texts = new ArrayList<>();
        for (RuntimeInstall.RuntimePlan runtime : offered) {
            texts.add((checked.contains(runtime.known()) ? "[x] " : "[ ] ")
                    + runtime.known().name()
                    + " · indexes of " + majors(runtime.known().indexMajors()) + " · "
                    + TextFormat.count(runtime.downloads().size(), "jar") + ", "
                    + TextFormat.size(runtime.downloadSize()));
        }
        int listWidth = texts.stream().mapToInt(CharWidth::of).max().orElse(0) + 2;
        List<Rect> parts = area.width() >= WIDE
                ? Layout.horizontal()
                        .constraints(Constraint.length(Math.min(listWidth, area.width() / 2)), Constraint.fill())
                        .split(area)
                : Layout.vertical()
                        .constraints(
                                Constraint.length(Math.min(offered.size() + 2, area.height() / 2)), Constraint.fill())
                        .split(area);
        List<Line> rows = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            rows.add(
                    i == cursor
                            ? Line.from(Span.styled(texts.get(i), Panels.highlight(true)))
                            : Line.from(texts.get(i)));
        }
        Rect listInner = listBlock.inner(parts.get(0));
        int listTop = Math.max(cursor - Math.max(listInner.height() - 1, 0), 0);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(rows.subList(Math.min(listTop, rows.size()), rows.size())))
                        .block(listBlock)
                        .build(),
                parts.get(0));
        if (offered.isEmpty()) {
            return;
        }
        RuntimeInstall.RuntimePlan current = offered.get(cursor);
        Block detailsBlock = Panels.panel(
                " " + current.known().name() + " · runtimes/" + current.known().folder() + " ", false);
        List<Line> details = Panels.wrap(
                details(current), Math.max(detailsBlock.inner(parts.get(1)).width(), 1));
        int maxScroll =
                Math.max(details.size() - detailsBlock.inner(parts.get(1)).height(), 0);
        detailsScroll = Math.min(detailsScroll, maxScroll);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(details))
                        .block(detailsBlock)
                        .scroll(detailsScroll)
                        .build(),
                parts.get(1));
    }

    /** What {@code runtime} downloads from where, what it keeps, and what comes from leia.jar. */
    private static List<Line> details(RuntimeInstall.RuntimePlan runtime) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(
                Span.styled("To download into runtimes/" + runtime.known().folder() + ":", Style.EMPTY.bold())));
        for (RuntimeInstall.Download download : runtime.downloads()) {
            // Unindented: a URL or a SHA-256 wraps on a narrow screen, and loses its indent then.
            lines.add(Line.from("• " + download.jar().name() + ", "
                    + TextFormat.size(download.jar().size())));
            lines.add(Line.from(download.uri().toString()));
            lines.add(Line.from(Span.styled("SHA-256 " + download.jar().sha256(), Style.EMPTY.dim())));
        }
        if (!runtime.kept().isEmpty()) {
            lines.add(Line.empty());
            lines.add(Line.from(Span.styled("Already there and matching:", Style.EMPTY.bold())));
            runtime.kept().forEach(jar -> lines.add(Line.from("  " + jar.name())));
        }
        lines.add(Line.empty());
        lines.add(Line.from(Span.styled("From leia.jar:", Style.EMPTY.bold())));
        runtime.ownJars().forEach(jar -> lines.add(Line.from("  " + jar)));
        return lines;
    }

    /** Such as {@code 8}, {@code 6 and 7}. */
    private static String majors(List<Integer> majors) {
        List<String> names = majors.stream().map(String::valueOf).toList();
        return names.size() == 1
                ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    private LineGauge gauge() {
        long total = progress.total();
        long done = progress.done();
        double ratio = total == 0 ? 1 : Math.min((double) done / total, 1);
        return LineGauge.builder()
                .ratio(ratio)
                .label(TextFormat.size(done) + " of " + TextFormat.size(total) + " ")
                .lineSet(LineGauge.THICK)
                .filledStyle(Style.EMPTY.green())
                .build();
    }

    private void renderProgress(Frame frame, Rect area) {
        Block block = Panels.panel(" Jars ", true);
        Rect inner = block.inner(area);
        List<Line> lines = Panels.wrap(progress.lines(SPINNER[ticks % SPINNER.length]), Math.max(inner.width(), 1));
        // The active jar stays in sight.
        int active = progress.activeLine();
        int top = Math.clamp(active - inner.height() / 2, 0, Math.max(lines.size() - inner.height(), 0));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(lines))
                        .block(block)
                        .scroll(top)
                        .build(),
                area);
    }

    /**
     * The state of each jar of the chosen runtimes, written by the install's thread and read by the
     * UI thread.
     */
    static final class Progress implements RuntimeInstall.Listener {

        private enum State {
            WAITING,
            ACTIVE,
            DOWNLOADED,
            KEPT,
            FAILED,
            CANCELLED
        }

        private static final class Jar {
            final String folder;
            final String name;
            final long size;
            State state = State.WAITING;
            long bytes;

            Jar(String folder, String name, long size) {
                this.folder = folder;
                this.name = name;
                this.size = size;
            }
        }

        private final List<RuntimeInstall.RuntimePlan> runtimes;
        private final Map<String, Jar> jars = new LinkedHashMap<>();
        private final Map<String, String> failures = new LinkedHashMap<>();
        private final long total;
        private long done;
        private Jar active;
        private RuntimeInstall.Outcome outcome;

        Progress(List<RuntimeInstall.RuntimePlan> runtimes) {
            this.runtimes = runtimes;
            long sum = 0;
            for (RuntimeInstall.RuntimePlan runtime : runtimes) {
                for (RuntimeFolders.ListedJar jar : runtime.listed()) {
                    if (jar.mavenPath().isPresent()) {
                        jars.put(
                                key(runtime.known().folder(), jar.name()),
                                new Jar(runtime.known().folder(), jar.name(), jar.size()));
                    }
                }
                sum += runtime.downloadSize();
            }
            total = sum;
        }

        private static String key(String folder, String jar) {
            return folder + "/" + jar;
        }

        synchronized int jarCount() {
            return runtimes.stream()
                    .mapToInt(runtime -> runtime.downloads().size())
                    .sum();
        }

        synchronized long total() {
            return total;
        }

        synchronized long done() {
            return done;
        }

        synchronized RuntimeInstall.Outcome outcome() {
            return outcome;
        }

        @Override
        public synchronized void jarStarted(String folder, String jar, long size) {
            active = jars.get(key(folder, jar));
            if (active != null) {
                active.state = State.ACTIVE;
                active.bytes = 0;
            }
        }

        @Override
        public synchronized void bytes(long n) {
            done += n;
            if (active != null) {
                active.bytes += n;
            }
        }

        @Override
        public synchronized void jarDone(String folder, String jar, RuntimeInstall.JarDone how, long size) {
            Jar found = jars.get(key(folder, jar));
            if (found == null) {
                return;
            }
            switch (how) {
                case DOWNLOADED -> found.state = State.DOWNLOADED;
                case KEPT -> found.state = State.KEPT;
                default -> {}
            }
            if (found == active) {
                active = null;
            }
        }

        @Override
        public synchronized void runtimeFailed(String folder, String message) {
            failures.put(folder, message);
            if (active != null && active.folder.equals(folder)) {
                active.state = State.FAILED;
                active = null;
            }
        }

        /** The install has ended: a jar still active was cancelled. */
        synchronized void finish(RuntimeInstall.Outcome outcome) {
            this.outcome = outcome;
            for (Jar jar : jars.values()) {
                if (jar.state == State.ACTIVE) {
                    jar.state = State.CANCELLED;
                }
            }
            active = null;
        }

        /** The line of the active jar in {@link #lines}, or 0. */
        synchronized int activeLine() {
            int line = 0;
            for (RuntimeInstall.RuntimePlan runtime : runtimes) {
                line++;
                if (failures.containsKey(runtime.known().folder())) {
                    line++;
                }
                for (Jar jar : jars.values()) {
                    if (jar.folder.equals(runtime.known().folder())) {
                        if (jar == active) {
                            return line;
                        }
                        line++;
                    }
                }
            }
            return 0;
        }

        /** A line per runtime, its failure if any, and a line per Lucene jar of it. */
        synchronized List<Line> lines(String spinner) {
            List<Line> lines = new ArrayList<>();
            for (RuntimeInstall.RuntimePlan runtime : runtimes) {
                String folder = runtime.known().folder();
                lines.add(Line.from(Span.styled(runtime.known().name() + " · runtimes/" + folder, Style.EMPTY.bold())));
                if (failures.containsKey(folder)) {
                    lines.add(Line.from(Span.styled("  ✗ " + failures.get(folder), FAILED)));
                }
                for (Jar jar : jars.values()) {
                    if (jar.folder.equals(folder)) {
                        lines.add(line(jar, spinner));
                    }
                }
            }
            return lines;
        }

        private static Line line(Jar jar, String spinner) {
            String size = TextFormat.size(jar.size);
            return switch (jar.state) {
                case WAITING -> Line.from(Span.styled("  · " + jar.name + ", " + size, Style.EMPTY.dim()));
                case ACTIVE ->
                    Line.from(
                            Span.styled("  " + spinner + " ", Style.EMPTY.cyan()),
                            Span.raw(jar.name + ", " + TextFormat.size(jar.bytes) + " / " + size));
                case DOWNLOADED ->
                    Line.from(
                            Span.styled("  ✓ ", Style.EMPTY.green()), Span.raw(jar.name + ", " + size + " downloaded"));
                case KEPT -> Line.from(Span.raw("  = " + jar.name + ", " + size + " kept"));
                case FAILED -> Line.from(Span.styled("  ✗ " + jar.name + ", " + size, FAILED));
                case CANCELLED ->
                    Line.from(Span.styled(
                            "  – " + jar.name + ", " + TextFormat.size(jar.bytes) + " / " + size + " cancelled",
                            Style.EMPTY.dim()));
            };
        }
    }
}
