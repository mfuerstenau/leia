package io.github.mfuerstenau.leia.tui.app;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.labelLine;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.overview.FieldScan;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.prompt.PathCompletion;
import io.github.mfuerstenau.leia.tui.prompt.PathPrompt;
import io.github.mfuerstenau.leia.tui.widgets.Logo;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The application state: what is on screen and what a key does. It makes no calls itself: {@link
 * #handleKey} returns an {@link Action}, the {@link Session} makes the calls and reports back with
 * {@link #opened}, {@link #openedWithoutReader}, {@link #openFailed}, {@link #reopened}, {@link
 * #reopenUnchanged}, {@link #reopenFailed}, {@link #closed}, and the {@code scan*} methods.
 *
 * <p>While an action that it waits for runs, the app is {@linkplain #busy() busy} and takes no keys
 * but Ctrl+C: the Rust TUI blocked on its calls, so no key came in between.
 *
 * <p>The Rust TUI got each reply before the next key; here a reply can come late, after the user
 * moved on. So each term scan has an id, a reply for a scan that is no longer the running one is
 * ignored, and a job that started for a cancelled scan is cancelled as soon as its id is known.
 *
 * <p>This is ADR 0034's port. The screens that aren't ported yet show what they will be.
 */
public final class App {

    private final VersionResult version;

    /** The directory that {@code ~} stands for in the prompt. */
    private final Optional<Path> home;

    /** The recently opened paths, for the prompt (ADR 0020). */
    private final History history;

    /** The {@code Directory} the next prompt opens with: {@code --directory}, then the last one chosen. */
    private DirectoryImpl directory;

    /** What is open, or null. */
    private Opened opened;

    /** The overview of the open index; null without one, or without a reader. */
    private Overview overview;

    /**
     * The term scan that runs in the backend, or null. There is at most one; the others wait in the
     * queue.
     *
     * @param jobId null until the backend has started the job
     */
    private record RunningScan(long id, String field, int topN, Long jobId) {}

    private RunningScan scan;

    /** Fields whose scan waits for the running one. */
    private final ArrayDeque<String> queue = new ArrayDeque<>();

    /** The id of the next scan. */
    private long nextScanId = 1;

    /** How many top terms a scan asks for; kept when another index is opened. */
    private int topN = Overview.DEFAULT_TOP_TERMS;

    /** The screen shown; kept when another index is opened. */
    private Screen screen = Screen.OVERVIEW;

    /** The open-index prompt, or null. */
    private PathPrompt prompt;

    /** What the last reopen came to, on the tab line until the next key; or null. */
    private Notice notice;

    /**
     * The path of a prompt that was closed after it failed to open it: the next prompt starts from
     * it, so that it can be tried again once a jar is added (ADR 0024). Or null.
     */
    private String failedPath;

    /** Whether an open, reopen or close runs. */
    private boolean busy;

    public App(VersionResult version, Optional<Path> home, History history, DirectoryImpl directory) {
        this.version = version;
        this.home = home;
        this.history = history;
        this.directory = directory;
    }

    /** The {@code Directory} the next open uses. */
    public DirectoryImpl directory() {
        return directory;
    }

    public boolean hasIndex() {
        return opened != null;
    }

    /** Whether an open, reopen or close runs: then no key but Ctrl+C does anything. */
    public boolean busy() {
        return busy;
    }

    private PathPrompt pathPrompt(String input) {
        return new PathPrompt(input, home).withRecent(history.paths()).withDirectory(directory);
    }

    /**
     * Shows {@code path} being opened with {@code mode}, for an open that no key started (the
     * command line's path).
     */
    public void opening(String path, OpenMode mode) {
        prompt = pathPrompt(path).withMode(mode);
        prompt.setBusy();
        busy = true;
    }

    /**
     * Shows the new index, opened with a reader, and the index directories left out of it. The
     * backend stopped the jobs of the previous one. Returns what the shown screen has to fetch first.
     */
    public Optional<Action> opened(GetIndexStatsResult stats, List<SkippedIndex> skipped) {
        history.add(stats.path());
        overview = new Overview(stats, Logo.aboutLines(version), topN).withSkipped(skipped);
        forgetScans();
        opened = new Opened(
                stats.path(),
                false,
                directory,
                stats.indexes().stream().map(IndexCommitStats::path).toList(),
                skipped.stream().map(SkippedIndex::path).toList());
        prompt = null;
        busy = false;
        return Optional.empty();
    }

    /**
     * Shows the index opened without a reader (ADR 0021): only the commits and check screens work,
     * and the commits are shown unless the analysis or check screen is. The backend stopped the jobs
     * of the previous index. Returns what the shown screen has to fetch first.
     */
    public Optional<Action> openedWithoutReader(OpenIndexResult result) {
        history.add(result.path());
        overview = null;
        forgetScans();
        opened = new Opened(
                result.path(),
                true,
                directory,
                result.indexes().stream().map(IndexEntry::path).toList(),
                result.skipped().stream().map(SkippedIndex::path).toList());
        if (screen != Screen.ANALYSIS && screen != Screen.CHECK) {
            screen = Screen.COMMITS;
        }
        prompt = null;
        busy = false;
        return Optional.empty();
    }

    /**
     * Shows the reopened index (ADR 0019) where the user was. The backend stopped the jobs of the old
     * index. Returns what the shown screen has to fetch first.
     */
    public Optional<Action> reopened(GetIndexStatsResult stats) {
        if (overview == null) {
            return opened(stats, List.of());
        }
        List<IndexCommitStats> indexes = stats.indexes();
        if (indexes.size() == 1) {
            notice = new Notice.Reopened(indexes.getFirst().segmentsFile());
        } else {
            List<IndexCommitStats> old = overview.stats().indexes();
            int changed = 0;
            for (int i = 0; i < Math.min(indexes.size(), old.size()); i++) {
                if (!indexes.get(i).segmentsFile().equals(old.get(i).segmentsFile())) {
                    changed++;
                }
            }
            notice = new Notice.Reopened(changed + " of " + indexes.size() + " indexes");
        }
        overview = overview.reopened(stats, topN);
        forgetScans();
        opened = new Opened(
                opened.path(),
                false,
                opened.directory(),
                indexes.stream().map(IndexCommitStats::path).toList(),
                opened.skipped());
        busy = false;
        return Optional.empty();
    }

    /** The index has no newer commit; everything stays as it is. */
    public void reopenUnchanged(ReopenIndexResult result) {
        List<IndexEntry> indexes = result.indexes();
        notice = new Notice.Unchanged(
                indexes.size() == 1
                        ? indexes.getFirst()
                                .reader()
                                .map(ReaderInfo::segmentsFile)
                                .orElse("")
                        : indexes.size() + " indexes");
        busy = false;
    }

    /** The open index stays open, and is shown as it was. */
    public void reopenFailed(String error) {
        notice = new Notice.Failed(error);
        busy = false;
    }

    /**
     * Shows {@code error} in the prompt, which keeps the path, so it can be corrected. If {@code
     * noReaderHelps}, it says that opening without a reader may still show the commits (ADR 0021).
     */
    public void openFailed(String error, boolean noReaderHelps) {
        if (prompt == null) {
            prompt = pathPrompt("");
        }
        if (noReaderHelps) {
            prompt.setErrorOfferingNoReader(error);
        } else {
            prompt.setError(error);
        }
        busy = false;
    }

    /** The backend stopped the jobs of the closed index. */
    public void closed() {
        opened = null;
        overview = null;
        forgetScans();
        busy = false;
    }

    private void forgetScans() {
        scan = null;
        queue.clear();
    }

    /** Whether a term scan runs or waits. */
    boolean scanning() {
        return scan != null;
    }

    /** The jobs to poll: the running term scan, once its job has started. */
    public List<Poll> polls() {
        if (scan == null || scan.jobId() == null) {
            return List.of();
        }
        return List.of(new Poll.Scan(scan.id(), scan.jobId()));
    }

    /**
     * The job of scan {@code scanId} has started. If that scan was cancelled or forgotten meanwhile,
     * returns the action that cancels the job.
     */
    public Optional<Action> scanStarted(long scanId, long jobId) {
        if (scan == null || scan.id() != scanId) {
            return Optional.of(new Action.CancelScan(jobId));
        }
        scan = new RunningScan(scan.id(), scan.field(), scan.topN(), jobId);
        return Optional.empty();
    }

    /**
     * Shows the progress of scan {@code scanId}, or its outcome. Returns the next scan to start, once
     * this one is done.
     */
    public Optional<Action> scanProgress(long scanId, GetFieldTermScanResult result) {
        if (scan == null || scan.id() != scanId || overview == null) {
            return Optional.empty();
        }
        if (result.terms().isPresent()) {
            overview.setScan(
                    scan.field(), Optional.of(FieldScan.done(result.terms().get(), scan.topN())));
            scan = null;
            return startNextScan();
        }
        overview.setScan(scan.field(), Optional.of(new FieldScan.Running(result.termsScanned())));
        return Optional.empty();
    }

    /** Shows why scan {@code scanId} failed. Returns the next scan to start. */
    public Optional<Action> scanFailed(long scanId, String error) {
        if (scan == null || scan.id() != scanId) {
            return Optional.empty();
        }
        if (overview != null) {
            overview.setScan(scan.field(), Optional.of(new FieldScan.Failed(error)));
        }
        scan = null;
        return startNextScan();
    }

    /**
     * Forgets the running scan and the queued ones. Returns the action that cancels the running job,
     * if it has started; otherwise {@link #scanStarted} cancels it once it has.
     */
    private Optional<Action> cancelScans() {
        Long jobId = scan.jobId();
        if (overview != null) {
            overview.setScan(scan.field(), Optional.empty());
            for (String field : queue) {
                overview.setScan(field, Optional.empty());
            }
        }
        forgetScans();
        return jobId == null ? Optional.empty() : Optional.of(new Action.CancelScan(jobId));
    }

    /** Queues the scans of {@code fields}, and returns the scan to start if none runs. */
    private Optional<Action> requestScans(List<String> fields) {
        for (String field : fields) {
            overview.setScan(field, Optional.of(new FieldScan.Queued()));
            queue.addLast(field);
        }
        return scan != null ? Optional.empty() : startNextScan();
    }

    private Optional<Action> startNextScan() {
        String field = queue.pollFirst();
        if (field == null) {
            return Optional.empty();
        }
        if (overview != null) {
            overview.setScan(field, Optional.of(new FieldScan.Running(0)));
        }
        scan = new RunningScan(nextScanId++, field, topN, null);
        return Optional.of(new Action.StartScan(scan.id(), field, topN));
    }

    /** What the overview does with a key that isn't one of the app's. */
    private Optional<Action> overviewKey(KeyEvent key) {
        if (key.code() == KeyCode.ENTER) {
            // Enter on a top term will open it on the terms screen, once that is ported.
            if (overview.selectedTopTerm().isPresent()) {
                return Optional.empty();
            }
            return overview.selectedField()
                    .filter(overview::needsScan)
                    .flatMap(field -> requestScans(List.of(field.name())));
        }
        if (key.isChar('a')) {
            return requestScans(overview.fieldsToScan());
        }
        if (key.isChar('+') || key.isChar('=') || key.isChar('-')) {
            topN = Overview.stepTopTerms(topN, !key.isChar('-'));
            overview.setTopN(topN);
            return Optional.empty();
        }
        overview.handleKey(key);
        return Optional.empty();
    }

    public Optional<Action> handleKey(KeyEvent key) {
        boolean isChar = key.code() == KeyCode.CHAR;
        if (isChar && key.codePoint() == 'c' && key.hasCtrl()) {
            return Optional.of(new Action.Quit());
        }
        if (busy) {
            return Optional.empty();
        }
        notice = null;
        if (prompt != null) {
            return switch (prompt.handleKey(key)) {
                case PathPrompt.Submit submit -> {
                    prompt.setBusy();
                    directory = prompt.directory();
                    busy = true;
                    yield Optional.of(
                            new Action.Open(PathCompletion.expandHome(submit.path(), home), directory, prompt.mode()));
                }
                case PathPrompt.Cancel _ -> {
                    failedPath = prompt.error().isPresent() ? prompt.input() : null;
                    prompt = null;
                    yield Optional.empty();
                }
                case PathPrompt.Nothing _ -> Optional.empty();
            };
        }
        if (key.code() == KeyCode.ESCAPE) {
            // Esc stops the scans first, and quits only when there are none.
            return scan != null ? cancelScans() : Optional.of(new Action.Quit());
        }
        if (!isChar) {
            return screen == Screen.OVERVIEW && overview != null ? overviewKey(key) : Optional.empty();
        }
        switch (key.codePoint()) {
            case 'q' -> {
                return Optional.of(new Action.Quit());
            }
            case '1' -> showIf(hasIndex(), Screen.OVERVIEW);
            case '2' -> showIf(hasIndex(), Screen.DOCUMENTS);
            case '3' -> showIf(hasIndex(), Screen.TERMS);
            case '4' -> showIf(hasIndex(), Screen.COMMITS);
            case '5' -> showIf(hasIndex(), Screen.SEARCH);
            case '6' -> showIf(true, Screen.ANALYSIS);
            case '7' -> showIf(hasIndex(), Screen.CHECK);
            case 'o' -> {
                // Starts from the path that failed to open, then from the open index, so a sibling
                // is a Ctrl+W away.
                String path = failedPath != null ? failedPath : opened != null ? opened.path() : "";
                failedPath = null;
                prompt = pathPrompt(path);
            }
            case 'c' -> {
                if (hasIndex()) {
                    busy = true;
                    return Optional.of(new Action.Close());
                }
            }
            case 'R' -> {
                if (overview != null) {
                    notice = new Notice.Reopening();
                    busy = true;
                    return Optional.of(new Action.Reopen());
                }
                if (hasIndex()) {
                    notice = new Notice.NoReader();
                }
            }
            default -> {
                if (screen == Screen.OVERVIEW && overview != null) {
                    return overviewKey(key);
                }
            }
        }
        return Optional.empty();
    }

    private void showIf(boolean available, Screen screen) {
        if (available) {
            this.screen = screen;
        }
    }

    public void render(Frame frame) {
        // The analysis screen needs no index, so its tab shows without one.
        boolean tabsShown = hasIndex() || screen == Screen.ANALYSIS;
        List<Rect> rows = Layout.vertical()
                .constraints(Constraint.length(tabsShown ? 1 : 0), Constraint.fill(), Constraint.length(1))
                .split(frame.area());
        Rect body = rows.get(1);
        String keys = renderBody(frame, body);
        if (tabsShown) {
            renderTabs(frame, rows.get(0));
        }
        frame.renderWidget(Paragraph.from(Line.from(keys)), rows.get(2));
        if (prompt != null) {
            prompt.render(frame, body);
        }
    }

    /** Draws the shown screen into {@code body}, and returns the footer's keys for it. */
    private String renderBody(Frame frame, Rect body) {
        String reopen = overview != null ? "R reopen   " : "";
        String cancel = scan != null ? "Esc cancel scan   " : "";
        if (screen == Screen.ANALYSIS) {
            renderNotPorted(frame, body, screen);
            return hasIndex() ? " " + cancel + "q quit   o open   c close   1-7 screen" : " q quit   o open";
        }
        if (opened == null) {
            renderAbout(frame, body);
            return " q quit   o open   6 analysis";
        }
        if (screen == Screen.COMMITS || screen == Screen.CHECK) {
            renderNotPorted(frame, body, screen);
            return " " + cancel + "q quit   o open   c close   " + reopen + "1-7 screen";
        }
        if (overview == null) {
            renderWithoutReader(frame, body);
            return " q quit   o open   c close   1-7 screen";
        }
        if (screen != Screen.OVERVIEW) {
            renderNotPorted(frame, body, screen);
            return " " + cancel + "q quit   o open   c close   R reopen   1-7 screen";
        }
        overview.render(frame, body);
        if (scan != null) {
            return " Esc cancel scan   q quit   o open   c close   R reopen   1-7 screen   s/S sort   Tab panel   ↑↓";
        }
        // Enter and / on a top term come back with the terms and search screens.
        if (overview.selectedTopTerm().isPresent()) {
            return " q quit   o open   c close   R reopen   1-7 screen   +/- top N   s/S sort   Tab panel   ↑↓";
        }
        return " q quit   o open   c close   R reopen   1-7 screen   ⏎ terms   a all terms   +/- top N   s/S sort   "
                + "Tab panel   ↑↓";
    }

    private void renderAbout(Frame frame, Rect body) {
        List<Line> lines = new ArrayList<>(Logo.aboutLines(version));
        lines.add(Line.empty());
        lines.add(Line.from("Java " + version.java().version() + " ("
                + version.java().vendor() + ") in " + version.java().home()));
        lines.add(Line.empty());
        lines.add(Line.from("No index open. Press o to open one, or 6 to try an analyzer."));
        Block block = bordered(" leia ");
        Rect inner = block.inner(body);
        frame.renderWidget(block, body);
        // The logo only where it leaves room for the text.
        if (inner.height() >= Logo.HEIGHT && inner.width() >= Logo.WIDTH + 2 + 40) {
            Logo.renderBeside(frame, inner, lines);
        } else {
            frame.renderWidget(wrapped(lines, null), inner);
        }
    }

    /**
     * A screen of an index opened without a reader: the overview says how it was opened, and the
     * screens that need a reader say so.
     */
    private void renderWithoutReader(Frame frame, Rect body) {
        List<Line> lines = new ArrayList<>();
        String title;
        if (screen == Screen.OVERVIEW) {
            lines.add(labelLine("Path", opened.path()));
            lines.add(labelLine("Directory", directoryName(opened.directory())));
            lines.add(labelLine("Reader", "none: opened without a reader, so only the commits (4) and check (7) work"));
            if (opened.indexes().size() > 1) {
                lines.add(labelLine("Indexes", Integer.toString(opened.indexes().size())));
                for (String index : opened.indexes()) {
                    lines.add(Line.from("  " + Overview.relative(opened.path(), index)));
                }
            }
            title = " Index ";
        } else {
            lines.add(Line.from("This screen needs a reader, and the index was opened without one. The commits (4) "
                    + "and check (7) screens work; o opens the index again, Ctrl+N switches the reader on."));
            title = " No reader ";
        }
        frame.renderWidget(wrapped(lines, title), body);
    }

    private static void renderNotPorted(Frame frame, Rect body, Screen screen) {
        frame.renderWidget(
                wrapped(
                        List.of(Line.from("The " + screen.name.toLowerCase(Locale.ROOT)
                                + " screen isn't ported to the Java TUI yet (ADR 0034).")),
                        " " + screen.name + " "),
                body);
    }

    /** The tabs, and the notice or how the index was opened, right-aligned after them. */
    private void renderTabs(Frame frame, Rect area) {
        Line line = notice != null ? notice.line() : opened != null ? marker(opened) : null;
        // Without padding, the tabs leave the line more room when it needs it.
        boolean compact = line != null && Math.max(area.width() - TABS_WIDTH, 0) < line.width();
        int tabsWidth = compact ? COMPACT_TABS_WIDTH : TABS_WIDTH;
        Rect tabs = area;
        if (line != null) {
            List<Rect> parts = Layout.horizontal()
                    .constraints(Constraint.length(tabsWidth), Constraint.fill())
                    .split(area);
            tabs = parts.get(0);
            frame.renderWidget(Paragraph.from(line.right()), parts.get(1));
        }
        String padding = compact ? "" : " ";
        List<Span> spans = new ArrayList<>();
        for (Screen tab : Screen.values()) {
            if (tab.ordinal() > 0) {
                spans.add(Span.raw("│"));
            }
            spans.add(Span.raw(padding));
            spans.add(tab == screen ? Span.styled(tab.title(), Style.EMPTY.reversed()) : Span.raw(tab.title()));
            spans.add(Span.raw(padding));
        }
        frame.renderWidget(Paragraph.from(Line.from(spans)), tabs);
    }

    /** How wide the tabs are: each title with a space on either side, and a divider between two. */
    static final int TABS_WIDTH = tabsWidth(2);

    /** How wide the tabs are without the spaces around the titles. */
    static final int COMPACT_TABS_WIDTH = tabsWidth(0);

    private static int tabsWidth(int padding) {
        int width = Screen.values().length - 1;
        for (Screen screen : Screen.values()) {
            width += screen.title().length() + padding;
        }
        return width;
    }

    /**
     * What the tab line says about how the index was opened, if not as usual: without a reader, or
     * with several index directories. Null otherwise.
     */
    private static Line marker(Opened opened) {
        int count = opened.indexes().size();
        String text;
        if (opened.noReader()) {
            text = count == 1 ? "no reader" : "no reader, " + count + " indexes";
        } else if (count > 1) {
            text = count + " indexes";
        } else {
            return null;
        }
        return Line.styled(text, Style.EMPTY.yellow());
    }

    /** How the overview names the {@code Directory} that was asked for. */
    private static String directoryName(DirectoryImpl directory) {
        return switch (directory) {
            case FS -> "FSDirectory.open (Lucene's choice)";
            case MMAP -> "MMapDirectory";
            case NIO -> "NIOFSDirectory";
        };
    }

    private static Block bordered(String title) {
        return Block.builder().borders(Borders.ALL).title(title).build();
    }

    /** {@code lines} wrapped at word boundaries, in a bordered block if {@code title} isn't null. */
    private static Paragraph wrapped(List<Line> lines, String title) {
        Paragraph.Builder paragraph = Paragraph.builder().text(Text.from(lines)).overflow(Overflow.WRAP_WORD);
        if (title != null) {
            paragraph.block(bordered(title));
        }
        return paragraph.build();
    }
}
