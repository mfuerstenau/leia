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
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.IndexCheck;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The check screen (ADR 0022): runs Lucene's {@code CheckIndex} on every index of the open path,
 * with or without a reader. The summary on top says what is broken per index, the output below
 * shows {@code CheckIndex}'s report as it arrives and follows its end.
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
         * and report back with {@link #started} or {@link #failed}.
         */
        record Start(long checkId, OptionalInt level) implements Request {}

        /** A key the index doesn't support, such as {@code l} for a Lucene without levels (ADR 0045). */
        record Unsupported(String what) implements Request {}
    }

    private enum Focus {
        SUMMARY,
        OUTPUT
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

    /** The level the next check runs at. */
    private int level = StartCheckIndexParams.DEFAULT_LEVEL;

    /** The level of the check shown. */
    private int checkedLevel = StartCheckIndexParams.DEFAULT_LEVEL;

    private State state = new Idle();
    private final List<String> lines = new ArrayList<>();
    private Focus focus = Focus.OUTPUT;
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

    /** The check screen of the path opened as {@code path}, with {@code l} and levels if {@code levels}. */
    public Check(String path, boolean levels) {
        this.path = path;
        this.levels = levels;
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
    }

    public Optional<Request> handleKey(KeyEvent key) {
        KeyCode code = key.code();
        if (code == KeyCode.ENTER && !running()) {
            long id = nextId++;
            state = new Running(id, null);
            checkedLevel = level;
            lines.clear();
            summaryScroll = 0;
            outputScroll = 0;
            outputColumn = 0;
            follow = true;
            return Optional.of(new Request.Start(id, levels ? OptionalInt.of(level) : OptionalInt.empty()));
        }
        if (key.isChar('l') && !levels) {
            return Optional.of(new Request.Unsupported("Check level"));
        }
        if (key.isChar('l') && !running()) {
            level = level >= StartCheckIndexParams.MAX_LEVEL ? StartCheckIndexParams.MIN_LEVEL : level + 1;
            return Optional.empty();
        }
        if (code == KeyCode.TAB) {
            focus = focus == Focus.SUMMARY ? Focus.OUTPUT : Focus.SUMMARY;
            return Optional.empty();
        }
        if (focus == Focus.SUMMARY) {
            scrollSummary(key);
        } else {
            scrollOutput(key);
        }
        return Optional.empty();
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
        return running()
                ? "Esc cancel check   Tab panel   ↑↓←→"
                : levels
                        ? "⏎ check   l level: " + level + " " + levelName(level) + "   Tab panel   ↑↓←→"
                        : "⏎ check   Tab panel   ↑↓←→";
    }

    public void render(Frame frame, Rect area) {
        List<Line> summary = summaryLines();
        int maxSummary = Math.max(area.height() * SUMMARY_SHARE / 100, 3);
        // Wrapped, plus the borders.
        int innerWidth = Math.max(area.width() - 2, 1);
        List<Line> wrapped = Panels.wrap(summary, innerWidth);
        int summaryRows = wrapped.size();
        int summaryHeight = Math.min(summaryRows + 2, maxSummary);
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.length(summaryHeight), Constraint.fill())
                .split(area);
        Rect summaryArea = parts.get(0);
        Rect outputArea = parts.get(1);

        // TamboUI scrolls no further than to show the last line at the bottom, so the scroll stops
        // there too (ADR 0034 §10).
        summaryScroll = Math.min(summaryScroll, Math.max(summaryRows - (summaryHeight - 2), 0));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(wrapped))
                        .scroll(summaryScroll)
                        .block(panel(levels ? " Check (" + titleLevel() + ") " : " Check ", focus == Focus.SUMMARY))
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
        outputColumn = Math.min(outputColumn, Math.max(widest - inner, 0));
        List<Line> shown = texts.subList(outputScroll, Math.min(outputScroll + outputRows, texts.size())).stream()
                .map(text -> Line.styled(fromColumn(text, outputColumn), outputStyle(text)))
                .toList();
        frame.renderWidget(
                Paragraph.builder().text(Text.from(shown)).block(block).build(), outputArea);
    }

    private String titleLevel() {
        int shown = state instanceof Idle ? level : checkedLevel;
        return "level " + shown + ", " + levelName(shown);
    }

    private List<Line> summaryLines() {
        return switch (state) {
            case Idle _
            when !levels ->
                List.of(
                        Line.from("Lucene's CheckIndex reads every index of the path and reports what is broken. "
                                + "It writes nothing, and works without a reader too."),
                        Line.from(""),
                        Line.from("⏎ checks every segment; this Lucene has no levels."));
            case Idle _ ->
                List.of(
                        Line.from("Lucene's CheckIndex reads every index of the path and reports what is broken. "
                                + "It writes nothing, and works without a reader too."),
                        Line.from(""),
                        Line.from("⏎ checks at level " + level + " (" + levelName(level)
                                + "); l changes the level: 1 checksums of every file, 2 also the integrity "
                                + "of every part of each segment, 3 also the slow checks."));
            case Running _ ->
                List.of(Line.from("Checking… " + TextFormat.count(lines.size(), "line") + " so far. Esc cancels."));
            case Failed(String error) -> List.of(Line.styled("The check failed: " + error, Style.EMPTY.red()));
            case Stopped(String message) -> List.of(Line.from(message));
            case Done(CheckIndexResult result) -> resultLines(result);
        };
    }

    private List<Line> resultLines(CheckIndexResult result) {
        long bad = result.indexes().stream().filter(index -> !index.clean()).count();
        String took = String.format(Locale.ROOT, "took %.1f s", result.tookMillis() / 1000.0);
        int count = result.indexes().size();
        List<Line> lines = new ArrayList<>();
        lines.add(
                bad == 0
                        ? Line.styled(
                                "OK: no problems found in " + indexCount(count) + " (" + took + ")",
                                Style.EMPTY.green().bold())
                        : Line.styled(
                                "BAD: problems in " + bad + " of " + indexCount(count) + " (" + took + ")",
                                Style.EMPTY.red().bold()));
        boolean several = count > 1;
        for (IndexCheck index : result.indexes()) {
            if (several || !index.clean()) {
                lines.addAll(indexLines(index, several));
            }
        }
        return lines;
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
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(head));
        for (String problem : indexProblems(index)) {
            lines.add(Line.styled("    " + problem, Style.EMPTY.red()));
        }
        for (SegmentCheck segment : index.segments()) {
            if (segment.error().isPresent()) {
                lines.add(Line.styled(
                        "    " + segment.name() + " (" + TextFormat.count(segment.maxDoc(), "doc") + ", "
                                + TextFormat.size(segment.sizeBytes()) + "): "
                                + segment.error().get(),
                        Style.EMPTY.red()));
            }
        }
        return lines;
    }

    /** What the summary line of an index says. */
    static String indexSummary(IndexCheck index) {
        if (index.error().isPresent()) {
            return "could not be checked";
        }
        if (index.segmentsFile().isEmpty()) {
            return "no readable commit";
        }
        String file = index.segmentsFile().get();
        String segments = TextFormat.count(index.segmentCount(), "segment");
        if (index.badSegments() > 0) {
            return file + ", " + index.badSegments() + " of " + segments + " broken, "
                    + TextFormat.count(index.lostDocs(), "document") + " would be lost";
        }
        return index.clean() ? file + ", " + segments + ", no problems" : file + ", " + segments;
    }

    /** What is wrong with an index besides its broken segments. */
    static List<String> indexProblems(IndexCheck index) {
        if (index.error().isPresent()) {
            return List.of(index.error().get());
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
