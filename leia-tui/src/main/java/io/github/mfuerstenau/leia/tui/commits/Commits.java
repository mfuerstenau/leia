package io.github.mfuerstenau.leia.tui.commits;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.highlight;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.panel;
import static io.github.mfuerstenau.leia.tui.widgets.Panels.right;

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
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.commits.CodecFormats;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.CommitFile;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.commits.UnreadableCommit;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import io.github.mfuerstenau.leia.tui.widgets.Selection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The commits screen: the commits of the index directory on top and, below, the segments of the
 * selected commit next to the details of the selected commit or segment (ADR 0009). Both come from
 * the directory, so {@code r} reads them again to show commits written since. With several index
 * directories, a panel above chooses whose commits are shown (ADR 0021).
 *
 * <p>The Rust TUI got each reply before the next key; here a reply can come after the user moved
 * on. So a fetch that is under way is not asked for again. A reply can't be stale, though: the
 * backend answers its calls one by one and in order, and a new {@code Commits} is only made after
 * an open, reopen or close, which come after every earlier call.
 */
public final class Commits {

    /** The commits panel shows at most this many rows before it scrolls. */
    private static final int COMMIT_ROWS = 6;

    /** The indexes panel shows at most this many rows before it scrolls. */
    private static final int INDEX_ROWS = 4;

    private static final int COLUMN_SPACING = 2;

    /** What a segment written before Lucene 4 or 5 shows for its codec or id (ADR 0045). */
    private static final String NONE = "–";

    /** Keys in the details are padded to the longest one, up to this width. */
    private static final int KEY_WIDTH = 28;

    /** What the caller has to fetch for the commits screen. */
    public sealed interface Request {

        /**
         * The commits of the index directory at this position, reported back with {@link
         * #commitsLoaded} or {@link #commitsFailed}.
         */
        record LoadList(int index) implements Request {}

        /**
         * One commit of the index directory at this position whole, by generation, reported back
         * with {@link #commitLoaded} or {@link #commitFailed}.
         */
        record LoadCommit(int index, long generation) implements Request {}
    }

    /** What a fetch brought: the value, or why there is none. */
    private sealed interface Fetched<T> {

        record Done<T>(T value) implements Fetched<T> {}

        record Failed<T>(String error) implements Fetched<T> {}
    }

    private enum Focus {
        /** Only with several index directories. */
        INDEXES,
        COMMITS,
        SEGMENTS,
        DETAILS
    }

    /** A commit of an index directory. */
    private record CommitKey(int index, long generation) {}

    /** The index directories as shown: below the opened path. */
    private final List<String> indexes;

    /** The index directory whose commits are shown. */
    private int indexRow;

    private final TableState indexTable = new TableState();

    /** The commits of each index directory; null until they are fetched. */
    private final List<Fetched<List<Commit>>> lists;

    /** The {@code segments_N} files of each index directory that could not be read. */
    private final List<List<UnreadableCommit>> unreadable;

    /** The selected row of the commits panel: a commit, or after them an unreadable one. */
    private int commitRow;

    private final TableState commitTable = new TableState();

    /** The commits fetched whole, or why they could not be. */
    private final Map<CommitKey, Fetched<GetCommitResult>> details = new HashMap<>();

    /** The fetches that are under way. */
    private final Set<Request> pending = new HashSet<>();

    private int segmentRow;
    private final TableState segmentTable = new TableState();
    private Focus focus = Focus.COMMITS;

    /**
     * Whether the details show the selected segment rather than the selected commit: the one of the
     * two that had the focus last.
     */
    private boolean segmentDetails;

    private int detailsScroll;

    /** The commits screen of these index directories, as shown; at least one. */
    public Commits(List<String> indexes) {
        this.indexes = List.copyOf(indexes);
        this.lists = new ArrayList<>();
        this.unreadable = new ArrayList<>();
        for (int i = 0; i < indexes.size(); i++) {
            lists.add(null);
            unreadable.add(List.of());
        }
    }

    /** What to fetch when the screen is shown. */
    public Optional<Request> initialRequest() {
        return nextRequest();
    }

    /**
     * The commit list first, then the selected commit whole. A fetch that failed is not repeated by
     * itself; {@code r} repeats it.
     */
    private Optional<Request> nextRequest() {
        int index = indexRow;
        Request request;
        if (lists.get(index) == null) {
            request = new Request.LoadList(index);
        } else {
            Optional<Commit> commit = selectedCommit();
            if (commit.isEmpty()) {
                return Optional.empty();
            }
            long generation = commit.get().generation();
            if (details.containsKey(new CommitKey(index, generation))) {
                return Optional.empty();
            }
            request = new Request.LoadCommit(index, generation);
        }
        return pending.add(request) ? Optional.of(request) : Optional.empty();
    }

    /** Whether a fetch is under way. */
    public boolean loading() {
        return !pending.isEmpty();
    }

    /** The commits of the shown index directory; empty if they aren't fetched or failed. */
    private Optional<List<Commit>> commits() {
        return lists.get(indexRow) instanceof Fetched.Done<List<Commit>> done
                ? Optional.of(done.value())
                : Optional.empty();
    }

    /**
     * Shows the commits of the index directory at {@code index}, and those that could not be read;
     * the selection stays on its commit, or else goes to the opened one.
     */
    public Optional<Request> commitsLoaded(int index, GetCommitsResult result) {
        pending.remove(new Request.LoadList(index));
        List<Commit> commits = result.commits();
        List<UnreadableCommit> unreadableCommits = result.unreadable();
        if (index == indexRow) {
            Optional<Long> generation = selectedCommit().map(Commit::generation);
            Optional<String> file = selectedUnreadable().map(UnreadableCommit::segmentsFile);
            OptionalInt row = generation
                    .map(g -> firstIndex(commits.size(), i -> commits.get(i).generation() == g))
                    .orElse(OptionalInt.empty());
            if (row.isEmpty() && file.isPresent()) {
                OptionalInt at = firstIndex(
                        unreadableCommits.size(),
                        i -> unreadableCommits.get(i).segmentsFile().equals(file.get()));
                row = at.isPresent() ? OptionalInt.of(commits.size() + at.getAsInt()) : at;
            }
            if (row.isEmpty()) {
                row = firstIndex(commits.size(), i -> commits.get(i).current());
            }
            commitRow = row.orElse(0);
        }
        lists.set(index, new Fetched.Done<>(List.copyOf(commits)));
        unreadable.set(index, List.copyOf(unreadableCommits));
        return nextRequest();
    }

    public void commitsFailed(int index, String error) {
        pending.remove(new Request.LoadList(index));
        lists.set(index, new Fetched.Failed<>(error));
    }

    public void commitLoaded(int index, long generation, GetCommitResult result) {
        pending.remove(new Request.LoadCommit(index, generation));
        details.put(new CommitKey(index, generation), new Fetched.Done<>(result));
    }

    public void commitFailed(int index, long generation, String error) {
        pending.remove(new Request.LoadCommit(index, generation));
        details.put(new CommitKey(index, generation), new Fetched.Failed<>(error));
    }

    private static OptionalInt firstIndex(int size, IntPredicate matches) {
        return IntStream.range(0, size).filter(matches).findFirst();
    }

    private Optional<Commit> selectedCommit() {
        return commits().filter(commits -> commitRow < commits.size()).map(commits -> commits.get(commitRow));
    }

    /** The selected unreadable commit, if the selection is below the commits. */
    private Optional<UnreadableCommit> selectedUnreadable() {
        Optional<List<Commit>> commits = commits();
        if (commits.isEmpty() || commitRow < commits.get().size()) {
            return Optional.empty();
        }
        int row = commitRow - commits.get().size();
        List<UnreadableCommit> shown = unreadable.get(indexRow);
        return row < shown.size() ? Optional.of(shown.get(row)) : Optional.empty();
    }

    private Optional<Fetched<GetCommitResult>> selectedDetails() {
        return selectedCommit().map(commit -> details.get(new CommitKey(indexRow, commit.generation())));
    }

    /** The selected commit whole, if it was fetched. */
    private Optional<GetCommitResult> selectedResult() {
        return selectedDetails()
                .flatMap(fetched -> fetched instanceof Fetched.Done<GetCommitResult> done
                        ? Optional.of(done.value())
                        : Optional.empty());
    }

    private Optional<Segment> selectedSegment() {
        return selectedResult()
                .filter(result -> segmentRow < result.segments().size())
                .map(result -> result.segments().get(segmentRow));
    }

    /** Shows the commits of another index directory, with the opened commit selected. */
    private void selectIndex(int row) {
        if (row == indexRow) {
            return;
        }
        indexRow = row;
        commitRow = commits()
                .map(commits -> firstIndex(commits.size(), i -> commits.get(i).current())
                        .orElse(0))
                .orElse(0);
        segmentRow = 0;
        detailsScroll = 0;
    }

    /** The panels that Tab goes through, in order. */
    private List<Focus> panels() {
        return indexes.size() > 1
                ? List.of(Focus.INDEXES, Focus.COMMITS, Focus.SEGMENTS, Focus.DETAILS)
                : List.of(Focus.COMMITS, Focus.SEGMENTS, Focus.DETAILS);
    }

    /**
     * Switches panels with Tab, moves the selection or scrolls the details, goes to the segments
     * with Enter, and reads the commits again with {@code r}.
     */
    public Optional<Request> handleKey(KeyEvent key) {
        if (key.isChar('r')) {
            // The commits stay until the new ones are in, so the selection can stay on its commit.
            int index = indexRow;
            details.keySet().removeIf(commit -> commit.index() == index);
            pending.removeIf(request -> request instanceof Request.LoadCommit commit && commit.index() == index);
            detailsScroll = 0;
            Request request = new Request.LoadList(index);
            pending.add(request);
            return Optional.of(request);
        }
        if (key.code() == KeyCode.TAB) {
            List<Focus> panels = panels();
            int at = Math.max(panels.indexOf(focus), 0);
            int next = key.hasShift() ? (at + panels.size() - 1) % panels.size() : (at + 1) % panels.size();
            focus(panels.get(next));
            return Optional.empty();
        }
        if (key.code() == KeyCode.ENTER && focus == Focus.INDEXES) {
            focus(Focus.COMMITS);
            return Optional.empty();
        }
        if (key.code() == KeyCode.ENTER && focus == Focus.COMMITS) {
            focus(Focus.SEGMENTS);
            return Optional.empty();
        }
        switch (focus) {
            case INDEXES -> {
                OptionalInt row = Selection.moved(indexRow, indexes.size(), key);
                if (row.isEmpty()) {
                    return Optional.empty();
                }
                selectIndex(row.getAsInt());
                return nextRequest();
            }
            case COMMITS -> {
                Optional<List<Commit>> commits = commits();
                if (commits.isEmpty()) {
                    return Optional.empty();
                }
                int len = commits.get().size() + unreadable.get(indexRow).size();
                OptionalInt row = Selection.moved(commitRow, len, key);
                if (row.isEmpty()) {
                    return Optional.empty();
                }
                if (row.getAsInt() != commitRow) {
                    commitRow = row.getAsInt();
                    segmentRow = 0;
                    detailsScroll = 0;
                }
                return nextRequest();
            }
            case SEGMENTS -> {
                Optional<GetCommitResult> result = selectedResult();
                if (result.isEmpty()) {
                    return Optional.empty();
                }
                OptionalInt row =
                        Selection.moved(segmentRow, result.get().segments().size(), key);
                if (row.isPresent() && row.getAsInt() != segmentRow) {
                    segmentRow = row.getAsInt();
                    detailsScroll = 0;
                }
                return Optional.empty();
            }
            case DETAILS -> {
                scrollDetails(key);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private void focus(Focus focus) {
        boolean segmentDetails = switch (focus) {
            case INDEXES, COMMITS -> false;
            case SEGMENTS -> true;
            case DETAILS -> this.segmentDetails;
        };
        if (segmentDetails != this.segmentDetails) {
            detailsScroll = 0;
        }
        this.segmentDetails = segmentDetails;
        this.focus = focus;
    }

    private void scrollDetails(KeyEvent key) {
        switch (key.code()) {
            case DOWN -> scrollBy(1);
            case UP -> scrollBy(-1);
            case PAGE_DOWN -> scrollBy(Selection.PAGE);
            case PAGE_UP -> scrollBy(-Selection.PAGE);
            case HOME -> detailsScroll = 0;
            case END -> detailsScroll = Integer.MAX_VALUE;
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'j' -> scrollBy(1);
                    case 'k' -> scrollBy(-1);
                    case 'g' -> detailsScroll = 0;
                    case 'G' -> detailsScroll = Integer.MAX_VALUE;
                    default -> {}
                }
            }
            default -> {}
        }
    }

    private void scrollBy(int lines) {
        detailsScroll = Math.clamp((long) detailsScroll + lines, 0, Integer.MAX_VALUE);
    }

    public void render(Frame frame, Rect area) {
        Rect rest = area;
        if (indexes.size() > 1) {
            int rows = Math.min(indexes.size(), INDEX_ROWS);
            // Borders.
            List<Rect> parts = Layout.vertical()
                    .constraints(Constraint.length(rows + 2), Constraint.fill())
                    .split(area);
            renderIndexes(frame, parts.get(0));
            rest = parts.get(1);
        }
        int unreadableCount = unreadable.get(indexRow).size();
        int rows = commits()
                .map(commits -> Math.clamp(commits.size() + unreadableCount, 1, COMMIT_ROWS))
                .orElse(1);
        // Borders and the header.
        List<Rect> parts = Layout.vertical()
                .constraints(Constraint.length(rows + 3), Constraint.fill())
                .split(rest);
        List<Rect> below = Layout.horizontal()
                .constraints(Constraint.fill(3), Constraint.fill(2))
                .split(parts.get(1));
        renderCommits(frame, parts.get(0));
        renderSegments(frame, below.get(0));
        renderDetails(frame, below.get(1));
    }

    private void renderIndexes(Frame frame, Rect area) {
        boolean focused = focus == Focus.INDEXES;
        String title = " Indexes (" + indexes.size() + ") ";
        indexTable.select(indexRow);
        Table table = table(
                        indexes.stream()
                                .map(index -> Row.from(Cell.from(index)))
                                .toList(),
                        focused)
                .widths(Constraint.fill())
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, indexTable);
    }

    private void renderCommits(Frame frame, Rect area) {
        boolean focused = focus == Focus.COMMITS;
        List<Commit> commits;
        switch (lists.get(indexRow)) {
            case null -> {
                message(frame, area, " Commits ", focused, "Reading the commits…");
                return;
            }
            case Fetched.Failed<List<Commit>> failed -> {
                message(frame, area, " Commits ", focused, "Cannot list the commits: " + failed.error());
                return;
            }
            case Fetched.Done<List<Commit>> done -> commits = done.value();
        }
        List<UnreadableCommit> unreadableCommits = unreadable.get(indexRow);
        String title = unreadableCommits.isEmpty()
                ? " Commits (" + commits.size() + "; ● opened, r reads again) "
                : " Commits (" + commits.size() + ", " + unreadableCommits.size()
                        + " unreadable; ● opened, r reads again) ";
        if (commits.isEmpty() && unreadableCommits.isEmpty()) {
            message(frame, area, title, focused, "No readable commit.");
            return;
        }
        int genWidth = Math.max(
                commits.stream()
                        .mapToInt(c -> Long.toString(c.generation()).length())
                        .max()
                        .orElse(0),
                3);
        int fileWidth = IntStream.concat(
                        commits.stream().mapToInt(c -> c.segmentsFile().length()),
                        unreadableCommits.stream()
                                .mapToInt(c -> c.segmentsFile().length()))
                .max()
                .orElse(0);
        int docsWidth = Selection.numberWidth(commits.stream().mapToLong(Commit::maxDoc));
        int luceneWidth = Math.max(
                commits.stream().mapToInt(c -> c.luceneVersion().length()).max().orElse(0), 6);
        Row header = Row.from(
                        Cell.from(""),
                        right("Gen", genWidth),
                        Cell.from("File"),
                        right("Segs", 4),
                        right("Docs", docsWidth),
                        right("Deleted", 7),
                        Cell.from("Lucene"),
                        Cell.from("User data"))
                .style(Style.EMPTY.bold());
        List<Row> rows = new ArrayList<>();
        for (Commit commit : commits) {
            rows.add(Row.from(
                    Cell.from(commit.current() ? "●" : ""),
                    right(Long.toString(commit.generation()), genWidth),
                    Cell.from(commit.segmentsFile()),
                    right(Integer.toString(commit.segmentCount()), 4),
                    right(Integer.toString(commit.maxDoc()), docsWidth),
                    right(Integer.toString(commit.delCount()), 7),
                    Cell.from(commit.luceneVersion()),
                    Cell.from(userData(commit.userData()))));
        }
        // Below the commits.
        for (UnreadableCommit commit : unreadableCommits) {
            rows.add(Row.from(
                            Cell.from("✗"),
                            Cell.from(""),
                            Cell.from(commit.segmentsFile()),
                            Cell.from(""),
                            Cell.from(""),
                            Cell.from(""),
                            Cell.from(""),
                            Cell.from("unreadable: " + TextFormat.escapeControl(commit.message())))
                    .style(Style.EMPTY.red()));
        }
        commitTable.select(commitRow);
        Table table = table(rows, focused)
                .widths(
                        Constraint.length(1),
                        Constraint.length(genWidth),
                        Constraint.length(fileWidth),
                        Constraint.length(4),
                        Constraint.length(docsWidth),
                        Constraint.length(7),
                        Constraint.length(luceneWidth),
                        Constraint.fill())
                .header(header)
                .block(panel(title, focused))
                .build();
        frame.renderStatefulWidget(table, area, commitTable);
    }

    private void renderSegments(Frame frame, Rect area) {
        boolean focused = focus == Focus.SEGMENTS;
        Optional<UnreadableCommit> unreadableCommit = selectedUnreadable();
        if (unreadableCommit.isPresent()) {
            message(frame, area, " Segments ", focused, unreadableCommit.get().segmentsFile() + " could not be read.");
            return;
        }
        GetCommitResult result;
        switch (selectedDetails().orElse(null)) {
            case null -> {
                if (selectedCommit().isPresent()) {
                    message(frame, area, " Segments ", focused, "Reading the commit…");
                } else {
                    frame.renderWidget(panel(" Segments ", focused), area);
                }
                return;
            }
            case Fetched.Failed<GetCommitResult> failed -> {
                message(frame, area, " Segments ", focused, "Cannot read the commit: " + failed.error());
                return;
            }
            case Fetched.Done<GetCommitResult> done -> result = done.value();
        }
        List<Segment> segments = result.segments();
        String title = " Segments of " + result.commit().segmentsFile() + " (" + segments.size() + ") ";
        if (segments.isEmpty()) {
            message(frame, area, title, focused, "No segments.");
            return;
        }
        int[] widths = {
            textWidth(segments, Segment::name, 4),
            Selection.numberWidth(segments.stream().mapToLong(Segment::maxDoc)),
            Selection.numberWidth(segments.stream().mapToLong(Segment::docBase)),
            7,
            textWidth(segments, segment -> sizeOr(segment.sizeInBytes(), "?"), 4),
            textWidth(segments, Segment::luceneVersion, 6),
            textWidth(segments, segment -> segment.codec().orElse(NONE), 5),
            3
        };
        // The columns on the right are also in the details, so they go first when the table is too wide.
        Block block = panel(title, focused);
        int shown =
                Panels.shownColumns(widths, COLUMN_SPACING, block.inner(area).width(), 1);
        Row header = segmentCells(
                        new String[] {"Name", "Docs", "Base", "Deleted", "Size", "Lucene", "Codec", "CFS"},
                        widths,
                        shown)
                .style(Style.EMPTY.bold());
        List<Row> rows = segments.stream()
                .map(segment -> segmentCells(
                        new String[] {
                            segment.name(),
                            Integer.toString(segment.maxDoc()),
                            Integer.toString(segment.docBase()),
                            Integer.toString(segment.delCount()),
                            sizeOr(segment.sizeInBytes(), "?"),
                            segment.luceneVersion(),
                            segment.codec().orElse(NONE),
                            segment.compound() ? "yes" : "no"
                        },
                        widths,
                        shown))
                .toList();
        segmentTable.select(segmentRow);
        Table table = table(rows, focused)
                .widths(Arrays.stream(widths, 0, shown)
                        .mapToObj(Constraint::length)
                        .toList())
                .header(header)
                .block(block)
                .build();
        frame.renderStatefulWidget(table, area, segmentTable);
    }

    private void renderDetails(Frame frame, Rect area) {
        boolean focused = focus == Focus.DETAILS;
        String title = null;
        List<Line> lines = null;
        // The borders don't depend on the title, so the inner width is known before it.
        int width = panel(" Details ", focused).inner(area).width();
        Optional<GetCommitResult> result = selectedResult();
        if (result.isPresent() && segmentDetails) {
            Optional<Segment> segment = selectedSegment();
            if (segment.isPresent()) {
                title = " Segment " + segment.get().name() + " ";
                lines = segmentLines(segment.get(), result.get(), width);
            }
        } else if (result.isPresent()) {
            title = " " + result.get().commit().segmentsFile() + " ";
            lines = commitLines(result.get());
        } else {
            Optional<UnreadableCommit> commit = selectedUnreadable();
            if (commit.isPresent()) {
                title = " " + commit.get().segmentsFile() + " ";
                lines = unreadableLines(commit.get());
            }
        }
        if (lines == null) {
            frame.renderWidget(panel(" Details ", focused), area);
            return;
        }
        // TamboUI scrolls no further than to show the last line at the bottom (ratatui scrolled on
        // until it was at the top), so the scroll stops there too: the next key after End moves.
        Block block = panel(title, focused);
        Rect inner = block.inner(area);
        List<Line> wrapped = Panels.wrap(lines, inner.width());
        detailsScroll = Math.min(detailsScroll, Math.max(wrapped.size() - inner.height(), 0));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(wrapped))
                        .scroll(detailsScroll)
                        .block(block)
                        .build(),
                area);
    }

    /** A table of {@code rows}, with the selected row highlighted as in the other screens. */
    private static Table.Builder table(List<Row> rows, boolean focused) {
        return Table.builder()
                .rows(rows)
                .columnSpacing(COLUMN_SPACING)
                .highlightStyle(highlight(focused))
                .highlightSymbol("")
                .highlightSpacing(Table.HighlightSpacing.NEVER);
    }

    /** The first {@code shown} cells of a segment row; the numbers, from Docs to Size, right-aligned. */
    private static Row segmentCells(String[] texts, int[] widths, int shown) {
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            cells.add(i >= 1 && i <= 4 ? right(texts[i], widths[i]) : Cell.from(texts[i]));
        }
        return Row.from(cells);
    }

    private static void message(Frame frame, Rect area, String title, boolean focused, String text) {
        frame.renderWidget(Panels.wrapped(text, panel(title, focused), area), area);
    }

    private static int textWidth(List<Segment> segments, Function<Segment, String> text, int min) {
        return Math.max(
                segments.stream()
                        .mapToInt(segment -> {
                            String value = text.apply(segment);
                            return value.codePointCount(0, value.length());
                        })
                        .max()
                        .orElse(0),
                min);
    }

    private static String sizeOr(OptionalLong bytes, String unknown) {
        return bytes.isPresent() ? TextFormat.size(bytes.getAsLong()) : unknown;
    }

    /** The user data in one line: {@code key=value} pairs, separated by commas. */
    static String userData(Map<String, String> userData) {
        return new TreeMap<>(userData)
                .entrySet().stream()
                        .map(entry -> TextFormat.escapeControl(entry.getKey()) + "="
                                + TextFormat.firstChars(TextFormat.escapeControl(entry.getValue()), 40))
                        .collect(Collectors.joining(", "));
    }

    /**
     * The details of a commit: what it is, where it stands to the opened one, its user data and all
     * of its files.
     */
    static List<Line> commitLines(GetCommitResult details) {
        Commit commit = details.commit();
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from("Generation " + commit.generation() + " · version " + commit.version() + " · Lucene "
                + commit.luceneVersion()));
        lines.add(Line.from(TextFormat.count(commit.segmentCount(), "segment") + " · "
                + TextFormat.count(commit.maxDoc(), "doc") + " · " + commit.delCount() + " deleted"));
        lines.add(Line.from(
                commit.current()
                        ? "The opened commit."
                        : "Not the opened commit: its documents are not shown on the other screens."));
        section(lines, "User data", new TreeMap<>(commit.userData()));
        fileSection(lines, details.files(), true);
        return lines;
    }

    /** The details of an unreadable commit: why it could not be read, its line breaks kept. */
    static List<Line> unreadableLines(UnreadableCommit commit) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.styled("Could not be read:", Style.EMPTY.red()));
        lines.add(Line.empty());
        commit.message()
                .lines()
                .map(line -> Line.from(TextFormat.escapeControl(line)))
                .forEach(lines::add);
        return lines;
    }

    /**
     * The details of a segment: its documents and versions, then its diagnostics, attributes, codec
     * formats, the formats its fields use (ADR 0037) and its files. {@code width} is the panel's
     * inner width, which the fields of a format wrap to.
     */
    static List<Line> segmentLines(Segment segment, GetCommitResult details, int width) {
        String docs = switch (segment.maxDoc()) {
            case 0 -> "No documents";
            case 1 -> "Document " + segment.docBase();
            default -> "Documents " + segment.docBase() + "–" + (segment.docBase() + segment.maxDoc() - 1);
        };
        String minVersion = segment.minLuceneVersion()
                .filter(min -> !min.equals(segment.luceneVersion()))
                .map(min -> " (parts from " + min + ")")
                .orElse("");
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(
                docs + " · " + segment.delCount() + " deleted · " + segment.softDelCount() + " soft-deleted"));
        lines.add(Line.from("Lucene " + segment.luceneVersion() + minVersion + " · codec "
                + segment.codec().orElse(NONE)));
        lines.add(Line.from("Compound file " + (segment.compound() ? "yes" : "no") + " · blocks "
                + (segment.hasBlocks() ? "yes" : "no") + " · size " + sizeOr(segment.sizeInBytes(), "unknown")));
        lines.add(Line.from("Sorted by " + segment.indexSort().orElse("document id (no index sort)")));
        lines.add(Line.from("Generations: deletes " + generation(segment.delGen()) + " · field infos "
                + generation(segment.fieldInfosGen()) + " · doc values " + generation(segment.docValuesGen())));
        lines.add(Line.from("Id " + segment.id().orElse(NONE)));
        Map<String, String> diagnostics = new TreeMap<>();
        segment.diagnostics().forEach((key, value) -> diagnostics.put(key, diagnostic(key, value)));
        section(lines, "Diagnostics", diagnostics);
        section(lines, "Attributes", new TreeMap<>(segment.attributes()));
        // An index before Lucene 4 has no codec, and its segments' doc store is in the attributes (ADR 0045).
        segment.formats().ifPresent(formats -> formatSection(lines, formats));
        fieldFormatSection(lines, segment, width);
        List<CommitFile> files = details.files().stream()
                .filter(file -> file.segment().filter(segment.name()::equals).isPresent())
                .toList();
        fileSection(lines, files, false);
        return lines;
    }

    private static void formatSection(List<Line> lines, CodecFormats formats) {
        Map<String, String> formatPairs = new LinkedHashMap<>();
        formatPairs.put("postings", formats.postings());
        formatPairs.put("doc values", formats.docValues());
        formatPairs.put("stored fields", formats.storedFields());
        formatPairs.put("term vectors", formats.termVectors());
        formatPairs.put("norms", formats.norms());
        // Absent before the Lucene that brought them (ADR 0046).
        formatPairs.put("points", formats.points().orElse("–"));
        formatPairs.put("KNN vectors", formats.knnVectors().orElse("–"));
        formatPairs.put("field infos", formats.fieldInfos());
        formatPairs.put("segment info", formats.segmentInfo());
        formatPairs.put("live docs", formats.liveDocs());
        formatPairs.put("compound", formats.compound());
        section(lines, "Codec formats", formatPairs);
    }

    private static String generation(OptionalLong generation) {
        return generation.isPresent() ? Long.toString(generation.getAsLong()) : "–";
    }

    /** A diagnostic's value; Lucene's {@code timestamp} in milliseconds also as a UTC time. */
    static String diagnostic(String key, String value) {
        if (!key.equals("timestamp")) {
            return value;
        }
        try {
            return value + " (" + TextFormat.utcTime(Long.parseLong(value)) + ")";
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * A blank line, the bold heading, and the pairs with their keys aligned; "none" if there are
     * none. The pairs are shown in the map's order.
     */
    private static void section(List<Line> lines, String heading, Map<String, String> pairs) {
        lines.add(Line.empty());
        lines.add(Line.styled(heading, Style.EMPTY.bold()));
        if (pairs.isEmpty()) {
            lines.add(Line.styled("none", Style.EMPTY.dim()));
            return;
        }
        int width = Math.min(
                pairs.keySet().stream()
                        .mapToInt(key -> {
                            String escaped = TextFormat.escapeControl(key);
                            return escaped.codePointCount(0, escaped.length());
                        })
                        .max()
                        .orElse(0),
                KEY_WIDTH);
        pairs.forEach((key, value) -> {
            String escaped = TextFormat.escapeControl(key);
            int padding = Math.max(width - escaped.codePointCount(0, escaped.length()), 0);
            lines.add(Line.from(escaped + " ".repeat(padding) + "  " + TextFormat.escapeControl(value)));
        });
    }

    /**
     * A blank line, the bold heading "Field formats", and for postings, doc values and KNN vectors a
     * line per format with its kind in bold and the format in cyan, then the fields that use it,
     * indented by 2 and wrapped to {@code width} under that indent; "none" if no field uses one, and
     * why in red if the field infos can't be read.
     */
    private static void fieldFormatSection(List<Line> lines, Segment segment, int width) {
        lines.add(Line.empty());
        lines.add(Line.styled("Field formats", Style.EMPTY.bold()));
        if (segment.fieldInfosError().isPresent()) {
            lines.add(Line.styled(
                    "Could not be read: "
                            + TextFormat.escapeControl(segment.fieldInfosError().get()),
                    Style.EMPTY.red()));
            return;
        }
        Map<String, Function<FieldFormats, Optional<String>>> kinds = new LinkedHashMap<>();
        kinds.put("postings", FieldFormats::postings);
        kinds.put("doc values", FieldFormats::docValues);
        kinds.put("KNN vectors", FieldFormats::knnVectors);
        Map<String, Map<String, List<String>>> formatsOfKind = new LinkedHashMap<>();
        kinds.forEach((kind, format) -> {
            Map<String, List<String>> fieldsOfFormat = new TreeMap<>();
            for (FieldFormats field : segment.fieldFormats()) {
                format.apply(field)
                        .ifPresent(name -> fieldsOfFormat
                                .computeIfAbsent(name, _ -> new ArrayList<>())
                                .add(field.field()));
            }
            if (!fieldsOfFormat.isEmpty()) {
                formatsOfKind.put(kind, fieldsOfFormat);
            }
        });
        if (formatsOfKind.isEmpty()) {
            lines.add(Line.styled("none", Style.EMPTY.dim()));
            return;
        }
        int kindWidth =
                formatsOfKind.keySet().stream().mapToInt(String::length).max().orElse(0);
        formatsOfKind.forEach((kind, fieldsOfFormat) -> fieldsOfFormat.forEach((format, fields) -> {
            lines.add(Line.from(
                    Span.styled(kind, Style.EMPTY.bold()),
                    Span.raw(" ".repeat(kindWidth - kind.length()) + "  "),
                    Span.styled(TextFormat.escapeControl(format), Style.EMPTY.cyan())));
            String names = fields.stream().map(TextFormat::escapeControl).collect(Collectors.joining(", "));
            for (String part : TextFormat.wrap(names, width - 2, Integer.MAX_VALUE)) {
                lines.add(Line.from("  " + part));
            }
        }));
    }

    /** The files with their sizes, and with {@code withSegment} the segment they belong to. */
    private static void fileSection(List<Line> lines, List<CommitFile> files, boolean withSegment) {
        OptionalLong total = files.stream().allMatch(file -> file.size().isPresent())
                ? OptionalLong.of(files.stream()
                        .mapToLong(file -> file.size().getAsLong())
                        .sum())
                : OptionalLong.empty();
        String heading = "Files (" + files.size() + ", " + sizeOr(total, "size unknown") + ")";
        Map<String, String> pairs = new LinkedHashMap<>();
        for (CommitFile file : files) {
            String size = sizeOr(file.size(), "missing");
            String value = " ".repeat(Math.max(10 - size.length(), 0)) + size;
            if (withSegment && file.segment().isPresent()) {
                value += "  " + file.segment().get();
            }
            pairs.put(file.name(), value);
        }
        section(lines, heading, pairs);
    }
}
