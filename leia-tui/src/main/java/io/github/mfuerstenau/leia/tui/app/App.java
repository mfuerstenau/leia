package io.github.mfuerstenau.leia.tui.app;

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
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.prompt.PathCompletion;
import io.github.mfuerstenau.leia.tui.prompt.PathPrompt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The application state: what is on screen and what a key does. It makes no calls itself: {@link
 * #handleKey} returns an {@link Action}, the {@link Session} makes the calls and reports back with
 * {@link #opened}, {@link #openedWithoutReader}, {@link #openFailed}, {@link #reopened}, {@link
 * #reopenUnchanged}, {@link #reopenFailed} or {@link #closed}.
 *
 * <p>While an action that it waits for runs, the app is {@linkplain #busy() busy} and takes no keys
 * but Ctrl+C: the Rust TUI blocked on its calls, so no key came in between.
 *
 * <p>This is the shell of ADR 0034's port. The screens aren't ported yet, so each one shows what it
 * will be; the overview lists the basics of the open index.
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

    /** The statistics of the open index; null without one, or without a reader. */
    private GetIndexStatsResult stats;

    /** The index directories that were found but left out of the open index. */
    private List<SkippedIndex> skipped = List.of();

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
        this.stats = stats;
        this.skipped = List.copyOf(skipped);
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
        stats = null;
        skipped = List.copyOf(result.skipped());
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
        if (this.stats == null) {
            return opened(stats, List.of());
        }
        List<IndexCommitStats> indexes = stats.indexes();
        if (indexes.size() == 1) {
            notice = new Notice.Reopened(indexes.getFirst().segmentsFile());
        } else {
            List<IndexCommitStats> old = this.stats.indexes();
            int changed = 0;
            for (int i = 0; i < Math.min(indexes.size(), old.size()); i++) {
                if (!indexes.get(i).segmentsFile().equals(old.get(i).segmentsFile())) {
                    changed++;
                }
            }
            notice = new Notice.Reopened(changed + " of " + indexes.size() + " indexes");
        }
        this.stats = stats;
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
        stats = null;
        skipped = List.of();
        busy = false;
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
            return Optional.of(new Action.Quit());
        }
        if (!isChar) {
            return Optional.empty();
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
                if (stats != null) {
                    notice = new Notice.Reopening();
                    busy = true;
                    return Optional.of(new Action.Reopen());
                }
                if (hasIndex()) {
                    notice = new Notice.NoReader();
                }
            }
            default -> {}
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
        String reopen = stats != null ? "R reopen   " : "";
        if (screen == Screen.ANALYSIS) {
            renderNotPorted(frame, body, screen);
            return hasIndex() ? " q quit   o open   c close   1-7 screen" : " q quit   o open";
        }
        if (opened == null) {
            renderAbout(frame, body);
            return " q quit   o open   6 analysis";
        }
        if (screen == Screen.COMMITS || screen == Screen.CHECK) {
            renderNotPorted(frame, body, screen);
            return " q quit   o open   c close   " + reopen + "1-7 screen";
        }
        if (stats == null) {
            renderWithoutReader(frame, body);
            return " q quit   o open   c close   1-7 screen";
        }
        if (screen == Screen.OVERVIEW) {
            renderOverview(frame, body, stats);
        } else {
            renderNotPorted(frame, body, screen);
        }
        return " q quit   o open   c close   R reopen   1-7 screen";
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

    /** The basics of the open index, until the overview is ported. */
    private void renderOverview(Frame frame, Rect body, GetIndexStatsResult stats) {
        List<Line> lines = new ArrayList<>();
        lines.add(labelLine("Path", stats.path()));
        lines.add(labelLine("Directory", stats.directoryImpl()));
        lines.add(labelLine("Documents", stats.numDocs() + " live, max doc " + stats.maxDoc()));
        lines.add(labelLine("Segments", Integer.toString(stats.segmentCount())));
        if (stats.indexes().size() > 1 || !skipped.isEmpty()) {
            String count = Integer.toString(stats.indexes().size());
            lines.add(labelLine("Indexes", skipped.isEmpty() ? count : count + " (" + skipped.size() + " skipped)"));
            for (IndexCommitStats index : stats.indexes()) {
                lines.add(Line.from("  " + relative(stats.path(), index.path())));
            }
            for (SkippedIndex index : skipped) {
                lines.add(Line.from("  " + relative(stats.path(), index.path()) + "  skipped: " + index.message()));
            }
        }
        List<String> fields = stats.fields().stream().map(IndexField::name).toList();
        lines.add(labelLine("Fields", fields.isEmpty() ? "none" : fields.size() + ": " + String.join(", ", fields)));
        lines.add(Line.empty());
        lines.add(Line.from(Span.raw("The overview isn't ported to the Java TUI yet (ADR 0034).")
                .dim()));
        frame.renderWidget(wrapped(lines, " Index "), body);
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
                    lines.add(Line.from("  " + relative(opened.path(), index)));
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

    /** A label, bold and padded to 10 columns, and its value. */
    static Line labelLine(String label, String value) {
        return Line.from(Span.styled(String.format(Locale.ROOT, "%-10s ", label), Style.EMPTY.bold()), Span.raw(value));
    }

    /** The name of an index directory as shown: below the opened path. */
    static String relative(String root, String path) {
        try {
            Path rootPath = Path.of(root);
            Path indexPath = Path.of(path);
            if (!indexPath.startsWith(rootPath)) {
                return path;
            }
            String rest = rootPath.relativize(indexPath).toString();
            return rest.isEmpty() ? "." : rest;
        } catch (IllegalArgumentException e) {
            return path;
        }
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
