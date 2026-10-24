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
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.gauge.LineGauge;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.spinner.Spinner;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
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
import java.util.function.BooleanSupplier;

/**
 * The window that downloads runtimes inside leia (ADR 0080), opened with {@code D} on the start
 * screen: the user checks the runtimes to download, none at first, sees what each one downloads from
 * where, and watches the download, which Esc cancels. It offers only the runtimes that need a
 * download; {@link RuntimeInstall#install} refreshes the others it was planned for without asking.
 * Once the download is done, the backend loads the new runtimes, and the window says what came of it.
 *
 * <p>The runtimes that are there already, loaded, to be loaded on a restart, or refused, are listed
 * too, checked and greyed out, for their jars' URLs and SHA-256s (ADR 0081). They can't be chosen:
 * their plans are kept apart from the install's {@link RuntimeInstall.Plan}, so that no install
 * ever writes their folders, whose jars may be open.
 *
 * <p>The plan and the install run on threads of their own; the install writes into a {@link
 * Progress} that the ticks draw. Everything else is touched on the UI thread only.
 */
public final class RuntimeWindow {

    private static final String CLOSE_KEYS = "Esc close";

    /** The window is at most this wide, borders included. */
    private static final int MAX_WIDTH = 110;

    /** Below this width the details go below the list instead of beside it. */
    private static final int WIDE = 90;

    private static final String CHOOSE_KEYS =
            "␣ check   a all   n none   ↑↓ move   [/] details   ⏎ download   Esc close";
    private static final String VIEW_KEYS = "↑↓ move   [/] details   Esc close";
    private static final String NOTHING_CHECKED = "Nothing checked: Space checks a runtime; Esc closes";
    private static final String[] SPINNER = Spinner.dots().frames();
    private static final Style FAILED = Style.EMPTY.red();
    private static final Style WARNING = Style.EMPTY.yellow();

    private enum Phase {
        PLANNING,
        PLAN_FAILED,
        CHOOSE,
        INSTALLING,
        LOADING,
        DONE
    }

    /** What a key did to the window. */
    public sealed interface Event {}

    /** Nothing for the caller to do. */
    public record Nothing() implements Event {}

    /**
     * Enter: install {@code chosen} of {@code plan}, telling {@code listener} how it goes, until
     * {@code cancelled}. The window shows the progress from now on.
     */
    public record Install(
            RuntimeInstall install,
            RuntimeInstall.Plan plan,
            Set<RuntimeFolders.Known> chosen,
            RuntimeInstall.Listener listener,
            BooleanSupplier cancelled)
            implements Event {}

    /** Esc: close the window. */
    public record Close() implements Event {}

    private final Set<RuntimeFolders.Known> checked = new LinkedHashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Map<String, RuntimeFolder> states = new LinkedHashMap<>();

    private Phase phase = Phase.PLANNING;
    private RuntimeInstall install;
    private RuntimeInstall.Plan plan;
    private List<RuntimeInstall.RuntimePlan> offered = List.of();
    private List<RuntimeInstall.RuntimePlan> present = List.of();
    private String planError;
    private int cursor;
    private int detailsScroll;
    private Optional<String> hint = Optional.empty();
    private Progress progress;
    private List<Line> loadLines = List.of();
    private int ticks;

    /** A window that waits for its plan, with the runtimes' states as {@code getRuntimes} has them. */
    public RuntimeWindow(List<RuntimeFolder> runtimes) {
        runtimes.forEach(runtime -> states.put(runtime.folder(), runtime));
    }

    /**
     * What {@code install} found: the window lets the user choose now. {@code present} are the plans
     * of the runtimes that are there already, which are only shown.
     */
    public void planned(RuntimeInstall install, RuntimeInstall.Plan plan, List<RuntimeInstall.RuntimePlan> present) {
        this.install = install;
        this.plan = plan;
        this.offered = plan.needingDownload();
        this.present = List.copyOf(present);
        phase = Phase.CHOOSE;
    }

    /** Why the runtimes couldn't be planned. */
    public void planFailed(String message) {
        planError = message;
        phase = Phase.PLAN_FAILED;
    }

    /**
     * The install has ended. Returns whether a runtime was installed, so that the backend loads the
     * new runtimes; the window waits for {@link #loaded} or {@link #loadFailed} then.
     */
    public boolean finished(RuntimeInstall.Outcome outcome) {
        if (phase != Phase.INSTALLING) {
            return false;
        }
        progress.finish(outcome);
        boolean installed =
                outcome.results().values().stream().anyMatch(RuntimeInstall.Result.Installed.class::isInstance);
        phase = installed ? Phase.LOADING : Phase.DONE;
        return installed;
    }

    /** What the backend made of the new runtimes; only while the window waits for it. */
    public void loaded(LoadRuntimesResult result) {
        if (phase != Phase.LOADING) {
            return;
        }
        List<Line> lines = new ArrayList<>();
        for (RuntimeFolder runtime : result.runtimes()) {
            boolean fresh = result.loaded().contains(runtime.folder());
            boolean taken = progress.outcome().results().keySet().stream()
                    .anyMatch(known -> known.folder().equals(runtime.folder()));
            if (fresh && runtime.state() == RuntimeState.LOADED) {
                lines.add(Line.styled(
                        "Loaded " + runtime.folder()
                                + runtime.luceneVersion()
                                        .map(v -> " (Lucene " + v + ")")
                                        .orElse(""),
                        Style.EMPTY.green()));
            } else if (runtime.state() == RuntimeState.RESTART) {
                lines.add(Line.styled(
                        runtime.folder() + ": restart leia to load it, since jars are loaded already", WARNING));
            } else if (taken && runtime.state() == RuntimeState.REFUSED) {
                lines.add(Line.styled(
                        "Refused " + runtime.folder() + ": " + runtime.problem().orElse(""), FAILED));
            } else if (taken && runtime.state() == RuntimeState.INCOMPLETE) {
                lines.add(Line.styled(
                        runtime.folder() + " is still incomplete: "
                                + runtime.problem().orElse("") + " is missing",
                        FAILED));
            }
        }
        if (!result.loaded().isEmpty()) {
            lines.add(Line.from("Open the index again (o) to read it with the new runtime."));
        }
        loadLines = lines;
        phase = Phase.DONE;
    }

    /** Why the backend couldn't load the new runtimes. */
    public void loadFailed(String message) {
        if (phase != Phase.LOADING) {
            return;
        }
        loadLines = List.of(Line.styled("Cannot load the runtimes: " + message, FAILED));
        phase = Phase.DONE;
    }

    /** Whether the window shows something that moves: a spinner, or the download's progress. */
    public boolean animating() {
        return phase == Phase.PLANNING || phase == Phase.INSTALLING || phase == Phase.LOADING;
    }

    /** Whether a download runs, which closing the window would leave running. */
    public boolean installing() {
        return phase == Phase.INSTALLING;
    }

    /** A tick: the spinner moves on. */
    public void tick() {
        ticks++;
    }

    public Event handleKey(KeyEvent key) {
        boolean close = key.code() == KeyCode.ESCAPE || key.isChar('q');
        return switch (phase) {
            case CHOOSE -> choose(key);
            case INSTALLING -> {
                // Closed once the install has ended, and the backend has loaded what it installed.
                if (key.code() == KeyCode.ESCAPE) {
                    cancelled.set(true);
                }
                yield new Nothing();
            }
            case PLANNING, PLAN_FAILED, LOADING -> close ? new Close() : new Nothing();
            case DONE -> close || key.code() == KeyCode.ENTER ? new Close() : new Nothing();
        };
    }

    private Event choose(KeyEvent key) {
        hint = Optional.empty();
        if (key.code() == KeyCode.ESCAPE || key.isChar('q')) {
            return new Close();
        }
        if (key.code() == KeyCode.ENTER) {
            return startChosen();
        }
        if (key.isChar(' ') && cursor < rowCount()) {
            toggle();
        } else if (key.isChar('a')) {
            offered.forEach(runtime -> checked.add(runtime.known()));
        } else if (key.isChar('n')) {
            checked.clear();
        } else {
            scrollOrMove(key);
        }
        return new Nothing();
    }

    private Event startChosen() {
        if (checked.isEmpty() && refreshed().isEmpty()) {
            hint = Optional.of(offered.isEmpty() ? "Nothing to download; Esc closes" : NOTHING_CHECKED);
            return new Nothing();
        }
        return start();
    }

    /** Checks or unchecks the runtime at the cursor; a present one has nothing to download. */
    private void toggle() {
        if (cursor < offered.size()) {
            RuntimeFolders.Known known = offered.get(cursor).known();
            if (!checked.remove(known)) {
                checked.add(known);
            }
            return;
        }
        String folder = present.get(cursor - offered.size()).known().folder();
        hint = Optional.of(folder + " is " + stateWord(folder) + "; there is nothing to download for it");
    }

    /** Scrolls the details, or moves the cursor. */
    private void scrollOrMove(KeyEvent key) {
        if (key.isChar(']') || (key.hasShift() && key.code() == KeyCode.DOWN)) {
            detailsScroll++;
        } else if (key.isChar('[') || (key.hasShift() && key.code() == KeyCode.UP)) {
            detailsScroll = Math.max(detailsScroll - 1, 0);
        } else {
            OptionalInt moved = Selection.moved(cursor, rowCount(), key);
            if (moved.isPresent() && moved.getAsInt() != cursor) {
                cursor = moved.getAsInt();
                detailsScroll = 0;
            }
        }
    }

    /** The rows of the list: the offered runtimes, then the present ones. */
    private int rowCount() {
        return offered.size() + present.size();
    }

    /** How a present runtime's row and hint name its state. */
    private String stateWord(String folder) {
        RuntimeFolder state = states.get(folder);
        if (state == null) {
            return "complete";
        }
        return switch (state.state()) {
            case LOADED -> "loaded";
            case REFUSED -> "refused";
            default -> "complete";
        };
    }

    /** The planned runtimes that need no download, which every install refreshes. */
    private List<RuntimeInstall.RuntimePlan> refreshed() {
        return plan.runtimes().stream()
                .filter(runtime -> !runtime.needsDownload())
                .toList();
    }

    /** Starts the install of the checked runtimes, and of those that need no download. */
    private Install start() {
        Set<RuntimeFolders.Known> chosen = Set.copyOf(checked);
        progress = new Progress(plan.runtimes().stream()
                .filter(runtime -> !runtime.needsDownload() || chosen.contains(runtime.known()))
                .toList());
        phase = Phase.INSTALLING;
        return new Install(install, plan, chosen, progress, cancelled::get);
    }

    /** How a failed install thread ends: every chosen runtime failed, for {@code error}. */
    public static RuntimeInstall.Outcome failedOutcome(Set<RuntimeFolders.Known> chosen, RuntimeException error) {
        Map<RuntimeFolders.Known, RuntimeInstall.Result> failed = new LinkedHashMap<>();
        chosen.forEach(known -> failed.put(known, new RuntimeInstall.Result.Failed(String.valueOf(error))));
        return new RuntimeInstall.Outcome(failed);
    }

    /** Draws the window, centred in {@code area}. */
    public void render(Frame frame, Rect area) {
        int width = Math.clamp((long) area.width() - 4, 0, MAX_WIDTH);
        int height = Math.max(area.height() - 2, 0);
        Rect window = Panels.centered(area, width, height);
        frame.renderWidget(Clear.INSTANCE, window);
        Block block = Block.builder()
                .borders(Borders.ALL)
                .title(" Download runtimes ")
                .build();
        Rect inner = block.inner(window);
        frame.renderWidget(block, window);
        if (inner.width() <= 0 || inner.height() <= 0) {
            return;
        }
        List<Line> header = header();
        List<Line> footer = footer(inner.width());
        List<Line> wrappedHeader = Panels.wrap(header, Math.max(inner.width() - 1, 1));
        // Below the header goes the list or the jars, but only once there is a plan.
        int headerHeight = Math.min(
                wrappedHeader.size(),
                phase != Phase.PLANNING && phase != Phase.PLAN_FAILED
                        ? Math.max(inner.height() / 3, 1)
                        : inner.height());
        int footerCap = Math.max(inner.height() / 3, 1);
        int footerHeight = Math.min(footer.size(), footerCap);
        int gaugeHeight = phase == Phase.INSTALLING || phase == Phase.LOADING || phase == Phase.DONE ? 1 : 0;
        List<Rect> rows = Layout.vertical()
                .constraints(
                        Constraint.length(headerHeight),
                        Constraint.length(gaugeHeight),
                        Constraint.fill(),
                        Constraint.length(footerHeight))
                .split(inner);
        frame.renderWidget(Paragraph.from(Text.from(wrappedHeader)), rows.get(0));
        if (phase == Phase.CHOOSE) {
            if (!plan.runtimes().isEmpty() || !present.isEmpty()) {
                renderChoose(frame, rows.get(2));
            }
        } else if (gaugeHeight > 0) {
            frame.renderWidget(gauge(), rows.get(1));
            renderProgress(frame, rows.get(2));
        }
        frame.renderWidget(Paragraph.from(Text.from(footer)), rows.get(3));
    }

    private List<Line> header() {
        return switch (phase) {
            case PLANNING -> List.of(Line.from(SPINNER[ticks % SPINNER.length] + " Reading runtimes/…"));
            case PLAN_FAILED -> List.of(Line.styled("Cannot plan the download: " + planError, FAILED));
            case CHOOSE -> chooseHeader();
            case INSTALLING ->
                List.of(Line.from(Span.styled(
                        cancelled.get()
                                ? "Cancelling…"
                                : "Downloading " + TextFormat.count(progress.jarCount(), "jar") + " into "
                                        + install.runtimesDir(),
                        Style.EMPTY.bold())));
            default -> doneHeader();
        };
    }

    private List<Line> chooseHeader() {
        if (plan.runtimes().isEmpty()) {
            return List.of(Line.from("Every runtime is loaded or complete; there is nothing to download."));
        }
        List<Line> header = new ArrayList<>();
        header.add(Line.from(
                install.source() + ", and checks each against the SHA-256 and size that" + " leia's build recorded."));
        List<RuntimeInstall.RuntimePlan> refreshed = refreshed();
        if (!refreshed.isEmpty()) {
            header.add(Line.from("Need no download, and are installed from leia.jar with them: "
                    + String.join(
                            ", ",
                            refreshed.stream()
                                    .map(runtime -> runtime.known().folder())
                                    .toList())));
        }
        header.add(Line.from(Span.styled(checkedTotal(), Style.EMPTY.bold())));
        return header;
    }

    private List<Line> doneHeader() {
        List<Line> header = new ArrayList<>();
        header.add(Line.from(Span.styled("Done: " + install.runtimesDir(), Style.EMPTY.bold())));
        for (String line : progress.outcome().summary()) {
            header.add(line.contains(": failed: ") ? Line.from(Span.styled(line, FAILED)) : Line.from(line));
        }
        if (phase == Phase.LOADING) {
            header.add(Line.from(SPINNER[ticks % SPINNER.length] + " Loading the new runtimes…"));
        } else {
            header.addAll(loadLines);
        }
        return header;
    }

    private List<Line> footer(int width) {
        return switch (phase) {
            case PLANNING, PLAN_FAILED -> KeyLegend.lines(CLOSE_KEYS, width);
            case CHOOSE -> {
                List<Line> footer = new ArrayList<>();
                hint.ifPresent(text -> footer.add(Line.from(Span.styled(" " + text, WARNING))));
                String keys = CHOOSE_KEYS;
                if (plan.runtimes().isEmpty()) {
                    keys = present.isEmpty() ? CLOSE_KEYS : VIEW_KEYS;
                }
                footer.addAll(KeyLegend.lines(keys, width));
                yield footer;
            }
            case INSTALLING -> KeyLegend.lines("Esc cancel", width);
            default -> KeyLegend.lines("⏎/Esc close", width);
        };
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

    /** A row for each runtime: the offered ones first, then the present ones. */
    private List<String> runtimeTexts() {
        List<String> texts = new ArrayList<>();
        for (RuntimeInstall.RuntimePlan runtime : offered) {
            texts.add((checked.contains(runtime.known()) ? "[x] " : "[ ] ")
                    + runtime.known().name()
                    + " · indexes of " + majors(runtime.known().indexMajors()) + " · "
                    + TextFormat.count(runtime.downloads().size(), "jar") + ", "
                    + TextFormat.size(runtime.downloadSize()));
        }
        for (RuntimeInstall.RuntimePlan runtime : present) {
            String folder = runtime.known().folder();
            String state = states.containsKey(folder) && states.get(folder).state() == RuntimeState.RESTART
                    ? "loads on restart"
                    : stateWord(folder);
            texts.add("[x] " + runtime.known().name() + " · indexes of "
                    + majors(runtime.known().indexMajors()) + " · " + state);
        }
        return texts;
    }

    private void renderChoose(Frame frame, Rect area) {
        Block listBlock = Panels.panel(" Runtimes ", true);
        List<String> texts = runtimeTexts();
        int listWidth = texts.stream().mapToInt(CharWidth::of).max().orElse(0) + 2;
        List<Rect> parts = area.width() >= WIDE
                ? Layout.horizontal()
                        .constraints(Constraint.length(Math.min(listWidth, area.width() / 2)), Constraint.fill())
                        .split(area)
                : Layout.vertical()
                        .constraints(Constraint.length(Math.min(rowCount() + 2, area.height() / 2)), Constraint.fill())
                        .split(area);
        List<Line> rows = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            // A present runtime's row is greyed out: it can't be chosen.
            Style style = i < offered.size() ? Style.EMPTY : Style.EMPTY.dim();
            rows.add(Line.from(Span.styled(
                    texts.get(i), i == cursor ? Panels.highlight(true).patch(style) : style)));
        }
        Rect listInner = listBlock.inner(parts.get(0));
        int listTop = Math.max(cursor - Math.max(listInner.height() - 1, 0), 0);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(rows.subList(Math.min(listTop, rows.size()), rows.size())))
                        .block(listBlock)
                        .build(),
                parts.get(0));
        if (rowCount() == 0) {
            return;
        }
        boolean isOffered = cursor < offered.size();
        RuntimeInstall.RuntimePlan current = isOffered ? offered.get(cursor) : present.get(cursor - offered.size());
        Block detailsBlock = Panels.panel(
                " " + current.known().name() + " · runtimes/" + current.known().folder() + " ", false);
        List<Line> details = Panels.wrap(
                isOffered ? details(current) : presentDetails(current),
                Math.max(detailsBlock.inner(parts.get(1)).width(), 1));
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
        ownJars(runtime, lines);
        return lines;
    }

    /**
     * What a present runtime's folder holds: its state, each Lucene jar with where it comes from, and
     * what comes from leia.jar.
     */
    private List<Line> presentDetails(RuntimeInstall.RuntimePlan runtime) {
        String folder = runtime.known().folder();
        RuntimeFolder state = states.get(folder);
        List<Line> lines = new ArrayList<>();
        if (state != null && state.state() == RuntimeState.LOADED) {
            lines.add(Line.from("Loaded"
                    + state.luceneVersion().map(v -> " (Lucene " + v + ")").orElse("")
                    + ". Its jars are open, so the download leaves runtimes/" + folder + " alone."));
        } else if (state != null && state.state() == RuntimeState.REFUSED) {
            lines.add(Line.styled("Refused: " + state.problem().orElse("") + ".", FAILED));
        } else {
            lines.add(Line.from("Complete; restart leia to load it."));
        }
        lines.add(Line.empty());
        lines.add(Line.from(Span.styled("Lucene jars in runtimes/" + folder + ":", Style.EMPTY.bold())));
        Set<String> mismatched = new LinkedHashSet<>();
        runtime.downloads().forEach(download -> mismatched.add(download.jar().name()));
        for (RuntimeFolders.ListedJar jar : runtime.listed()) {
            Optional<String> mavenPath = jar.mavenPath();
            if (mavenPath.isEmpty()) {
                continue;
            }
            // Unindented, as in the details of an offered runtime.
            lines.add(Line.from("• " + jar.name() + ", " + TextFormat.size(jar.size())));
            if (mismatched.contains(jar.name())) {
                lines.add(Line.styled(
                        "Not there, or doesn't match the list; the loader checks only that it is there", WARNING));
            }
            lines.add(Line.from(install.repository().resolve(mavenPath.get()).toString()));
            lines.add(Line.from(Span.styled("SHA-256 " + jar.sha256(), Style.EMPTY.dim())));
        }
        ownJars(runtime, lines);
        return lines;
    }

    /** Adds what of {@code runtime} comes from leia.jar to {@code lines}. */
    private static void ownJars(RuntimeInstall.RuntimePlan runtime, List<Line> lines) {
        lines.add(Line.empty());
        lines.add(Line.from(Span.styled("From leia.jar:", Style.EMPTY.bold())));
        runtime.ownJars().forEach(jar -> lines.add(Line.from("  " + jar)));
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
        int top = Math.clamp((long) active - inner.height() / 2, 0, Math.max(lines.size() - inner.height(), 0));
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
