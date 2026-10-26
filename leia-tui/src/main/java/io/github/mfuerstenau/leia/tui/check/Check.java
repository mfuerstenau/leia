package io.github.mfuerstenau.leia.tui.check;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.CheckTarget;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetIndex;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetSegment;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsResult;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The check screen (ADR 0022, ADR 0093): runs Lucene's {@code CheckIndex} on the indexes of the open
 * path, with or without a reader. The panel on top lists each index with the segments of its latest
 * commit, to mark what to check (everything if nothing is marked), and says what is broken once a
 * check is done; the output below shows {@code CheckIndex}'s report as it arrives and follows its
 * end.
 *
 * <p>Each check has an id, which its start, its polls and its replies carry: a reply for a check
 * that was stopped or replaced is dropped, and a job that starts for one is cancelled. Esc stops the
 * check at once (ADR 0034 §10).
 */
public final class Check {

    /** The summary takes at most this share of the screen, in percent. */
    private static final int SUMMARY_SHARE = 50;

    private static final int PAGE = 10;

    /** How far ←/→ scroll the output sideways. */
    private static final int COLUMN_STEP = 20;

    /** What the caller has to do for the check screen. */
    public sealed interface Request {

        /**
         * Start check {@code checkId} at this level, or at the only one of a Lucene without levels,
         * on this many threads, or all the processors, of the indexes and segments of {@code only},
         * or of everything, and report back with {@link #started} or {@link #failed}.
         */
        record Start(long checkId, OptionalInt level, OptionalInt threads, Optional<List<CheckTarget>> only)
                implements Request {}

        /** Fetch the indexes with their segments, and report back with {@link #targetsLoaded} or {@link #targetsFailed}. */
        record LoadTargets() implements Request {}

        /** A key the index doesn't support, such as {@code l} for a Lucene without levels (ADR 0045). */
        record Unsupported(String what) implements Request {}
    }

    private enum Focus {
        TARGETS,
        OUTPUT
    }

    /** The indexes and segments to mark. */
    private sealed interface Targets {}

    private record NotLoaded() implements Targets {}

    private record Loading() implements Targets {}

    private record Loaded(List<CheckTargetIndex> indexes) implements Targets {}

    private record TargetsFailed(String error) implements Targets {}

    /** A line of the list: the row it belongs to, or -1 for a line above the rows. */
    private record Entry(Line line, int row) {}

    /** A row of the list: an index, or one of its segments (-1 for the index itself). */
    private record Row(int index, int segment) {}

    /** How much of an index is marked. */
    private enum Mark {
        NONE("[ ]"),
        SOME("[-]"),
        ALL("[x]");

        private final String box;

        Mark(String box) {
            this.box = box;
        }
    }

    private sealed interface State {}

    /** No check yet. */
    private record Idle() implements State {}

    /**
     * Asked to start.
     *
     * @param jobId null until the backend has started the job
     */
    private record Running(long id, Long jobId) implements State {}

    private record Done(CheckIndexResult result) implements State {}

    private record Failed(String error) implements State {}

    /** Esc, or a reopen that stopped the backend's jobs. */
    private record Stopped(String message) implements State {}

    /** The opened path, which the index directories are shown relative to. */
    private final String path;

    /**
     * Whether the check has levels, as the feature {@code CHECK_LEVELS} says: Lucene 10 and 5.5 have
     * them, Lucene 3.6 doesn't (ADR 0045, ADR 0046).
     */
    private final boolean levels;

    /**
     * Whether the check has threads, as the feature {@code CHECK_THREADS} says: Lucene 9 and 10 have
     * them (ADR 0093).
     */
    private final boolean threadsSupported;

    /** The threads of the JVM that checks, which {@code t} cycles up to. */
    private final int processors;

    /** The threads the next check runs on. */
    private int threads;

    /** The threads of the check shown. */
    private int checkedThreads;

    private Targets targets = new NotLoaded();

    /** The marked segments by index path, for the indexes that have segments. */
    private final Map<String, Set<String>> markedSegments = new HashMap<>();

    /** The marked indexes that have no segments to mark, by path. */
    private final Set<String> markedWhole = new HashSet<>();

    /** The row the cursor is on. */
    private int cursor;

    /** The level the next check runs at. */
    private int level = StartCheckIndexParams.DEFAULT_LEVEL;

    /** The level of the check shown. */
    private int checkedLevel = StartCheckIndexParams.DEFAULT_LEVEL;

    private State state = new Idle();
    private final List<String> lines = new ArrayList<>();
    private Focus focus = Focus.TARGETS;
    private int summaryScroll;

    /** The first output line shown. */
    private int outputScroll;

    /** Whether the output shows its end as lines arrive. */
    private boolean follow = true;

    /** The first column of the output shown, for lines wider than the panel. */
    private int outputColumn;

    /** How many output lines the last frame showed, for paging and following. */
    private int outputRows = 1;

    /** The id of the next check. */
    private long nextId = 1;

    /**
     * The check screen of the path opened as {@code path}, with {@code l} and levels if {@code levels},
     * and {@code t} and threads if {@code threads}.
     */
    public Check(String path, boolean levels, boolean threads) {
        this(path, levels, threads, Runtime.getRuntime().availableProcessors());
    }

    /** As {@link #Check(String, boolean, boolean)}, for a JVM with {@code processors}. */
    Check(String path, boolean levels, boolean threads, int processors) {
        this.path = path;
        this.levels = levels;
        this.threadsSupported = threads;
        this.processors = Math.max(processors, 1);
        this.threads = this.processors;
        this.checkedThreads = this.processors;
    }

    /** Fetches the indexes with their segments, the first time the screen is shown. */
    public Optional<Request> initialRequest() {
        if (targets instanceof NotLoaded) {
            targets = new Loading();
            return Optional.of(new Request.LoadTargets());
        }
        return Optional.empty();
    }

    /** Shows the indexes and segments, keeping the marks of those that are still there. */
    public void targetsLoaded(GetCheckTargetsResult result) {
        targets = new Loaded(result.indexes());
        Map<String, Set<String>> segmentNames = new HashMap<>();
        for (CheckTargetIndex index : result.indexes()) {
            segmentNames.put(
                    index.path(),
                    index.segments().stream()
                            .map(CheckTargetSegment::name)
                            .collect(java.util.stream.Collectors.toSet()));
        }
        markedSegments.keySet().removeIf(index -> !segmentNames.containsKey(index));
        markedSegments.forEach((index, names) -> names.retainAll(segmentNames.get(index)));
        markedWhole.removeIf(index ->
                !segmentNames.containsKey(index) || !segmentNames.get(index).isEmpty());
        cursor = Math.clamp(cursor, 0, Math.max(rows().size() - 1, 0));
    }

    public void targetsFailed(String error) {
        targets = new TargetsFailed(error);
        markedSegments.clear();
        markedWhole.clear();
    }

    public boolean running() {
        return state instanceof Running;
    }

    /** The job to poll, once the backend has started it: its check id and job id. */
    public Optional<long[]> job() {
        return state instanceof Running(long id, Long jobId) && jobId != null
                ? Optional.of(new long[] {id, jobId})
                : Optional.empty();
    }

    /** The first line the next poll asks for: the lines there are. */
    public int fromLine() {
        return lines.size();
    }

    /**
     * The job of check {@code checkId} has started. If that check was stopped meanwhile, returns the
     * job to cancel.
     */
    public OptionalLong started(long checkId, long jobId) {
        if (state instanceof Running(long id, Long job) && id == checkId && job == null) {
            state = new Running(id, jobId);
            return OptionalLong.empty();
        }
        return OptionalLong.of(jobId);
    }

    /** Adds the new lines of check {@code checkId}, and shows the outcome once it is there. */
    public void progress(long checkId, GetCheckIndexResult result) {
        if (!isRunning(checkId)) {
            return;
        }
        lines.addAll(result.lines());
        result.result().ifPresent(outcome -> state = new Done(outcome));
    }

    public void failed(long checkId, String error) {
        if (isRunning(checkId)) {
            state = new Failed(error);
        }
    }

    private boolean isRunning(long checkId) {
        return state instanceof Running(long id, Long _) && id == checkId;
    }

    /**
     * Esc: stops the running check at once. Returns its job to cancel, if it has started; a job that
     * starts later is cancelled then.
     */
    public OptionalLong cancel() {
        if (!(state instanceof Running(long _, Long jobId))) {
            return OptionalLong.empty();
        }
        state = new Stopped("Cancelled.");
        return jobId == null ? OptionalLong.empty() : OptionalLong.of(jobId);
    }

    /** A reopen stopped the backend's jobs, the running check among them. */
    public void stoppedByReopen() {
        if (running()) {
            state = new Stopped("Stopped: the index was reopened.");
        }
        // The segments may have changed: the next showing fetches them again.
        targets = new NotLoaded();
    }

    public Optional<Request> handleKey(KeyEvent key) {
        KeyCode code = key.code();
        if (code == KeyCode.ENTER && !running()) {
            long id = nextId++;
            state = new Running(id, null);
            checkedLevel = level;
            checkedThreads = threads;
            lines.clear();
            summaryScroll = 0;
            outputScroll = 0;
            outputColumn = 0;
            follow = true;
            // The arrow keys scroll the report from now on.
            focus = Focus.OUTPUT;
            return Optional.of(new Request.Start(
                    id,
                    levels ? OptionalInt.of(level) : OptionalInt.empty(),
                    threadsSupported ? OptionalInt.of(threads) : OptionalInt.empty(),
                    only()));
        }
        if (key.isChar('l') && !levels) {
            return Optional.of(new Request.Unsupported("Check level"));
        }
        if (key.isChar('l') && !running()) {
            level = level >= StartCheckIndexParams.MAX_LEVEL ? StartCheckIndexParams.MIN_LEVEL : level + 1;
            return Optional.empty();
        }
        if (key.isChar('t') && !threadsSupported) {
            return Optional.of(new Request.Unsupported("Check threads"));
        }
        if (key.isChar('t') && !running()) {
            threads = nextThreads(threads, processors);
            return Optional.empty();
        }
        if (key.isChar('r') && !running() && !(targets instanceof Loading)) {
            targets = new Loading();
            return Optional.of(new Request.LoadTargets());
        }
        if (code == KeyCode.TAB) {
            focus = focus == Focus.TARGETS ? Focus.OUTPUT : Focus.TARGETS;
            return Optional.empty();
        }
        if (focus == Focus.TARGETS) {
            if (rows().isEmpty()) {
                scrollSummary(key);
            } else {
                targetsKey(key);
            }
        } else {
            scrollOutput(key);
        }
        return Optional.empty();
    }

    /** 1, 2, 4, 8, … up to {@code processors}, then 1 again. */
    static int nextThreads(int threads, int processors) {
        return threads >= processors ? 1 : Math.min(threads * 2, processors);
    }

    /** Moves the cursor, and marks with Space and {@code a}, unless a check runs. */
    private void targetsKey(KeyEvent key) {
        List<Row> rows = rows();
        int last = rows.size() - 1;
        switch (key.code()) {
            case DOWN -> cursor++;
            case UP -> cursor--;
            case PAGE_DOWN -> cursor += PAGE;
            case PAGE_UP -> cursor -= PAGE;
            case HOME -> cursor = 0;
            case END -> cursor = last;
            case CHAR -> charKey(key.codePoint(), rows);
            default -> {
                // Not a key of the list.
            }
        }
        cursor = Math.clamp(cursor, 0, last);
    }

    /** The character keys of the list; Space is one too. */
    private void charKey(int codePoint, List<Row> rows) {
        int last = rows.size() - 1;
        switch (codePoint) {
            case 'j' -> cursor++;
            case 'k' -> cursor--;
            case 'g' -> cursor = 0;
            case 'G' -> cursor = last;
            case ' ' -> {
                if (!running()) {
                    toggle(rows.get(Math.clamp(cursor, 0, last)));
                }
            }
            case 'a' -> {
                if (!running()) {
                    toggleAll();
                }
            }
            default -> {
                // Not a key of the list.
            }
        }
    }

    /** The rows of the list: each index, then its segments. */
    private List<Row> rows() {
        if (!(targets instanceof Loaded(List<CheckTargetIndex> indexes))) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < indexes.size(); i++) {
            rows.add(new Row(i, -1));
            for (int s = 0; s < indexes.get(i).segments().size(); s++) {
                rows.add(new Row(i, s));
            }
        }
        return rows;
    }

    private List<CheckTargetIndex> indexes() {
        return targets instanceof Loaded(List<CheckTargetIndex> indexes) ? indexes : List.of();
    }

    private boolean isMarked(CheckTargetIndex index, String segment) {
        return markedSegments.getOrDefault(index.path(), Set.of()).contains(segment);
    }

    private Mark mark(CheckTargetIndex index) {
        if (index.segments().isEmpty()) {
            return markedWhole.contains(index.path()) ? Mark.ALL : Mark.NONE;
        }
        long marked = index.segments().stream()
                .filter(segment -> isMarked(index, segment.name()))
                .count();
        if (marked == 0) {
            return Mark.NONE;
        }
        return marked == index.segments().size() ? Mark.ALL : Mark.SOME;
    }

    private boolean anyMarked() {
        return indexes().stream().anyMatch(index -> mark(index) != Mark.NONE);
    }

    /** Space: an index with all its segments, or one segment. */
    private void toggle(Row row) {
        CheckTargetIndex index = indexes().get(row.index());
        if (row.segment() >= 0) {
            String name = index.segments().get(row.segment()).name();
            Set<String> marked = markedSegments.computeIfAbsent(index.path(), _ -> new LinkedHashSet<>());
            if (!marked.remove(name)) {
                marked.add(name);
            }
        } else {
            setIndex(index, mark(index) != Mark.ALL);
        }
    }

    private void setIndex(CheckTargetIndex index, boolean marked) {
        if (index.segments().isEmpty()) {
            if (marked) {
                markedWhole.add(index.path());
            } else {
                markedWhole.remove(index.path());
            }
        } else if (marked) {
            markedSegments.put(
                    index.path(),
                    index.segments().stream()
                            .map(CheckTargetSegment::name)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        } else {
            markedSegments.remove(index.path());
        }
    }

    /** {@code a}: marks everything, or clears the marks if everything is marked. */
    private void toggleAll() {
        boolean all = indexes().stream().allMatch(index -> mark(index) == Mark.ALL);
        indexes().forEach(index -> setIndex(index, !all));
    }

    /**
     * What to check: the marked segments of the indexes with marks, an index whose every segment is
     * marked as a whole; absent if nothing is marked, which checks everything.
     */
    private Optional<List<CheckTarget>> only() {
        List<CheckTarget> only = new ArrayList<>();
        for (CheckTargetIndex index : indexes()) {
            switch (mark(index)) {
                case ALL -> only.add(new CheckTarget(index.path(), Optional.empty()));
                case SOME ->
                    only.add(new CheckTarget(
                            index.path(),
                            Optional.of(index.segments().stream()
                                    .map(CheckTargetSegment::name)
                                    .filter(name -> isMarked(index, name))
                                    .toList())));
                case NONE -> {
                    // Not checked.
                }
            }
        }
        return only.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(only));
    }

    private void scrollSummary(KeyEvent key) {
        summaryScroll = switch (key.code()) {
            case DOWN -> summaryScroll + 1;
            case UP -> summaryScroll - 1;
            case PAGE_DOWN -> summaryScroll + PAGE;
            case PAGE_UP -> summaryScroll - PAGE;
            case HOME -> 0;
            case END -> Integer.MAX_VALUE;
            case CHAR ->
                switch (key.codePoint()) {
                    case 'j' -> summaryScroll + 1;
                    case 'k' -> summaryScroll - 1;
                    case 'g' -> 0;
                    case 'G' -> Integer.MAX_VALUE;
                    default -> summaryScroll;
                };
            default -> summaryScroll;
        };
        summaryScroll = Math.max(summaryScroll, 0);
    }

    private void scrollOutput(KeyEvent key) {
        int page = Math.max(outputRows, 1);
        int last = lastOutputScroll();
        int scroll;
        switch (key.code()) {
            case DOWN -> scroll = outputScroll + 1;
            case UP -> scroll = outputScroll - 1;
            case PAGE_DOWN -> scroll = outputScroll + page;
            case PAGE_UP -> scroll = outputScroll - page;
            case HOME -> scroll = 0;
            case END -> scroll = last;
            case RIGHT -> {
                outputColumn += COLUMN_STEP;
                return;
            }
            case LEFT -> {
                outputColumn = Math.max(outputColumn - COLUMN_STEP, 0);
                return;
            }
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'j' -> scroll = outputScroll + 1;
                    case 'k' -> scroll = outputScroll - 1;
                    case 'g' -> scroll = 0;
                    case 'G' -> scroll = last;
                    default -> {
                        return;
                    }
                }
            }
            default -> {
                return;
            }
        }
        outputScroll = Math.clamp(scroll, 0, last);
        follow = outputScroll >= last;
    }

    /** The scroll that shows the last line at the bottom. */
    private int lastOutputScroll() {
        return Math.max(lines.size() - outputRows, 0);
    }

    /** The keys of the screen, for the footer. */
    public String keys() {
        if (running()) {
            return "Esc cancel check   Tab panel   ↑↓←→";
        }
        // The title says the level and the threads.
        return "⏎ check " + (anyMarked() ? "marked" : "all") + "   Space mark   a all"
                + (levels ? "   l level" : "")
                + (threadsSupported ? "   t threads" : "")
                + "   Tab panel";
    }

    public void render(Frame frame, Rect area) {
        List<Entry> summary = summaryEntries();
        int maxSummary = Math.max(area.height() * SUMMARY_SHARE / 100, 3);
        // Wrapped, plus the borders.
        int innerWidth = Math.max(area.width() - 2, 1);
        List<Line> wrapped = new ArrayList<>();
        int cursorLine = -1;
        int cursorEntry = rows().isEmpty() ? -1 : Math.clamp(cursor, 0, rows().size() - 1);
        for (Entry entry : summary) {
            if (entry.row() == cursorEntry && cursorLine < 0) {
                cursorLine = wrapped.size();
            }
            Line line = entry.row() == cursorEntry && focus == Focus.TARGETS
                    ? entry.line().patchStyle(Panels.highlight(true))
                    : entry.line();
            wrapped.addAll(Panels.wrap(List.of(line), innerWidth));
        }
        int summaryRows = wrapped.size();
        int summaryHeight = Math.min(summaryRows + 2, maxSummary);
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.length(summaryHeight), Constraint.fill())
                .split(area);
        Rect summaryArea = parts.get(0);
        Rect outputArea = parts.get(1);

        int visible = summaryHeight - 2;
        if (cursorLine >= 0 && focus == Focus.TARGETS) {
            // The cursor stays in view.
            summaryScroll = Math.clamp(summaryScroll, cursorLine - visible + 1, cursorLine);
        }
        // TamboUI scrolls no further than to show the last line at the bottom, so the scroll stops
        // there too (ADR 0034 §10).
        summaryScroll = Math.clamp(summaryScroll, 0, Math.max(summaryRows - visible, 0));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(wrapped))
                        .scroll(summaryScroll)
                        .block(panel(" Check" + title() + " ", focus == Focus.TARGETS))
                        .build(),
                summaryArea);

        outputRows = Math.max(outputArea.height() - 2, 1);
        if (follow) {
            outputScroll = lastOutputScroll();
        }
        outputScroll = Math.min(outputScroll, lastOutputScroll());
        String title = lines.isEmpty()
                ? " Output "
                : " Output: lines " + (outputScroll + 1) + "–" + Math.min(outputScroll + outputRows, lines.size())
                        + " of " + lines.size() + " ";
        Block block = panel(title, focus == Focus.OUTPUT);
        int inner = block.inner(outputArea).width();
        List<String> texts = lines.stream().map(Check::outputText).toList();
        int widest = texts.stream()
                .mapToInt(text -> text.codePointCount(0, text.length()))
                .max()
                .orElse(0);
        // TamboUI's Paragraph doesn't scroll sideways, so the lines are cut here.
        outputColumn = Math.clamp(outputColumn, 0, Math.max(widest - inner, 0));
        List<Line> shown = texts.subList(outputScroll, Math.min(outputScroll + outputRows, texts.size())).stream()
                .map(text -> Line.styled(fromColumn(text, outputColumn), outputStyle(text)))
                .toList();
        frame.renderWidget(
                Paragraph.builder().text(Text.from(shown)).block(block).build(), outputArea);
    }

    /** The level and threads of the check, in the panel's title: {@code (level 2, integrity · 4 threads)}. */
    private String title() {
        List<String> parts = new ArrayList<>();
        if (levels) {
            int shown = state instanceof Idle ? level : checkedLevel;
            parts.add("level " + shown + ", " + levelName(shown));
        }
        if (threadsSupported) {
            parts.add(TextFormat.count(state instanceof Idle ? threads : checkedThreads, "thread"));
        }
        return parts.isEmpty() ? "" : " (" + String.join(" · ", parts) + ")";
    }

    private List<Entry> summaryEntries() {
        List<Entry> entries = new ArrayList<>();
        Map<String, IndexCheck> checked = new HashMap<>();
        Set<String> listed = new HashSet<>();
        for (Line line : headlines()) {
            entries.add(new Entry(line, -1));
        }
        if (state instanceof Done(CheckIndexResult result)) {
            result.indexes().forEach(index -> checked.put(index.path(), index));
        }
        List<Row> rows = rows();
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            CheckTargetIndex index = indexes().get(row.index());
            IndexCheck outcome = checked.get(index.path());
            if (row.segment() < 0) {
                entries.add(new Entry(indexRow(index, outcome), r));
                if (outcome != null) {
                    listed.add(index.path());
                    addProblems(entries, outcome, r);
                }
            } else {
                CheckTargetSegment segment = index.segments().get(row.segment());
                SegmentCheck result = outcome == null
                        ? null
                        : outcome.segments().stream()
                                .filter(candidate -> candidate.name().equals(segment.name()))
                                .findFirst()
                                .orElse(null);
                entries.add(new Entry(segmentRow(index, segment, result), r));
                if (result != null && result.error().isPresent()) {
                    entries.add(
                            new Entry(Line.styled("        " + result.error().get(), Style.EMPTY.red()), r));
                }
            }
        }
        // What was checked but isn't listed, such as an index that came after the list was fetched.
        if (state instanceof Done(CheckIndexResult result)) {
            boolean several = result.indexes().size() > 1;
            for (IndexCheck index : result.indexes()) {
                if (!listed.contains(index.path()) && (several || !index.clean())) {
                    indexLines(index, several).forEach(line -> entries.add(new Entry(line, -1)));
                }
            }
        }
        return entries;
    }

    /** The row of an index, with its marks and, once checked, whether it passed. */
    private Line indexRow(CheckTargetIndex index, IndexCheck outcome) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw(mark(index).box + " "));
        if (outcome != null) {
            spans.add(outcome.clean() ? Span.styled("✓ ", Style.EMPTY.green()) : Span.styled("✗ ", Style.EMPTY.red()));
        }
        spans.add(Span.styled(Overview.relative(path, index.path()), Style.EMPTY.bold()));
        if (index.skipped()) {
            spans.add(Span.raw(" (skipped when opened)"));
        }
        spans.add(Span.raw(index.error()
                .map(error -> ": " + error)
                .orElseGet(() -> index.segmentsFile()
                        .map(file -> "  " + file + ", "
                                + TextFormat.count(index.segments().size(), "segment"))
                        .orElse(""))));
        return Line.from(spans);
    }

    private Line segmentRow(CheckTargetIndex index, CheckTargetSegment segment, SegmentCheck outcome) {
        List<Span> spans = new ArrayList<>();
        spans.add(Span.raw("    " + (isMarked(index, segment.name()) ? Mark.ALL : Mark.NONE).box + " "));
        if (outcome != null) {
            spans.add(
                    outcome.error().isEmpty()
                            ? Span.styled("✓ ", Style.EMPTY.green())
                            : Span.styled("✗ ", Style.EMPTY.red()));
        }
        spans.add(Span.styled(segment.name(), Style.EMPTY.bold()));
        spans.add(Span.raw("  " + TextFormat.count(segment.maxDoc(), "doc")
                + segment.sizeBytes().stream()
                        .mapToObj(size -> ", " + TextFormat.size(size))
                        .findFirst()
                        .orElse("")));
        return Line.from(spans);
    }

    /** What is wrong with a checked index besides its broken segments, under its row. */
    private static void addProblems(List<Entry> entries, IndexCheck outcome, int row) {
        for (String problem : indexProblems(outcome)) {
            entries.add(new Entry(Line.styled("    " + problem, Style.EMPTY.red()), row));
        }
    }

    /** The lines above the list: what the screen does, or how the check went. */
    private List<Line> headlines() {
        String listing = switch (targets) {
            case NotLoaded _ -> "";
            case Loading _ -> "Listing the segments…";
            case TargetsFailed(String error) -> "Cannot list the segments: " + error + " (r tries again)";
            case Loaded _ -> "";
        };
        List<Line> shown = new ArrayList<>(
                switch (state) {
                    case Idle _ -> idleLines();
                    case Running _ ->
                        List.of(Line.from(
                                "Checking… " + TextFormat.count(lines.size(), "line") + " so far. Esc cancels."));
                    case Failed(String error) -> List.of(Line.styled("The check failed: " + error, Style.EMPTY.red()));
                    case Stopped(String message) -> List.of(Line.from(message));
                    case Done(CheckIndexResult result) -> headline(result);
                });
        if (!listing.isEmpty()) {
            shown.add(Line.from(listing));
        }
        return shown;
    }

    private List<Line> idleLines() {
        return List.of(
                Line.from("Lucene's CheckIndex reports what is broken; it writes nothing, and works without a reader. "
                        + (levels ? "Levels: 1 checksums, 2 integrity, 3 slow." : "This Lucene has no levels.")));
    }

    /** The headline of a check. */
    private List<Line> headline(CheckIndexResult result) {
        long bad = result.indexes().stream().filter(index -> !index.clean()).count();
        String took = String.format(Locale.ROOT, "took %.1f s", result.tookMillis() / 1000.0);
        int count = result.indexes().size();
        List<Line> shown = new ArrayList<>();
        shown.add(
                bad == 0
                        ? Line.styled(
                                "OK: no problems found in " + indexCount(count) + " (" + took + ")",
                                Style.EMPTY.green().bold())
                        : Line.styled(
                                "BAD: problems in " + bad + " of " + indexCount(count) + " (" + took + ")",
                                Style.EMPTY.red().bold()));
        return shown;
    }

    private List<Line> indexLines(IndexCheck index, boolean named) {
        List<Span> head = new ArrayList<>();
        head.add(index.clean() ? Span.styled("✓ ", Style.EMPTY.green()) : Span.styled("✗ ", Style.EMPTY.red()));
        if (named) {
            head.add(Span.styled(Overview.relative(path, index.path()), Style.EMPTY.bold()));
            if (index.skipped()) {
                head.add(Span.raw(" (skipped when opened)"));
            }
            head.add(Span.raw(": "));
        }
        head.add(Span.raw(indexSummary(index)));
        List<Line> shown = new ArrayList<>();
        shown.add(Line.from(head));
        for (String problem : indexProblems(index)) {
            shown.add(Line.styled("    " + problem, Style.EMPTY.red()));
        }
        for (SegmentCheck segment : index.segments()) {
            segment.error()
                    .ifPresent(error -> shown.add(Line.styled(
                            "    " + segment.name() + " (" + TextFormat.count(segment.maxDoc(), "doc") + ", "
                                    + TextFormat.size(segment.sizeBytes()) + "): " + error,
                            Style.EMPTY.red())));
        }
        return shown;
    }

    /** What the summary line of an index says. */
    static String indexSummary(IndexCheck index) {
        if (index.error().isPresent()) {
            return "could not be checked";
        }
        Optional<String> segmentsFile = index.segmentsFile();
        if (segmentsFile.isEmpty()) {
            return "no readable commit";
        }
        String file = segmentsFile.get();
        String segments = TextFormat.count(index.segmentCount(), "segment");
        if (index.badSegments() > 0) {
            return file + ", " + index.badSegments() + " of " + segments + " broken, "
                    + TextFormat.count(index.lostDocs(), "document") + " would be lost";
        }
        return index.clean() ? file + ", " + segments + ", no problems" : file + ", " + segments;
    }

    /** What is wrong with an index besides its broken segments. */
    static List<String> indexProblems(IndexCheck index) {
        Optional<String> error = index.error();
        if (error.isPresent()) {
            return List.of(error.get());
        }
        List<String> problems = new ArrayList<>();
        if (index.missingSegments()) {
            problems.add("A commit can't be read: see the output for why.");
        }
        if (index.toolOutOfDate()) {
            problems.add("The index was written by a newer Lucene than leia's.");
        }
        if (!index.validCounter() && !index.missingSegments()) {
            problems.add("The commit's counter for segment names is not above every segment's name.");
        }
        if (!index.segmentsNotFound().isEmpty()) {
            problems.add("Not in the latest commit: " + String.join(", ", index.segmentsNotFound()));
        }
        return problems;
    }

    /** {@code 1 index}, {@code 3 indexes}. */
    private static String indexCount(int n) {
        return n == 1 ? "1 index" : n + " indexes";
    }

    public static String levelName(int level) {
        return switch (level) {
            case 1 -> "checksums";
            case 2 -> "integrity";
            default -> "slow";
        };
    }

    /** An output line: tabs as spaces, other control characters dropped. */
    static String outputText(String line) {
        StringBuilder text = new StringBuilder(line.length());
        line.codePoints().forEach(c -> {
            if (c == '\t') {
                text.append("    ");
            } else if (!Character.isISOControl(c)) {
                text.appendCodePoint(c);
            }
        });
        return text.toString();
    }

    /** Errors in red, warnings in yellow. */
    static Style outputStyle(String text) {
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("ERROR") || text.contains("FAILED")) {
            return Style.EMPTY.red();
        }
        return trimmed.startsWith("WARNING") ? Style.EMPTY.yellow() : Style.EMPTY;
    }

    /** {@code text} from its code point {@code column} on. */
    private static String fromColumn(String text, int column) {
        int length = text.codePointCount(0, text.length());
        return column >= length ? "" : text.substring(text.offsetByCodePoints(0, column));
    }
}
