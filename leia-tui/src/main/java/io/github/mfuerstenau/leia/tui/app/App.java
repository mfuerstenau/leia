package io.github.mfuerstenau.leia.tui.app;

import static io.github.mfuerstenau.leia.tui.widgets.Panels.labelLine;

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
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.ReaderSegment;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsResult;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesResult;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import io.github.mfuerstenau.leia.protocol.jars.ServerRuntimeInfo;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnRecallResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetHighlightResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.terms.ExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetExportTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders.Known;
import io.github.mfuerstenau.leia.tui.analysis.Analysis;
import io.github.mfuerstenau.leia.tui.analysis.AutoStopWordsForm;
import io.github.mfuerstenau.leia.tui.check.Check;
import io.github.mfuerstenau.leia.tui.commits.Commits;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.FilterStep;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport.DocumentData;
import io.github.mfuerstenau.leia.tui.documents.Documents;
import io.github.mfuerstenau.leia.tui.documents.Viewer;
import io.github.mfuerstenau.leia.tui.jars.DefaultJars;
import io.github.mfuerstenau.leia.tui.jars.JarsPopup;
import io.github.mfuerstenau.leia.tui.jars.RuntimeFolders;
import io.github.mfuerstenau.leia.tui.log.LogBuffer;
import io.github.mfuerstenau.leia.tui.log.LogWindow;
import io.github.mfuerstenau.leia.tui.overview.FieldScan;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.points.PointFormats;
import io.github.mfuerstenau.leia.tui.prompt.ExportPrompt;
import io.github.mfuerstenau.leia.tui.prompt.ExportPrompt.ExportTarget;
import io.github.mfuerstenau.leia.tui.prompt.PathCompletion;
import io.github.mfuerstenau.leia.tui.prompt.PathPrompt;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeInstall;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeWindow;
import io.github.mfuerstenau.leia.tui.search.Search;
import io.github.mfuerstenau.leia.tui.terms.Terms;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.theme.Palette;
import io.github.mfuerstenau.leia.tui.theme.Theme;
import io.github.mfuerstenau.leia.tui.theme.ThemeFile;
import io.github.mfuerstenau.leia.tui.theme.Themes;
import io.github.mfuerstenau.leia.tui.widgets.KeyLegend;
import io.github.mfuerstenau.leia.tui.widgets.Logo;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.logging.Level;

/**
 * The application state: what is on screen and what a key does. It makes no calls itself: {@link
 * #handleKey} returns an {@link Action}, the {@link Session} makes the calls and reports back with
 * {@link #opened}, {@link #openedWithoutReader}, {@link #openFailed}, {@link #reopened}, {@link
 * #reopenUnchanged}, {@link #reopenFailed}, {@link #closed}, and the {@code scan*}, {@code commit*},
 * {@code terms*} and {@code postings*} methods.
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

    private static final String APP_KEYS = "q quit   o open   c close   ";

    private static final String INDEXES = " indexes";

    /** The major of the Lucene that reads the newest indexes, leia-lucene10's (ADR 0071). */
    static final int NEWEST_LUCENE_MAJOR = 10;

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

    /** The commits screen of the open index, with or without a reader; null without one. */
    private Commits commits;

    /** The terms screen of the open index; null without one, or without a reader. */
    private Terms terms;

    /** The documents screen of the open index; null without one, or without a reader. */
    private Documents documents;

    /** The format of each point field, which the terms and documents screens share (ADR 0041). */
    private PointFormats pointFormats = new PointFormats();

    /** The search screen of the open index; null without one, or without a reader. */
    private Search search;

    /** The check screen of the open path, with or without a reader; null without one. */
    private Check check;

    /** The analysis screen, which needs no index; its analyzer is kept when another index is opened. */
    private final Analysis analysis = new Analysis();

    /** Calls of the analysis screen whose reply is still to come. */
    private int analysisCalls;

    /** Document fetches (documents, stored values, term vectors, positions) whose reply is still to come. */
    private int documentFetches;

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

    /** How many jumps Backspace can retrace at most (ADR 0052). */
    private static final int BACK_DEPTH = 16;

    /** Rows above the logo on the about screen. */
    private static final int ABOUT_PADDING_TOP = 1;

    /** Columns left of the logo on the about screen. */
    private static final int ABOUT_PADDING_LEFT = 2;

    /**
     * The screens that the jumps to the shown one came from, the last one first (ADR 0052), with
     * where the jumps left them (ADR 0053). A number key, an open and a close forget them.
     */
    private final Deque<Back> back = new ArrayDeque<>();

    /**
     * A screen that a jump came from, with where the jump left the terms, documents, search and
     * commits screens (ADR 0053, ADR 0054): the terms screen when it was left, or when the document
     * left steps through its postings; the search screen likewise, when the document steps through
     * its hits; the documents and commits screens when they were left. A reopen drops the marks.
     */
    private record Back(
            Screen screen,
            Optional<Terms.Mark> terms,
            Optional<Documents.Mark> documents,
            Optional<Search.Mark> search,
            Optional<Commits.Mark> commits) {

        Back withoutMarks() {
            return new Back(screen, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** The open-index prompt, or null. */
    private PathPrompt prompt;

    /** What the last reopen came to, on the tab line until the next key; or null. */
    private Notice notice;

    /**
     * The path of a prompt that was closed after it failed to open it: the next prompt starts from
     * it, so that it can be tried again once a jar is added (ADR 0024). Or null.
     */
    private String failedPath;

    /** The default folder of jars, and whether its jars were loaded (ADR 0024). */
    private DefaultJars defaultJars = DefaultJars.none();

    private Themes themes = Themes.bundled();
    private ThemeFile themeFile = ThemeFile.none();

    private RuntimeFolders runtimeFolders = RuntimeFolders.none();

    /** The runtime jars leia knows and which of them are loaded (ADR 0072); null until they are known. */
    private GetRuntimesResult runtimes;

    /** The jars popup, while it is open; or null. */
    private JarsPopup jars;

    /** The window that downloads runtimes (ADR 0080), while it is open; or null. */
    private RuntimeWindow runtimeWindow;

    /** The last records of the log (ADR 0087). */
    private LogBuffer log = LogBuffer.detached();

    /** The log window, while it is open; or null. */
    private LogWindow logWindow;

    /** The level the log window shows; it outlasts the window. */
    private Level logFilter = log.logged();

    /** Where the log is written to, for the about screen; empty if it is dropped. */
    private Optional<Path> logFile = Optional.empty();

    /** Whether an open, reopen or close runs. */
    private boolean busy;

    /** The directory that a relative export path is resolved against. */
    private Optional<Path> cwd = Optional.empty();

    /** Where to export the terms of a field (ADR 0022) or a document (ADR 0030) to, while it is asked; or null. */
    private ExportPrompt exportPrompt;

    /**
     * The export that runs, or null. There is at most one. Each has an id, so that a reply for an
     * export that was stopped or forgotten is dropped, and a job that starts for one is cancelled.
     */
    private sealed interface RunningExport {
        long id();
    }

    /**
     * The terms of a field, written by the backend (ADR 0022).
     *
     * @param jobId null until the backend has started the job
     */
    private record TermsExport(long id, String field, Long jobId, long termsWritten) implements RunningExport {}

    /** The document is being fetched; the prompt says so. */
    private record Loading(long id, int docId, boolean indexedTerms, ExportTarget target) implements RunningExport {}

    /**
     * The indexed terms and points of the document are being rebuilt.
     *
     * @param jobId null until the backend has started the job
     */
    private record Rebuilding(
            long id, int docId, DocumentData data, ExportTarget target, Long jobId, long scanned, OptionalLong total)
            implements RunningExport {}

    /** The document is being written to its file. */
    private record Writing(long id, int docId) implements RunningExport {}

    private RunningExport export;

    /** The id of the next export. */
    private long nextExportId = 1;

    public App(VersionResult version, Optional<Path> home, History history, DirectoryImpl directory) {
        this.version = version;
        this.home = home;
        this.history = history;
        this.directory = directory;
    }

    /** Resolves relative export paths against {@code cwd}. */
    public App withCwd(Path cwd) {
        this.cwd = Optional.of(cwd);
        return this;
    }

    /** The jars popup shows the default folder, and marks the jars that came from it. */
    public App withDefaultJars(DefaultJars defaultJars) {
        this.defaultJars = defaultJars;
        return this;
    }

    /**
     * {@code T} on the start screen cycles {@code themes} and saves the theme it switches to in
     * {@code themeFile} (ADR 0095, ADR 0096).
     */
    public App withThemes(Themes themes, ThemeFile themeFile) {
        this.themes = themes;
        this.themeFile = themeFile;
        return this;
    }

    /** The jars popup shows the folder of runtimes (ADR 0044), and marks the jars that came from it. */
    public App withRuntimeFolders(RuntimeFolders runtimeFolders) {
        this.runtimeFolders = runtimeFolders;
        return this;
    }

    /**
     * The log window shows the records of {@code log}, from the level it logs at, and the about
     * screen says that {@code file} has them too.
     */
    public App withLog(LogBuffer log, Optional<Path> file) {
        this.log = log;
        this.logFilter = log.logged();
        this.logFile = file;
        return this;
    }

    /** The about screen says which runtimes are loaded, and why the others aren't (ADR 0072, ADR 0073). */
    public void runtimesLoaded(GetRuntimesResult runtimes) {
        this.runtimes = runtimes;
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
        return opened(stats, skipped, Feature.all(), "");
    }

    /**
     * As {@link #opened(GetIndexStatsResult, List)}, for an index that can do only {@code features}:
     * one that an old Lucene reads, whose version is {@code luceneRuntime} (ADR 0044). The screens it
     * can't show are refused, and the overview is shown if the shown screen is one of them.
     */
    public Optional<Action> opened(
            GetIndexStatsResult stats, List<SkippedIndex> skipped, Set<Feature> features, String luceneRuntime) {
        return opened(stats, skipped, features, luceneRuntime, Optional.empty());
    }

    /**
     * As {@link #opened(GetIndexStatsResult, List, Set, String)}, for an index that the Lucene of
     * {@code server}, an Elasticsearch or OpenSearch installation, reads (ADR 0090), which the tab line
     * names with it.
     */
    public Optional<Action> opened(
            GetIndexStatsResult stats,
            List<SkippedIndex> skipped,
            Set<Feature> features,
            String luceneRuntime,
            Optional<String> server) {
        history.add(stats.path());
        overview = new Overview(stats, topN).withSkipped(skipped);
        forgetScans();
        opened = new Opened(
                stats.path(),
                false,
                directory,
                stats.indexes().stream().map(IndexCommitStats::path).toList(),
                skipped.stream().map(SkippedIndex::path).toList(),
                features,
                readByTheNewestLucene(luceneRuntime) && server.isEmpty()
                        ? Optional.empty()
                        : Optional.of("Lucene " + stats.indexes().getFirst().createdVersionMajor() + ".x, read with "
                                + luceneRuntime
                                + server.map(name -> " (" + name + ")").orElse("")));
        if (!supports(screen)) {
            screen = Screen.OVERVIEW;
        }
        back.clear();
        commits = new Commits(indexNames(opened), true);
        check = new Check(stats.path(), opened.supports(Feature.CHECK_LEVELS), opened.supports(Feature.CHECK_THREADS));
        pointFormats = new PointFormats();
        terms = new Terms(stats.fields()).withPointFormats(pointFormats).withFeatures(opened.features());
        documents = newDocuments(stats, opened);
        analysis.indexChanged(indexedFields(stats), autoStopWordsReason(), false);
        search = newSearch(stats);
        prompt = null;
        forgetExport();
        busy = false;
        return initialRequest();
    }

    /** The search screen of {@code stats}, which parses with the analysis screen's analyzer. */
    private Search newSearch(GetIndexStatsResult stats) {
        Search fresh = new Search(stats.fields(), stats.maxDoc()).withFeatures(opened.features());
        fresh.setAnalyzer(analysis.settings());
        return fresh;
    }

    /** The fields as far as they make up the schema: without their term statistics. */
    private static List<IndexField> schema(List<IndexField> fields) {
        return fields.stream()
                .map(field -> new IndexField(
                        field.name(),
                        field.number(),
                        field.indexOptions(),
                        field.hasNorms(),
                        field.hasPayloads(),
                        field.hasTermVectors(),
                        field.docValuesType(),
                        field.pointDimensionCount(),
                        field.pointNumBytes(),
                        field.vectorDimension(),
                        field.vectorEncoding(),
                        field.vectorSimilarity(),
                        Optional.empty()))
                .toList();
    }

    /**
     * Shows the index opened without a reader (ADR 0021): only the commits and check screens work,
     * and the commits are shown unless the analysis or check screen is. The backend stopped the jobs
     * of the previous index. Returns what the shown screen has to fetch first.
     */
    public Optional<Action> openedWithoutReader(OpenIndexResult result) {
        history.add(result.path());
        overview = null;
        terms = null;
        documents = null;
        search = null;
        forgetScans();
        opened = new Opened(
                result.path(),
                true,
                directory,
                result.indexes().stream().map(IndexEntry::path).toList(),
                result.skipped().stream().map(SkippedIndex::path).toList(),
                result.features(),
                Optional.empty());
        commits = new Commits(indexNames(opened), false);
        check = new Check(result.path(), opened.supports(Feature.CHECK_LEVELS), opened.supports(Feature.CHECK_THREADS));
        analysis.indexChanged(List.of(), autoStopWordsReason(), false);
        if (screen != Screen.ANALYSIS && screen != Screen.CHECK) {
            screen = Screen.COMMITS;
        }
        back.clear();
        prompt = null;
        forgetExport();
        busy = false;
        return initialRequest();
    }

    /**
     * Shows the reopened index (ADR 0019) where the user was. The backend stopped the jobs of the old
     * index, and forgot the filter, which {@link #rebuildFilter} builds again. The screens offer what
     * {@code features} allow: on an index that a runtime reads, a new commit can add fields with doc
     * values, points or KNN vectors (ADR 0060). Returns what the shown screen has to fetch first.
     */
    public Optional<Action> reopened(GetIndexStatsResult stats, Set<Feature> features) {
        if (overview == null) {
            return opened(stats, List.of());
        }
        List<IndexCommitStats> indexes = stats.indexes();
        String reopened;
        if (indexes.size() == 1) {
            reopened = indexes.getFirst().segmentsFile();
        } else {
            List<IndexCommitStats> old = overview.stats().indexes();
            int changed = 0;
            for (int i = 0; i < Math.min(indexes.size(), old.size()); i++) {
                if (!indexes.get(i).segmentsFile().equals(old.get(i).segmentsFile())) {
                    changed++;
                }
            }
            reopened = changed + " of " + indexes.size() + INDEXES;
        }
        // Before anything below reads it.
        opened = new Opened(
                opened.path(),
                false,
                opened.directory(),
                indexes.stream().map(IndexCommitStats::path).toList(),
                opened.skipped(),
                features,
                opened.oldLucene());
        // A commit that dropped the last field with points, say, leaves a screen without its feature.
        if (!supports(screen)) {
            screen = Screen.OVERVIEW;
        }
        boolean sameFields = schema(overview.stats().fields()).equals(schema(stats.fields()));
        analysis.indexChanged(indexedFields(stats), autoStopWordsReason(), true);
        search = search != null
                ? search.reopened(stats.fields(), stats.maxDoc(), sameFields).withFeatures(features)
                : newSearch(stats);
        overview = overview.reopened(stats, topN);
        terms = terms != null
                ? terms.reopened(stats.fields()).withFeatures(features)
                : new Terms(stats.fields()).withPointFormats(pointFormats).withFeatures(opened.features());
        forgetScans();
        commits = new Commits(indexNames(opened), true);
        documents = documents != null
                ? documents
                        .reopened(stats.fields(), stats.maxDoc(), segmentStarts(stats))
                        .withFeatures(features)
                : newDocuments(stats, opened);
        syncTermsFilter();
        // The marks hold the old reader's terms, postings and documents: back shows the latest state.
        List<Back> jumps = back.stream().map(Back::withoutMarks).toList();
        back.clear();
        back.addAll(jumps);
        if (check != null) {
            check.stoppedByReopen();
        }
        if (export != null) {
            reopened += "; export stopped";
        }
        forgetExport();
        if (documents.filterToRebuild().isPresent()) {
            reopened += "; rebuilding the filter";
        }
        notice = new Notice.Reopened(reopened);
        busy = false;
        return initialRequest();
    }

    /** The documents screen of {@code stats}, which says which index directory a document is in. */
    private Documents newDocuments(GetIndexStatsResult stats, Opened opened) {
        List<String> names = indexNames(opened);
        List<Documents.IndexStart> starts = new ArrayList<>();
        for (int i = 0; i < Math.min(names.size(), stats.indexes().size()); i++) {
            IndexCommitStats index = stats.indexes().get(i);
            starts.add(new Documents.IndexStart(index.docBase(), names.get(i), index.createdVersionMajor()));
        }
        return new Documents(stats.fields(), stats.maxDoc())
                .withIndexes(starts)
                .withSegments(segmentStarts(stats))
                .withPointFormats(pointFormats)
                .withFeatures(opened.features());
    }

    /** The open reader's segments of every index directory, in document order (ADR 0051). */
    private static List<Documents.SegmentStart> segmentStarts(GetIndexStatsResult stats) {
        List<Documents.SegmentStart> segments = new ArrayList<>();
        for (int i = 0; i < stats.indexes().size(); i++) {
            for (ReaderSegment segment : stats.indexes().get(i).segments()) {
                segments.add(new Documents.SegmentStart(
                        i, segment.name(), segment.docBase(), segment.maxDoc(), segment.numDocs()));
            }
        }
        return segments;
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
                        : indexes.size() + INDEXES);
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
        analysis.indexChanged(List.of(), Optional.of(AutoStopWordsForm.NO_INDEX), false);
        opened = null;
        overview = null;
        commits = null;
        check = null;
        terms = null;
        documents = null;
        search = null;
        forgetScans();
        forgetExport();
        back.clear();
        busy = false;
    }

    /** The index directories as the screens show them: below the opened path (Rust's {@code index_names}). */
    private static List<String> indexNames(Opened opened) {
        return opened.indexes().stream()
                .map(index -> Overview.relative(opened.path(), index))
                .toList();
    }

    /** What the shown screen has to fetch first, if anything. */
    private Optional<Action> initialRequest() {
        if (screen == Screen.COMMITS && commits != null) {
            return commitsAction(commits.initialRequest());
        }
        if (screen == Screen.TERMS && terms != null) {
            return termsAction(terms.initialRequest());
        }
        if (screen == Screen.DOCUMENTS && documents != null) {
            return documentsAction(documents.initialRequest());
        }
        if (screen == Screen.ANALYSIS) {
            return analysisAction(analysis.initialRequest());
        }
        if (screen == Screen.CHECK && check != null) {
            return checkAction(check.initialRequest());
        }
        return Optional.empty();
    }

    /** Turns what the check screen needs into an action. */
    private Optional<Action> checkAction(Optional<Check.Request> request) {
        return request.flatMap(next -> switch (next) {
            case Check.Request.Start start ->
                Optional.of(new Action.StartCheck(start.checkId(), start.level(), start.threads(), start.only()));
            case Check.Request.LoadTargets _ -> Optional.of(new Action.LoadCheckTargets());
            case Check.Request.Unsupported unsupported -> {
                // Not reason(): an index without a reader may lack levels too.
                notice = new Notice.NotSupported(unsupported.what(), "not for " + oldLucene());
                yield Optional.empty();
            }
        });
    }

    /**
     * Turns what the analysis screen needs into an action. The search screen then has the analysis
     * screen's analysis, which a key may have changed, such as dropping a field's analyzer or the
     * auto stop words (ADR 0039).
     */
    private Optional<Action> analysisAction(Optional<Analysis.Request> request) {
        syncAnalyzer();
        return request.map(next -> switch (next) {
            case Analysis.Request.Components _ -> {
                analysisCalls++;
                yield new Action.LoadAnalysisComponents();
            }
            case Analysis.Request.Analyze analyze -> {
                analysisCalls++;
                yield new Action.Analyze(analyze.params());
            }
            case Analysis.Request.StartAutoStopWords start -> new Action.StartAutoStopWords(start.id(), start.params());
            case Analysis.Request.CancelAutoStopWords cancel -> new Action.CancelAutoStopWords(cancel.jobId());
        });
    }

    /** The search screen parses and likes with the analysis screen's analysis. */
    private void syncAnalyzer() {
        if (search != null) {
            search.setAnalyzer(analysis.settings());
        }
    }

    /** The job of the search for auto stop words {@code id} has started (ADR 0039). */
    public Optional<Action> autoStopWordsStarted(long id, long jobId) {
        return analysisAction(analysis.autoStopWordsStarted(id, jobId));
    }

    public void autoStopWordsProgress(long id, GetAutoStopWordsResult result) {
        analysis.autoStopWordsProgress(id, result);
        syncAnalyzer();
    }

    public void autoStopWordsFailed(long id, String error) {
        analysis.autoStopWordsFailed(id, error);
    }

    /** The indexed fields of {@code stats}, which the analysis screen offers (ADR 0039). */
    private static List<String> indexedFields(GetIndexStatsResult stats) {
        return stats.fields().stream()
                .filter(field -> field.indexOptions() != IndexOptions.NONE)
                .map(IndexField::name)
                .toList();
    }

    public void componentsLoaded(GetAnalysisComponentsResult components) {
        analysisCalls--;
        analysis.componentsLoaded(components);
    }

    public void componentsFailed(String error) {
        analysisCalls--;
        analysis.componentsFailed(error);
    }

    /** No loaded runtime has analysis (ADR 0071): {@code getAnalysisComponents} is {@code NOT_SUPPORTED}. */
    public void noAnalysis() {
        analysisCalls--;
        analysis.noAnalysis();
    }

    /** Whether the analysis screen waits for a reply. */
    boolean analyzing() {
        return analysisCalls > 0;
    }

    public void jarsLoaded(List<String> loaded) {
        if (jars != null) {
            jars.jarsLoaded(loaded);
        }
    }

    public void jarsFailed(String error) {
        if (jars != null) {
            jars.jarsFailed(error);
        }
    }

    /**
     * Shows what the jars added. Returns the analysis components to fetch again if new jars were
     * loaded and the components had been fetched: the jars may have added some.
     */
    public Optional<Action> jarsAdded(AddJarsResult result) {
        if (jars != null) {
            jars.added(result);
        }
        if (result.jars().isEmpty() || !analysis.hasComponents()) {
            return Optional.empty();
        }
        return analysisAction(Optional.of(new Analysis.Request.Components()));
    }

    public void addJarsFailed(String error) {
        if (jars != null) {
            jars.addFailed(error);
        }
    }

    /** Whether the jars popup waits for the jars to be listed or added. */
    boolean jarsWaiting() {
        return jars != null && jars.waiting();
    }

    /**
     * What {@code install} found for the runtimes of {@link Action.PlanRuntimes}: {@code plan} for
     * those to offer, {@code present} for those that are only shown.
     */
    public void runtimesPlanned(
            RuntimeInstall install, RuntimeInstall.Plan plan, List<RuntimeInstall.RuntimePlan> present) {
        if (runtimeWindow != null) {
            runtimeWindow.planned(install, plan, present);
        }
    }

    public void runtimesPlanFailed(String message) {
        if (runtimeWindow != null) {
            runtimeWindow.planFailed(message);
        }
    }

    /**
     * The download has ended. Returns {@link Action.LoadRuntimes} if a runtime was installed, so that
     * the backend loads it (ADR 0080).
     */
    public Optional<Action> runtimesInstalled(RuntimeInstall.Outcome outcome) {
        // The window takes no Esc while it installs, so it is still open.
        if (runtimeWindow != null && runtimeWindow.finished(outcome)) {
            return Optional.of(new Action.LoadRuntimes());
        }
        return Optional.empty();
    }

    /** What the backend made of the new runtimes; the about screen shows their states now. */
    public void runtimesReloaded(LoadRuntimesResult result) {
        runtimes = new GetRuntimesResult(result.runtimes(), runtimes == null ? List.of() : runtimes.servers());
        if (runtimeWindow != null) {
            runtimeWindow.loaded(result);
        }
    }

    public void reloadRuntimesFailed(String message) {
        if (runtimeWindow != null) {
            runtimeWindow.loadFailed(message);
        }
    }

    /** Whether the window that downloads runtimes shows something that moves. */
    boolean animating() {
        return runtimeWindow != null && runtimeWindow.animating();
    }

    /** A tick of the UI's clock; returns whether something moved, so that the screen is drawn again. */
    boolean tick() {
        boolean logged = logWindow != null && logWindow.changed();
        if (runtimeWindow == null) {
            return logged;
        }
        runtimeWindow.tick();
        return runtimeWindow.animating() || logged;
    }

    /**
     * {@code D} on the start screen: opens the window that downloads runtimes (ADR 0080), for the
     * runtimes that are missing or incomplete. Those are never loaded, so no jar that is open gets
     * written. A loaded, refused or restart one is planned only to be shown, apart from the install's
     * plan, so that the install never sees it (ADR 0081).
     */
    private Optional<Action> openRuntimeWindow() {
        if (runtimeFolders.dir().isEmpty()) {
            notice = new Notice.CannotDownload("leia doesn't run from leia.jar, so it has no runtimes folder");
            return Optional.empty();
        }
        List<RuntimeFolder> states = runtimes == null ? List.of() : runtimes.runtimes();
        List<String> needing = states.stream()
                .filter(runtime ->
                        runtime.state() == RuntimeState.MISSING || runtime.state() == RuntimeState.INCOMPLETE)
                .map(RuntimeFolder::folder)
                .toList();
        List<String> there = states.stream()
                .filter(runtime -> runtime.state() == RuntimeState.LOADED
                        || runtime.state() == RuntimeState.RESTART
                        || runtime.state() == RuntimeState.REFUSED)
                .map(RuntimeFolder::folder)
                .toList();
        List<Known> known = io.github.mfuerstenau.leia.server.runtime.RuntimeFolders.KNOWN.stream()
                .filter(runtime -> needing.contains(runtime.folder()))
                .toList();
        List<Known> present = io.github.mfuerstenau.leia.server.runtime.RuntimeFolders.KNOWN.stream()
                .filter(runtime -> there.contains(runtime.folder()))
                .toList();
        runtimeWindow = new RuntimeWindow(states);
        return Optional.of(new Action.PlanRuntimes(known, present));
    }

    /**
     * Shows what the analyzer made of {@code text}. An analyzer that the builder applied is now the
     * current one. Returns the components to fetch if there are none yet, as when {@code a} on the
     * documents screen opened the screen.
     */
    public Optional<Action> analyzed(String text, AnalyzeResult result) {
        analysisCalls--;
        analysis.analyzed(text, result);
        return analysisAction(analysis.initialRequest());
    }

    public Optional<Action> analyzeFailed(String error) {
        analysisCalls--;
        analysis.analyzeFailed(error);
        return analysisAction(analysis.initialRequest());
    }

    /**
     * Turns what the commits screen needs into an action; a segment's documents switch to the
     * documents screen, pinned to the segment (ADR 0051), if the open reader has it.
     */
    private Optional<Action> commitsAction(Optional<Commits.Request> request) {
        return request.flatMap(next -> switch (next) {
            case Commits.Request.LoadList list -> Optional.of(new Action.LoadCommits(list.index()));
            case Commits.Request.LoadCommit commit ->
                Optional.of(new Action.LoadCommit(commit.index(), commit.generation()));
            case Commits.Request.ShowDocuments show -> {
                if (documents == null) {
                    yield Optional.<Action>empty();
                }
                Optional<Documents.SegmentStart> segment = documents.segment(show.index(), show.segment());
                if (segment.isEmpty()) {
                    // A segment of an older commit, merged away since.
                    notice = new Notice.NotOpened(show.segment());
                    yield Optional.<Action>empty();
                }
                jump(Screen.DOCUMENTS);
                yield documentsAction(Optional.of(documents.showSegment(segment.get())));
            }
        });
    }

    /** Shows the commits of the index directory at {@code index}. Returns the selected commit to fetch. */
    public Optional<Action> commitsLoaded(int index, GetCommitsResult result) {
        return commits == null ? Optional.empty() : commitsAction(commits.commitsLoaded(index, result));
    }

    public void commitsFailed(int index, String error) {
        if (commits != null) {
            commits.commitsFailed(index, error);
        }
    }

    public void commitLoaded(int index, long generation, GetCommitResult result) {
        if (commits != null) {
            commits.commitLoaded(index, generation, result);
        }
    }

    public void commitFailed(int index, long generation, String error) {
        if (commits != null) {
            commits.commitFailed(index, generation, error);
        }
    }

    /**
     * Turns what the terms screen needs into an action; a document to show switches to the documents
     * screen, and an export opens its prompt.
     */
    private Optional<Action> termsAction(Optional<Terms.Request> request) {
        return request.flatMap(next -> switch (next) {
            case Terms.Request.LoadTerms load ->
                Optional.of(new Action.LoadTerms(load.field(), load.from(), load.more()));
            case Terms.Request.LoadPostings load ->
                Optional.of(
                        new Action.LoadPostings(load.field(), load.term(), load.fromDoc(), load.more(), load.filter()));
            case Terms.Request.Document document -> {
                if (documents == null) {
                    yield Optional.<Action>empty();
                }
                documents.showHit(document.hit());
                jump(Screen.DOCUMENTS);
                yield loadDocument(document.hit().docId());
            }
            case Terms.Request.LoadPointTree load -> Optional.of(new Action.LoadPointTree(load.field(), load.serial()));
            case Terms.Request.LoadPointCell load ->
                Optional.of(new Action.LoadPointCell(load.field(), load.serial(), load.segment(), load.path()));
            case Terms.Request.PointDocument document -> {
                if (documents == null) {
                    yield Optional.<Action>empty();
                }
                documents.showPoint(document.field());
                jump(Screen.DOCUMENTS);
                yield loadDocument(document.docId());
            }
            case Terms.Request.LoadKnnGraph load -> Optional.of(new Action.LoadKnnGraph(load.field(), load.serial()));
            case Terms.Request.LoadKnnNodes load ->
                Optional.of(new Action.LoadKnnNodes(
                        load.field(), load.serial(), load.segment(), load.level(), load.offset()));
            case Terms.Request.LoadKnnNode load ->
                Optional.of(new Action.LoadKnnNode(
                        load.field(), load.serial(), load.segment(), load.ord(), load.docId(), load.walk()));
            case Terms.Request.VectorDocument document -> {
                if (documents == null) {
                    yield Optional.<Action>empty();
                }
                documents.showPoint(document.field());
                jump(Screen.DOCUMENTS);
                yield loadDocument(document.docId());
            }
            case Terms.Request.Search searched -> {
                if (search == null) {
                    yield Optional.<Action>empty();
                }
                jump(Screen.SEARCH);
                yield searchAction(search.termQuery(searched.field(), searched.term()));
            }
            case Terms.Request.Export asked -> {
                if (export != null) {
                    notice = new Notice.ExportRunning();
                } else {
                    exportPrompt = ExportPrompt.terms(asked.field(), home, cwd);
                }
                yield Optional.<Action>empty();
            }
            case Terms.Request.Unsupported unsupported -> notSupported(unsupported.what());
        });
    }

    /**
     * Turns what the search screen needs into an action; a document or value to show, or a filter to
     * browse, switches to the documents screen.
     */
    private Optional<Action> searchAction(Optional<Search.Request> request) {
        return request.flatMap(next -> switch (next) {
            case Search.Request.Start start ->
                Optional.of(new Action.StartSearch(start.searchId(), start.params(), start.cancel()));
            case Search.Request.Explain explain ->
                Optional.of(new Action.StartExplain(explain.explainId(), explain.params(), explain.cancel()));
            case Search.Request.Highlight highlight ->
                Optional.of(new Action.StartHighlight(highlight.highlightId(), highlight.params(), highlight.cancel()));
            case Search.Request.StoredScan storedScan ->
                Optional.of(new Action.StartStoredScan(storedScan.scanId(), storedScan.params(), storedScan.cancel()));
            case Search.Request.Recall recall ->
                Optional.of(new Action.StartKnnRecall(recall.recallId(), recall.params(), recall.cancel()));
            case Search.Request.Cancel cancel -> Optional.of(new Action.CancelSearch(cancel.jobs()));
            case Search.Request.Filter filter -> startFilter(filter.label(), filter.params(), filter.startDoc());
            case Search.Request.Document document -> {
                if (documents == null) {
                    yield Optional.<Action>empty();
                }
                documents.showSearchHit(document.docId(), search.matchesQuery());
                jump(Screen.DOCUMENTS);
                yield loadDocument(document.docId());
            }
            case Search.Request.View view -> {
                if (documents == null) {
                    yield Optional.<Action>empty();
                }
                documents.showSearchHit(view.docId(), search.matchesQuery());
                jump(Screen.DOCUMENTS);
                Optional<Action> action = documentsAction(documents.viewMatches(
                        view.docId(), view.field(), view.index(), view.spans(), view.matchCount()));
                documents.forgetUnsentMatches();
                yield action;
            }
            case Search.Request.Unsupported unsupported -> notSupported(unsupported.what());
        });
    }

    /** Whether the search screen runs a search, an explanation or a regex scan. */
    boolean searching() {
        return search != null && search.running();
    }

    private static Optional<Action> cancelSearchJob(OptionalLong job) {
        return job.isPresent() ? Optional.of(new Action.CancelSearch(List.of(job.getAsLong()))) : Optional.empty();
    }

    /** The job of search {@code searchId} has started; the job of a search that is gone is cancelled. */
    public Optional<Action> searchStarted(long searchId, long jobId) {
        return cancelSearchJob(search == null ? OptionalLong.of(jobId) : search.searchStarted(searchId, jobId));
    }

    /** Shows the progress of search {@code searchId}, or its hits. Returns the next page to fetch, or the document to step to. */
    public Optional<Action> searchProgress(long searchId, GetSearchResult result) {
        return search == null ? Optional.empty() : searchAction(search.searchProgress(searchId, result));
    }

    public void searchFailed(long searchId, String error) {
        if (search != null) {
            search.searchFailed(searchId, error);
        }
    }

    /** The job of explanation {@code explainId} has started; the job of one that is gone is cancelled. */
    public Optional<Action> explainStarted(long explainId, long jobId) {
        return cancelSearchJob(search == null ? OptionalLong.of(jobId) : search.explainStarted(explainId, jobId));
    }

    public void explainProgress(long explainId, GetExplainResult result) {
        if (search != null) {
            search.explainProgress(explainId, result);
        }
    }

    public void explainFailed(long explainId, String error) {
        if (search != null) {
            search.explainFailed(explainId, error);
        }
    }

    /** The job of highlight {@code highlightId} has started; the job of one that is gone is cancelled. */
    public Optional<Action> highlightStarted(long highlightId, long jobId) {
        return cancelSearchJob(search == null ? OptionalLong.of(jobId) : search.highlightStarted(highlightId, jobId));
    }

    /** Takes the matches of highlight {@code highlightId}; once they are in, views the value with them. */
    public Optional<Action> highlightProgress(long highlightId, GetHighlightResult result) {
        return search == null ? Optional.empty() : searchAction(search.highlightProgress(highlightId, result));
    }

    public void highlightFailed(long highlightId, String error) {
        if (search != null) {
            search.highlightFailed(highlightId, error);
        }
    }

    /** The job of regex scan {@code scanId} has started; the job of one that is gone is cancelled. */
    public Optional<Action> storedScanStarted(long scanId, long jobId) {
        return cancelSearchJob(search == null ? OptionalLong.of(jobId) : search.storedScanStarted(scanId, jobId));
    }

    public void storedScanProgress(long scanId, GetStoredScanResult result) {
        if (search != null) {
            search.storedScanProgress(scanId, result);
        }
    }

    public void storedScanFailed(long scanId, String error) {
        if (search != null) {
            search.storedScanFailed(scanId, error);
        }
    }

    /** The job of recall check {@code recallId} has started; the job of one that is gone is cancelled. */
    public Optional<Action> knnRecallStarted(long recallId, long jobId) {
        return cancelSearchJob(search == null ? OptionalLong.of(jobId) : search.knnRecallStarted(recallId, jobId));
    }

    public void knnRecallProgress(long recallId, GetKnnRecallResult result) {
        if (search != null) {
            search.knnRecallProgress(recallId, result);
        }
    }

    public void knnRecallFailed(long recallId, String error) {
        if (search != null) {
            search.knnRecallFailed(recallId, error);
        }
    }

    /**
     * Fetches a document for the documents screen, which stops rebuilding a field of the shown one,
     * finds out whether it matches the filter if that isn't known yet, and finds the matches of the
     * search hit it is (ADR 0043).
     */
    private Optional<Action> loadDocument(int docId) {
        List<Long> cancel = new ArrayList<>();
        documents.stopRebuild().ifPresent(cancel::add);
        Optional<Documents.MatchesStart> matches = documents.matchesFor(docId, cancel);
        Optional<Documents.Locate> locate = documents.filterToLocate(docId);
        documents.want(docId);
        documentFetches++;
        return Optional.of(new Action.LoadDocument(docId, List.copyOf(cancel), locate, matches));
    }

    /**
     * Starts a filter on the documents screen (ADR 0031) on {@code label}, built from {@code params},
     * which starts on {@code startDoc} once it is built, and shows that screen, as {@code b} on the
     * search screen asks for.
     */
    public Optional<Action> startFilter(String label, StartFilterParams params, int startDoc) {
        if (documents == null) {
            return Optional.empty();
        }
        jump(Screen.DOCUMENTS);
        return documentsAction(Optional.of(documents.startFilter(label, params, startDoc)));
    }

    /**
     * Builds the filter of the documents screen again after a reopen (ADR 0032): the backend forgot
     * it with the old index. Reported back with {@link #filterStarted} or {@link #filterFailed}.
     */
    public Optional<Action> rebuildFilter() {
        if (documents == null) {
            return Optional.empty();
        }
        return documentsAction(documents.filterToRebuild().map(Documents.Request.class::cast));
    }

    /** Hands the filter of the documents screen, once it is built, to the terms screen, which marks its postings with it (ADR 0032). */
    private void syncTermsFilter() {
        if (terms != null) {
            terms.setFilter(documents != null ? documents.filterId() : OptionalLong.empty());
        }
    }

    /** Turns what the documents screen needs into an action; a search or an analysis switches to that screen. */
    private Optional<Action> documentsAction(Optional<Documents.Request> request) {
        // A key or a step may have built, failed or cleared the filter.
        syncTermsFilter();
        if (request.isEmpty() || documents == null) {
            return Optional.empty();
        }
        return switch (request.get()) {
            case Documents.Request.Document document -> loadDocument(document.docId());
            case Documents.Request.TermVector load -> {
                documentFetches++;
                yield Optional.of(new Action.LoadTermVector(load.docId(), load.field()));
            }
            case Documents.Request.Positions load -> {
                documentFetches++;
                yield Optional.of(new Action.LoadPositions(load.docId(), load.field(), load.term()));
            }
            case Documents.Request.StoredValue load -> {
                documentFetches++;
                yield Optional.of(new Action.LoadStoredValue(load.docId(), load.field(), load.index()));
            }
            case Documents.Request.Copy copy -> Optional.of(new Action.Copy(copy.text()));
            case Documents.Request.Reconstruct rebuild ->
                Optional.of(new Action.StartReconstruct(rebuild.docId(), rebuild.field(), rebuild.cancel()));
            case Documents.Request.StartFilter start ->
                Optional.of(new Action.StartFilter(start.serial(), start.params(), start.cancel(), start.release()));
            case Documents.Request.StepFilter step -> {
                documentFetches++;
                yield Optional.of(new Action.StepFilter(step.serial(), step.params(), step.step()));
            }
            case Documents.Request.ClearFilter clear ->
                Optional.of(new Action.ClearFilter(clear.cancel(), clear.release()));
            case Documents.Request.Unsupported unsupported -> notSupported(unsupported.what());
            case Documents.Request.Export asked -> {
                if (export != null) {
                    notice = new Notice.ExportRunning();
                } else {
                    exportPrompt = ExportPrompt.document(
                            asked.docId(), indexDirs(), opened.supports(Feature.RECONSTRUCT), home, cwd);
                }
                yield Optional.empty();
            }
            case Documents.Request.Step step
            when step.walk() == Documents.Walk.TERM_DOCS ->
                terms == null ? Optional.empty() : termsAction(terms.step(step.forward()));
            case Documents.Request.Analyze analyze -> {
                jump(Screen.ANALYSIS);
                yield analysisAction(
                        analysis.analyzeValue("doc " + analyze.docId() + " · " + analyze.field(), analyze.text()));
            }
            case Documents.Request.Step step ->
                search == null ? Optional.empty() : searchAction(search.step(step.forward()));
            case Documents.Request.MoreLikeThis like -> {
                if (search == null) {
                    yield Optional.<Action>empty();
                }
                jump(Screen.SEARCH);
                yield searchAction(search.moreLikeThis(like.docId()));
            }
            case Documents.Request.SearchKnn knn -> {
                if (search == null) {
                    yield Optional.<Action>empty();
                }
                jump(Screen.SEARCH);
                yield searchAction(search.knnQuery(knn.field(), knn.docId()));
            }
            case Documents.Request.SearchDocValues values -> {
                if (search == null) {
                    yield Optional.<Action>empty();
                }
                jump(Screen.SEARCH);
                yield searchAction(
                        search.docValuesQuery(values.field(), values.number(), values.hex(), values.values()));
            }
            case Documents.Request.ShowCommit show -> {
                if (commits == null) {
                    yield Optional.<Action>empty();
                }
                jump(Screen.COMMITS);
                yield commitsAction(commits.showSegment(show.index(), show.segment()));
            }
            case Documents.Request.BrowseTerms browse -> {
                if (terms == null) {
                    yield Optional.<Action>empty();
                }
                jump(Screen.TERMS);
                yield termsAction(terms.show(browse.field(), browse.term(), browse.points()));
            }
            case Documents.Request.BrowseGraph browse -> {
                if (terms == null) {
                    yield Optional.<Action>empty();
                }
                jump(Screen.TERMS);
                yield termsAction(terms.showNode(browse.field(), browse.docId()));
            }
        };
    }

    /** Shows the document, if it is the one asked for. Returns where the term occurs in it, if the terms screen opened it. */
    public Optional<Action> documentLoaded(GetDocumentResult document) {
        documentFetches--;
        if (documents == null || !documents.loaded(document)) {
            return Optional.empty();
        }
        // A ←/→ through the filter pressed while this one was fetched goes on from it.
        Optional<Documents.Request> next = documents.queuedStep();
        if (next.isEmpty()) {
            next = documents.positionsRequest();
        }
        return documentsAction(next.isPresent() ? next : documents.openPendingView());
    }

    /** The job of document matches {@code matchesId} has started; the job of ones that are gone is cancelled. */
    public Optional<Action> documentMatchesStarted(long matchesId, long jobId) {
        return cancelSearchJob(documents == null ? OptionalLong.of(jobId) : documents.matchesStarted(matchesId, jobId));
    }

    public void documentMatchesProgress(long matchesId, GetHighlightResult result) {
        if (documents != null) {
            documents.matchesProgress(matchesId, result);
        }
    }

    public void documentMatchesFailed(long matchesId, String error) {
        if (documents != null) {
            documents.matchesFailed(matchesId, error);
        }
    }

    public void documentFailed(int docId, String error) {
        documentFetches--;
        if (documents != null) {
            documents.failed(docId, error);
        }
    }

    public void storedValueLoaded(int docId, String field, int index, StoredValue value) {
        documentFetches--;
        if (documents != null) {
            documents.storedValueLoaded(docId, field, index, value);
        }
    }

    public void storedValueFailed(int docId, String field, int index, String error) {
        documentFetches--;
        if (documents != null) {
            documents.storedValueFailed(docId, field, index, error);
        }
    }

    public void termVectorLoaded(int docId, String field, List<TermVectorTerm> terms) {
        documentFetches--;
        if (documents != null) {
            documents.termVectorLoaded(docId, field, terms);
        }
    }

    public void termVectorFailed(int docId, String field, String error) {
        documentFetches--;
        if (documents != null) {
            documents.termVectorFailed(docId, field, error);
        }
    }

    public void positionsLoaded(int docId, String field, BytesValue term, List<TermPosition> positions) {
        documentFetches--;
        if (documents != null) {
            documents.positionsLoaded(docId, field, term, positions);
        }
    }

    public void positionsFailed(int docId, String field, BytesValue term, String error) {
        documentFetches--;
        if (documents != null) {
            documents.positionsFailed(docId, field, term, error);
        }
    }

    /** Whether the documents screen waits for a fetch. */
    boolean loadingDocuments() {
        return documentFetches > 0;
    }

    /** Whether an export runs: of terms, or of a document from its fetch to its write. */
    boolean exporting() {
        return export != null;
    }

    /** Whether the documents screen rebuilds a field, or builds its filter. */
    boolean documentJobs() {
        return documents != null && (documents.rebuilding() || documents.filterBuilding());
    }

    /**
     * The job of the rebuild of {@code field} of {@code docId} has started. If that rebuild was
     * stopped meanwhile, returns the action that cancels the job.
     */
    public Optional<Action> reconstructStarted(int docId, String field, long jobId) {
        if (documents != null && documents.reconstructStarted(docId, field, jobId)) {
            return Optional.empty();
        }
        return Optional.of(new Action.CancelReconstruct(jobId));
    }

    public void reconstructStartFailed(int docId, String field, String error) {
        if (documents != null) {
            documents.reconstructStartFailed(docId, field, error);
        }
    }

    /** Shows the progress of rebuild job {@code jobId}, or the rebuilt field. */
    public void reconstructProgress(long jobId, GetReconstructResult result) {
        if (documents != null) {
            documents.reconstructProgress(jobId, result);
        }
    }

    public void reconstructFailed(long jobId, String error) {
        if (documents != null) {
            documents.reconstructFailed(jobId, error);
        }
    }

    /**
     * The job that builds filter {@code serial} has started. If that filter was replaced or cleared
     * meanwhile, returns the action that cancels the job.
     */
    public Optional<Action> filterStarted(long serial, long jobId) {
        if (documents != null && documents.filterStarted(serial, jobId)) {
            return Optional.empty();
        }
        return Optional.of(new Action.ClearFilter(OptionalLong.of(jobId), OptionalLong.empty()));
    }

    /**
     * Takes the progress of the job that builds filter {@code serial}. Returns the step to the first
     * document to show once it is built.
     */
    public Optional<Action> filterProgress(long serial, GetFilterResult result) {
        return documents == null ? Optional.empty() : documentsAction(documents.filterProgress(serial, result));
    }

    /** Building filter {@code serial}, or locating a document in it, failed. */
    public void filterFailed(long serial, String error) {
        if (documents != null) {
            documents.filterFailed(serial, error);
        }
        syncTermsFilter();
    }

    /** Takes what {@code stepFilter} found. Returns the document to show, if any. */
    public Optional<Action> filterStepped(
            long serial, StepFilterParams params, FilterStep step, Optional<FilterMatch> found) {
        documentFetches--;
        return filterFound(serial, params, step, found);
    }

    /** Stepping through filter {@code serial} failed. */
    public void filterStepFailed(long serial, String error) {
        documentFetches--;
        filterFailed(serial, error);
    }

    /**
     * Takes what {@code stepFilter} found for a document being loaded, which is counted with the
     * document (ADR 0031).
     */
    public void filterLocated(long serial, StepFilterParams params, Optional<FilterMatch> found) {
        filterFound(serial, params, FilterStep.LOCATE, found);
    }

    private Optional<Action> filterFound(
            long serial, StepFilterParams params, FilterStep step, Optional<FilterMatch> found) {
        return documents == null
                ? Optional.empty()
                : documentsAction(documents.filterStepped(serial, params, step, found));
    }

    /** Starts the export the prompt asks for: the prompt says "Exporting…" until it has started. */
    private Optional<Action> startExport(ExportPrompt.ExportRequest request) {
        return switch (request) {
            case ExportPrompt.ExportRequest.Terms termsRequest -> {
                exportPrompt.setBusy();
                long id = nextExportId++;
                export = new TermsExport(id, termsRequest.params().field(), null, 0);
                yield Optional.of(new Action.StartExportTerms(id, termsRequest.params()));
            }
            case ExportPrompt.ExportRequest.Document document -> {
                exportPrompt.setBusy();
                long id = nextExportId++;
                export = new Loading(id, document.docId(), document.indexedTerms(), document.target());
                yield Optional.of(
                        new Action.LoadDocumentExport(id, document.docId(), opened.supports(Feature.TERM_VECTORS)));
            }
        };
    }

    /** The index directories of the open path, opened or skipped: an export never writes there. */
    private List<String> indexDirs() {
        if (opened == null) {
            return List.of();
        }
        List<String> dirs = new ArrayList<>(opened.indexes());
        dirs.addAll(opened.skipped());
        return List.copyOf(dirs);
    }

    /** The file as it would be typed: below the working directory, relative to it. */
    private String shownPath(String path) {
        return cwd.map(dir -> Overview.relative(dir.toString(), path)).orElse(path);
    }

    private void forgetExport() {
        exportPrompt = null;
        export = null;
    }

    /**
     * The document of export {@code exportId} is in. Returns the rebuild of its indexed terms, or,
     * without them, where the export goes.
     */
    public Optional<Action> documentExportLoaded(long exportId, DocumentData data) {
        if (!(export instanceof Loading loading) || loading.id() != exportId) {
            return Optional.empty();
        }
        if (!loading.indexedTerms()) {
            return finishDocumentExport(exportId, data, Optional.empty(), loading.target());
        }
        export = new Rebuilding(exportId, loading.docId(), data, loading.target(), null, 0, OptionalLong.empty());
        return Optional.of(new Action.StartDocumentExport(exportId, loading.docId()));
    }

    /**
     * The job of export {@code exportId} runs, which writes the terms or rebuilds the document's: the
     * prompt closes, and the tab line shows the progress. If that export was stopped or forgotten
     * meanwhile, returns the action that cancels the job.
     */
    public Optional<Action> exportStarted(long exportId, long jobId) {
        if (export instanceof TermsExport termsExport && termsExport.id() == exportId && termsExport.jobId() == null) {
            exportPrompt = null;
            export = new TermsExport(exportId, termsExport.field(), jobId, 0);
            return Optional.empty();
        }
        if (!(export instanceof Rebuilding rebuilding) || rebuilding.id() != exportId || rebuilding.jobId() != null) {
            return Optional.of(new Action.CancelExport(jobId));
        }
        exportPrompt = null;
        export = new Rebuilding(
                exportId,
                rebuilding.docId(),
                rebuilding.data(),
                rebuilding.target(),
                jobId,
                rebuilding.scanned(),
                rebuilding.total());
        return Optional.empty();
    }

    /** Shows the progress of terms export {@code exportId}, or where it wrote the terms. */
    public void termsExportProgress(long exportId, GetExportTermsResult result) {
        if (!(export instanceof TermsExport termsExport) || termsExport.id() != exportId) {
            return;
        }
        Optional<ExportTermsResult> exported = result.result();
        if (exported.isPresent()) {
            forgetExport();
            notice = new Notice.Exported(
                    "Exported " + TextFormat.count(exported.get().terms(), "term") + " to "
                            + shownPath(exported.get().path()));
            return;
        }
        export = new TermsExport(exportId, termsExport.field(), termsExport.jobId(), result.termsWritten());
    }

    /** Shows the progress of the rebuild. Returns where the export goes once it is done. */
    public Optional<Action> documentExportProgress(long exportId, GetReconstructResult result) {
        if (!(export instanceof Rebuilding rebuilding) || rebuilding.id() != exportId) {
            return Optional.empty();
        }
        Optional<ReconstructResult> rebuilt = result.result();
        if (rebuilt.isPresent()) {
            return finishDocumentExport(
                    exportId, rebuilding.data(), Optional.of(rebuilt.get().fields()), rebuilding.target());
        }
        export = new Rebuilding(
                exportId,
                rebuilding.docId(),
                rebuilding.data(),
                rebuilding.target(),
                rebuilding.jobId(),
                result.scanned(),
                result.total());
        return Optional.empty();
    }

    /** Puts the export together: the file to write, or the text to copy. */
    private Optional<Action> finishDocumentExport(
            long exportId, DocumentData data, Optional<List<ReconstructedField>> rebuilt, ExportTarget target) {
        if (documents == null || opened == null) {
            forgetExport();
            return Optional.empty();
        }
        int docId = data.document().docId();
        Documents shown = documents;
        String text = DocumentExport.toJson(
                DocumentExport.assemble(data, rebuilt, shown::field, opened.path(), shown.indexOf(docId)));
        return switch (target) {
            case ExportTarget.File file -> {
                export = new Writing(exportId, docId);
                yield Optional.of(
                        new Action.WriteExport(exportId, docId, file.path(), text, file.overwrite(), indexDirs()));
            }
            case ExportTarget.Clipboard _ -> {
                forgetExport();
                long bytes = text.getBytes(StandardCharsets.UTF_8).length;
                notice = new Notice.Exported("Copied document " + docId + " as JSON, " + TextFormat.size(bytes));
                yield Optional.of(new Action.Copy(text));
            }
        };
    }

    /** Document {@code docId} of export {@code exportId} was written to {@code path}. */
    public void documentExported(long exportId, int docId, String path) {
        if (export == null || export.id() != exportId) {
            return;
        }
        forgetExport();
        notice = new Notice.Exported("Exported document " + docId + " to " + shownPath(path));
    }

    /** Shows why export {@code exportId} couldn't start in its prompt, or why it failed once running. */
    public void exportFailed(long exportId, String error) {
        if (export == null || export.id() != exportId) {
            return;
        }
        export = null;
        if (exportPrompt != null) {
            exportPrompt.setError(error);
        } else {
            notice = new Notice.ExportFailed(error);
        }
    }

    private static Terms.Request.LoadTerms request(Action.LoadTerms load) {
        return new Terms.Request.LoadTerms(load.field(), load.from(), load.more());
    }

    private static Terms.Request.LoadPostings request(Action.LoadPostings load) {
        return new Terms.Request.LoadPostings(load.field(), load.term(), load.fromDoc(), load.more(), load.filter());
    }

    /** Shows a page of terms. Returns what the terms screen needs next. */
    public Optional<Action> termsLoaded(Action.LoadTerms load, GetTermsResult result) {
        return terms == null ? Optional.empty() : termsAction(terms.termsLoaded(request(load), result));
    }

    public Optional<Action> termsFailed(Action.LoadTerms load, String error) {
        return terms == null ? Optional.empty() : termsAction(terms.termsFailed(request(load), error));
    }

    /** Shows a page of postings. Returns what the terms screen needs next. */
    public Optional<Action> postingsLoaded(Action.LoadPostings load, GetPostingsResult result) {
        return terms == null ? Optional.empty() : termsAction(terms.postingsLoaded(request(load), result));
    }

    /** Shows the BKD tree of a point field. Returns what the keys held for it ask for. */
    public Optional<Action> pointTreeLoaded(Action.LoadPointTree load, GetPointTreeResult result) {
        return terms == null
                ? Optional.empty()
                : termsAction(
                        terms.pointTreeLoaded(new Terms.Request.LoadPointTree(load.field(), load.serial()), result));
    }

    public Optional<Action> pointTreeFailed(Action.LoadPointTree load, String error) {
        return terms == null
                ? Optional.empty()
                : termsAction(
                        terms.pointTreeFailed(new Terms.Request.LoadPointTree(load.field(), load.serial()), error));
    }

    /** Shows a cell of a BKD tree. */
    public Optional<Action> pointCellLoaded(Action.LoadPointCell load, GetPointCellResult result) {
        if (terms != null) {
            terms.pointCellLoaded(request(load), result);
        }
        return Optional.empty();
    }

    public Optional<Action> pointCellFailed(Action.LoadPointCell load, String error) {
        if (terms != null) {
            terms.pointCellFailed(request(load), error);
        }
        return Optional.empty();
    }

    private static Terms.Request.LoadPointCell request(Action.LoadPointCell load) {
        return new Terms.Request.LoadPointCell(load.field(), load.serial(), load.segment(), load.path());
    }

    /**
     * Shows the HNSW graphs of a vector field (ADR 0085). Returns the node the terms screen waited for,
     * or what the keys held for the graphs ask for.
     */
    public Optional<Action> knnGraphLoaded(Action.LoadKnnGraph load, GetKnnGraphResult result) {
        return terms == null
                ? Optional.empty()
                : termsAction(
                        terms.knnGraphLoaded(new Terms.Request.LoadKnnGraph(load.field(), load.serial()), result));
    }

    public Optional<Action> knnGraphFailed(Action.LoadKnnGraph load, String error) {
        return terms == null
                ? Optional.empty()
                : termsAction(terms.knnGraphFailed(new Terms.Request.LoadKnnGraph(load.field(), load.serial()), error));
    }

    /** Shows a page of a level's nodes. */
    public Optional<Action> knnNodesLoaded(Action.LoadKnnNodes load, GetKnnGraphNodesResult result) {
        if (terms != null) {
            terms.knnNodesLoaded(request(load), result);
        }
        return Optional.empty();
    }

    public Optional<Action> knnNodesFailed(Action.LoadKnnNodes load, String error) {
        if (terms != null) {
            terms.knnNodesFailed(request(load), error);
        }
        return Optional.empty();
    }

    /** Shows a node's neighbours. */
    public Optional<Action> knnNodeLoaded(Action.LoadKnnNode load, GetKnnGraphNodeResult result) {
        if (terms != null) {
            terms.knnNodeLoaded(request(load), result);
        }
        return Optional.empty();
    }

    public Optional<Action> knnNodeFailed(Action.LoadKnnNode load, String error) {
        if (terms != null) {
            terms.knnNodeFailed(request(load), error);
        }
        return Optional.empty();
    }

    private static Terms.Request.LoadKnnNodes request(Action.LoadKnnNodes load) {
        return new Terms.Request.LoadKnnNodes(load.field(), load.serial(), load.segment(), load.level(), load.offset());
    }

    private static Terms.Request.LoadKnnNode request(Action.LoadKnnNode load) {
        return new Terms.Request.LoadKnnNode(
                load.field(), load.serial(), load.segment(), load.ord(), load.docId(), load.walk());
    }

    public void postingsFailed(Action.LoadPostings load, String error) {
        if (terms != null) {
            terms.postingsFailed(request(load), error);
        }
    }

    private void forgetScans() {
        scan = null;
        queue.clear();
    }

    /** Whether a term scan runs or waits. */
    boolean scanning() {
        return scan != null;
    }

    /** Whether the commits screen waits for a fetch. */
    boolean loadingCommits() {
        return commits != null && commits.loading();
    }

    /** Whether the terms screen waits for a fetch. */
    boolean loadingTerms() {
        return terms != null && terms.loading();
    }

    /**
     * The jobs to poll, once they have started: the running term scan, the rebuild and the filter of
     * the documents screen, the export, the search screen's jobs and the check.
     */
    public List<Poll> polls() {
        List<Poll> polls = new ArrayList<>();
        if (scan != null && scan.jobId() != null) {
            polls.add(new Poll.Scan(scan.id(), scan.jobId()));
        }
        if (documents != null) {
            documents.reconstructJob().ifPresent(jobId -> polls.add(new Poll.Reconstruct(jobId)));
            documents.matchesJob().ifPresent(job -> polls.add(new Poll.DocumentMatches(job[0], job[1])));
            documents
                    .filterJob()
                    .ifPresent(jobId ->
                            polls.add(new Poll.Filter(documents.filterSerial().getAsLong(), jobId)));
        }
        if (export instanceof Rebuilding rebuilding && rebuilding.jobId() != null) {
            polls.add(new Poll.DocumentExport(rebuilding.id(), rebuilding.jobId()));
        }
        if (export instanceof TermsExport termsExport && termsExport.jobId() != null) {
            polls.add(new Poll.ExportTerms(termsExport.id(), termsExport.jobId()));
        }
        if (search != null) {
            search.searchJob().ifPresent(job -> polls.add(new Poll.Search(job[0], job[1])));
            search.explainJob().ifPresent(job -> polls.add(new Poll.Explain(job[0], job[1])));
            search.highlightJob().ifPresent(job -> polls.add(new Poll.Highlight(job[0], job[1])));
            search.storedScanJob()
                    .ifPresent(job -> polls.add(new Poll.StoredScan(job[0], job[1], search.storedScanFromHit())));
            search.knnRecallJob().ifPresent(job -> polls.add(new Poll.KnnRecall(job[0], job[1])));
        }
        if (check != null) {
            check.job().ifPresent(job -> polls.add(new Poll.Check(job[0], job[1], check.fromLine())));
        }
        analysis.autoStopWordsJob().ifPresent(job -> polls.add(new Poll.AutoStopWords(job[0], job[1])));
        return polls;
    }

    /** Whether the check screen runs a check. */
    boolean checking() {
        return check != null && check.running();
    }

    /** Shows the indexes and segments of the check screen. */
    public void checkTargetsLoaded(GetCheckTargetsResult result) {
        if (check != null) {
            check.targetsLoaded(result);
        }
    }

    public void checkTargetsFailed(String error) {
        if (check != null) {
            check.targetsFailed(error);
        }
    }

    /** The job of check {@code checkId} has started; the job of a check that was stopped is cancelled. */
    public Optional<Action> checkStarted(long checkId, long jobId) {
        if (check == null) {
            return Optional.of(new Action.CancelCheck(jobId));
        }
        OptionalLong cancel = check.started(checkId, jobId);
        return cancel.isPresent() ? Optional.of(new Action.CancelCheck(cancel.getAsLong())) : Optional.empty();
    }

    /** Shows the new output lines of check {@code checkId}, and its outcome once it is there. */
    public void checkProgress(long checkId, GetCheckIndexResult result) {
        if (check != null) {
            check.progress(checkId, result);
        }
    }

    public void checkFailed(long checkId, String error) {
        if (check != null) {
            check.failed(checkId, error);
        }
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
        Optional<FieldTerms> scanned = result.terms();
        if (scanned.isPresent()) {
            overview.setScan(scan.field(), Optional.of(FieldScan.done(scanned.get(), scan.topN())));
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
        if (!opened.supports(Feature.TOP_TERMS)) {
            notice = new Notice.NotSupported("Top terms", reason());
            return Optional.empty();
        }
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
            return overviewEnter();
        }
        if (key.isChar('/')) {
            Optional<Overview.FieldTerm> top = overview.selectedTopTerm();
            if (top.isPresent() && !searchesTerms()) {
                notice = new Notice.NotSupported("Search term", reason());
                return Optional.empty();
            }
            if (top.isPresent() && search != null) {
                jump(Screen.SEARCH);
                return searchAction(
                        search.termQuery(top.get().field(), top.get().term()));
            }
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

    /** Enter on the overview: the docs of the selected top term, or else a scan of the selected field. */
    private Optional<Action> overviewEnter() {
        Optional<Overview.FieldTerm> top = overview.selectedTopTerm();
        if (top.isEmpty()) {
            return overview.selectedField()
                    .filter(overview::needsScan)
                    .flatMap(field -> requestScans(List.of(field.name())));
        }
        if (!opened.supports(Feature.TERMS)) {
            notice = new Notice.NotSupported("Term's docs", reason());
            return Optional.empty();
        }
        jump(Screen.TERMS);
        return termsAction(terms.browse(top.get().field(), top.get().term()));
    }

    public Optional<Action> handleKey(KeyEvent key) {
        boolean isChar = key.code() == KeyCode.CHAR;
        if (isChar && key.codePoint() == 'c' && key.hasCtrl()) {
            return Optional.of(new Action.Quit());
        }
        if (logWindow != null) {
            return logWindowKey(key);
        }
        if (busy) {
            return Optional.empty();
        }
        notice = null;
        if (runtimeWindow != null) {
            return runtimeWindowKey(key);
        }
        if (jars != null) {
            return jarsKey(key);
        }
        if (prompt != null) {
            return promptKey(key);
        }
        if (exportPrompt != null) {
            return exportPromptKey(key);
        }
        if (screenPrompting()) {
            return screenPromptKey(key);
        }
        if (key.code() == KeyCode.ESCAPE) {
            return escapeKey();
        }
        if (key.code() == KeyCode.BACKSPACE) {
            return goBack();
        }
        if (!isChar) {
            return screenKey(key);
        }
        return charKey(key);
    }

    private Optional<Action> logWindowKey(KeyEvent key) {
        return switch (logWindow.handleKey(key)) {
            case LogWindow.Copy copy -> Optional.of(new Action.Copy(copy.text()));
            case LogWindow.Close _ -> {
                logFilter = logWindow.filter();
                logWindow = null;
                yield Optional.empty();
            }
            case LogWindow.Nothing _ -> Optional.empty();
        };
    }

    private Optional<Action> runtimeWindowKey(KeyEvent key) {
        return switch (runtimeWindow.handleKey(key)) {
            case RuntimeWindow.Install install -> Optional.of(new Action.InstallRuntimes(install));
            case RuntimeWindow.Close _ -> {
                runtimeWindow = null;
                yield Optional.empty();
            }
            case RuntimeWindow.Nothing _ -> Optional.empty();
        };
    }

    private Optional<Action> jarsKey(KeyEvent key) {
        return switch (jars.handleKey(key)) {
            case JarsPopup.Submit submit -> {
                jars.setBusy();
                yield Optional.of(
                        new Action.AddJars(new AddJarsParams(List.of(PathCompletion.expandHome(submit.path(), home)))));
            }
            case JarsPopup.Close _ -> {
                jars = null;
                yield Optional.empty();
            }
            case JarsPopup.Nothing _ -> Optional.empty();
        };
    }

    private Optional<Action> promptKey(KeyEvent key) {
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

    private Optional<Action> exportPromptKey(KeyEvent key) {
        return switch (exportPrompt.handleKey(key)) {
            case ExportPrompt.Submit submit -> startExport(submit.request());
            case ExportPrompt.Cancel _ -> {
                exportPrompt = null;
                yield Optional.empty();
            }
            case ExportPrompt.Nothing _ -> Optional.empty();
        };
    }

    /** Whether the shown screen has a prompt open, which takes every key. */
    private boolean screenPrompting() {
        return switch (screen) {
            case TERMS -> terms != null && terms.prompting();
            case DOCUMENTS -> documents != null && documents.prompting();
            case ANALYSIS -> analysis.prompting();
            case SEARCH -> search != null && search.prompting();
            default -> false;
        };
    }

    private Optional<Action> screenPromptKey(KeyEvent key) {
        return switch (screen) {
            case TERMS -> termsAction(terms.handleKey(key));
            case DOCUMENTS -> documentsAction(documents.handleKey(key));
            case ANALYSIS -> analysisAction(analysis.handleKey(key));
            case SEARCH -> searchAction(search.handleKey(key));
            default -> Optional.empty();
        };
    }

    /**
     * Esc stops the scans first, then the export, then the shown screen's jobs, and quits only when
     * there are none.
     */
    private Optional<Action> escapeKey() {
        if (scan != null) {
            return cancelScans();
        }
        if (export != null) {
            return cancelExport();
        }
        if (screen == Screen.SEARCH && searching()) {
            // A job that hasn't started yet is cancelled once it starts.
            List<Long> jobs = search.cancel();
            return jobs.isEmpty() ? Optional.empty() : Optional.of(new Action.CancelSearch(jobs));
        }
        if (screen == Screen.DOCUMENTS && documents != null && documents.rebuilding()) {
            return documents.stopRebuild().stream().boxed().findFirst().map(Action.CancelReconstruct::new);
        }
        if (screen == Screen.DOCUMENTS && documents != null && documents.filterBuilding()) {
            return documentsAction(documents.clearFilter());
        }
        if (screen == Screen.CHECK && checking()) {
            // A job that hasn't started yet is cancelled once it starts.
            return check.cancel().stream().boxed().findFirst().map(Action.CancelCheck::new);
        }
        return Optional.of(new Action.Quit());
    }

    private Optional<Action> cancelExport() {
        Long job = switch (export) {
            case Rebuilding rebuilding -> rebuilding.jobId();
            case TermsExport termsExport -> termsExport.jobId();
            default -> null;
        };
        export = null;
        return job != null ? Optional.of(new Action.CancelExport(job)) : Optional.empty();
    }

    private Optional<Action> charKey(KeyEvent key) {
        switch (key.codePoint()) {
            case 'q' -> {
                return Optional.of(new Action.Quit());
            }
            case '1' -> showIf(hasIndex() && supported(Screen.OVERVIEW), Screen.OVERVIEW);
            case '2' -> {
                return showLoading(Screen.DOCUMENTS);
            }
            case '3' -> {
                return showLoading(Screen.TERMS);
            }
            case '4' -> {
                return showLoading(Screen.COMMITS);
            }
            case '5' -> showIf(hasIndex() && supported(Screen.SEARCH), Screen.SEARCH);
            case '6' -> {
                show(Screen.ANALYSIS);
                return initialRequest();
            }
            case '7' -> {
                if (check != null && supported(Screen.CHECK)) {
                    show(Screen.CHECK);
                    return initialRequest();
                }
            }
            case 'o' -> openPrompt();
            case 'L' -> logWindow = new LogWindow(log, logFilter);
            case 'X' -> {
                jars = new JarsPopup(defaultJars, runtimeFolders, home);
                return Optional.of(new Action.LoadJars());
            }
            case 'D' -> {
                // Only on the start screen: the analysis tab has a D of its own.
                return opened == null && screen != Screen.ANALYSIS ? openRuntimeWindow() : screenKey(key);
            }
            case 'T' -> {
                // Only on the start screen: the documents tab has a T of its own.
                if (opened != null || screen == Screen.ANALYSIS) {
                    return screenKey(key);
                }
                Theme theme = themes.next(Palette.theme());
                Palette.use(theme);
                themeFile.save(theme);
                notice = new Notice.ThemeChanged(theme.id());
            }
            case 'c' -> {
                if (hasIndex()) {
                    busy = true;
                    return Optional.of(new Action.Close());
                }
            }
            case 'R' -> {
                return reopenKey();
            }
            default -> {
                return screenKey(key);
            }
        }
        return Optional.empty();
    }

    /** Shows {@code shown}, if the index has it, with what it loads first. */
    private Optional<Action> showLoading(Screen shown) {
        if (hasIndex() && supported(shown)) {
            show(shown);
            return initialRequest();
        }
        return Optional.empty();
    }

    /**
     * Starts from the path that failed to open, then from the open index, so a sibling is a Ctrl+W
     * away.
     */
    private void openPrompt() {
        String path = "";
        if (failedPath != null) {
            path = failedPath;
        } else if (opened != null) {
            path = opened.path();
        }
        failedPath = null;
        prompt = pathPrompt(path);
    }

    private Optional<Action> reopenKey() {
        if (hasIndex() && !opened.supports(Feature.REOPEN)) {
            notice = new Notice.NotSupported("Reopen", reason());
        } else if (overview != null) {
            notice = new Notice.Reopening();
            busy = true;
            return Optional.of(new Action.Reopen());
        }
        return Optional.empty();
    }

    /** What the shown screen does with a key that isn't one of the app's. */
    private Optional<Action> screenKey(KeyEvent key) {
        if (screen == Screen.OVERVIEW && overview != null) {
            return overviewKey(key);
        }
        if (screen == Screen.COMMITS && commits != null) {
            return commitsAction(commits.handleKey(key));
        }
        if (screen == Screen.TERMS && terms != null) {
            return termsAction(terms.handleKey(key));
        }
        if (screen == Screen.DOCUMENTS && documents != null) {
            return documentsAction(documents.handleKey(key));
        }
        if (screen == Screen.ANALYSIS) {
            return analysisAction(analysis.handleKey(key));
        }
        if (screen == Screen.SEARCH && search != null) {
            return searchAction(search.handleKey(key));
        }
        if (screen == Screen.CHECK && check != null) {
            return checkAction(check.handleKey(key));
        }
        return Optional.empty();
    }

    /**
     * Whether the open index can show {@code screen}, by its features (ADR 0044): an index opened
     * without a reader, or one that an old Lucene reads, lacks some. The overview is always there;
     * without a reader, it shows how the index was opened.
     */
    private boolean supports(Screen screen) {
        if (opened == null) {
            return true;
        }
        return switch (screen) {
            case OVERVIEW -> true;
            case DOCUMENTS -> opened.supports(Feature.DOCUMENTS);
            case TERMS -> opened.supports(Feature.TERMS);
            case COMMITS -> opened.supports(Feature.COMMITS);
            case SEARCH -> opened.supports(Feature.CLASSIC_SEARCH) || opened.supports(Feature.TERM_SEARCH);
            case ANALYSIS -> true;
            case CHECK -> opened.supports(Feature.CHECK_INDEX);
        };
    }

    /** Whether the open index can search a term (ADR 0058). */
    private boolean searchesTerms() {
        return opened.supports(Feature.TERM_SEARCH);
    }

    /**
     * Whether {@code luceneRuntime}, such as {@code 10.5.2}, is the Lucene that reads the newest
     * indexes, of {@link #NEWEST_LUCENE_MAJOR}; an empty version, as from {@link
     * #opened(GetIndexStatsResult, List)}, stands for it too. Then the tab line says nothing about it;
     * an older one it names, since it explains what the index lacks.
     */
    private static boolean readByTheNewestLucene(String luceneRuntime) {
        return luceneRuntime.isEmpty() || luceneRuntime.startsWith(NEWEST_LUCENE_MAJOR + ".");
    }

    /**
     * Why the open index lacks a feature, for {@link Notice.NotSupported}: {@code needs a reader}, or
     * who wrote it, such as {@code not for Lucene 2.x}.
     */
    private String reason() {
        if (opened.noReader()) {
            return "needs a reader";
        }
        return "not for " + oldLucene();
    }

    /** The old Lucene of the open index, such as {@code Lucene 2.x}, or {@code this index}. */
    private String oldLucene() {
        return opened.oldLucene().map(old -> old.substring(0, old.indexOf(','))).orElse("this index");
    }

    /** Why the open index can't find auto stop words; empty if it can (ADR 0039). */
    private Optional<String> autoStopWordsReason() {
        return opened.supports(Feature.AUTO_STOP_WORDS) ? Optional.empty() : Optional.of(reason());
    }

    /** The tab line says that the index lacks {@code what} until the next key; nothing goes to the backend. */
    private Optional<Action> notSupported(String what) {
        notice = new Notice.NotSupported(what, reason());
        return Optional.empty();
    }

    /** As {@link #supports(Screen)}; if not, the tab line says why until the next key. */
    private boolean supported(Screen screen) {
        if (supports(screen)) {
            return true;
        }
        notice = new Notice.NotSupported(screen.name, reason());
        return false;
    }

    private void showIf(boolean available, Screen screen) {
        if (available) {
            show(screen);
        }
    }

    /** Shows {@code to} as a number key does: Backspace retraces no jump from before it (ADR 0052). */
    private void show(Screen to) {
        leaving(to);
        back.clear();
        screen = to;
    }

    /** Ends what the shown screen notes until it is left, as it is left for {@code to}. */
    private void leaving(Screen to) {
        if (screen == Screen.TERMS && to != Screen.TERMS && terms != null) {
            terms.leave();
        }
    }

    /**
     * Shows {@code to} as a jump from the shown screen, which Backspace goes back to (ADR 0052). The
     * oldest jump is forgotten beyond {@link #BACK_DEPTH}.
     */
    private void jump(Screen to) {
        if (to != screen) {
            pushBack();
        }
        leaving(to);
        screen = to;
    }

    /** Keeps where the shown screen is, for Backspace to go back to. */
    private void pushBack() {
        boolean leavesDocuments = screen == Screen.DOCUMENTS && documents != null;
        boolean leavesTerms = screen == Screen.TERMS
                || leavesDocuments
                        && documents
                                .walk()
                                .filter(Documents.Walk.TERM_DOCS::equals)
                                .isPresent();
        // A search hit's [/] step the search screen, as a term's document's step the terms screen.
        boolean leavesSearch = screen == Screen.SEARCH
                || leavesDocuments
                        && documents
                                .walk()
                                .filter(Documents.Walk.SEARCH_HITS::equals)
                                .isPresent();
        back.push(new Back(
                screen,
                leavesTerms && terms != null ? Optional.of(terms.mark()) : Optional.empty(),
                leavesDocuments ? Optional.of(documents.mark()) : Optional.empty(),
                leavesSearch && search != null ? Optional.of(search.mark()) : Optional.empty(),
                screen == Screen.COMMITS && commits != null ? Optional.of(commits.mark()) : Optional.empty()));
        if (back.size() > BACK_DEPTH) {
            back.removeLast();
        }
    }

    /**
     * Backspace: the screen that the last jump came from, as it was left (ADR 0053), skipping those
     * the open index can't show anymore (ADR 0052). Returns what it has to fetch first.
     */
    private Optional<Action> goBack() {
        while (!back.isEmpty()) {
            Back to = back.pop();
            if (canShow(to.screen())) {
                return restore(to);
            }
        }
        return Optional.empty();
    }

    /** Shows the screen of {@code to} again as it was, with what it fetches. */
    private Optional<Action> restore(Back to) {
        if (terms != null) {
            to.terms().ifPresent(terms::restore);
        }
        if (commits != null) {
            to.commits().ifPresent(commits::restore);
        }
        Optional<Search.Request.Cancel> searchCancel =
                search != null ? to.search().flatMap(search::restore) : Optional.empty();
        Optional<Action> document = documents != null
                ? to.documents().map(documents::restore).flatMap(this::documentsAction)
                : Optional.empty();
        leaving(to.screen());
        screen = to.screen();
        if (searchCancel.isPresent()) {
            // The newer search's jobs are cancelled with the fetch, if the document needs one.
            if (document.orElse(null) instanceof Action.LoadDocument load) {
                List<Long> cancel = new ArrayList<>(load.cancel());
                cancel.addAll(searchCancel.get().jobs());
                return Optional.of(
                        new Action.LoadDocument(load.docId(), List.copyOf(cancel), load.locate(), load.matches()));
            }
            if (document.isEmpty()) {
                return searchAction(searchCancel.map(Search.Request.class::cast));
            }
        }
        return document.isPresent() ? document : initialRequest();
    }

    /** Whether {@code screen} is there to go back to: the open index supports it, and it was made. */
    private boolean canShow(Screen screen) {
        boolean made = switch (screen) {
            case OVERVIEW -> overview != null;
            case DOCUMENTS -> documents != null;
            case TERMS -> terms != null;
            case COMMITS -> commits != null;
            case SEARCH -> search != null;
            case ANALYSIS -> true;
            case CHECK -> check != null;
        };
        return made && supports(screen);
    }

    public void render(Frame frame) {
        Rect area = frame.area();
        // A theme with a background of its own paints it first (ADR 0095).
        frame.buffer().setStyle(area, Palette.current().base());
        // The analysis screen needs no index, so its tab shows without one.
        boolean tabsShown = hasIndex() || screen == Screen.ANALYSIS;
        List<Line> footer = notice != null && notice.inFooter()
                ? List.of(Line.from(Span.raw(" ")).append(notice.line()))
                : KeyLegend.lines(footerKeys(), area.width());
        // A legend that wraps takes a third of the screen at most, so the screen keeps the rest.
        int footerCap = Math.max(area.height() / 3, 1);
        int footerHeight = Math.min(footer.size(), footerCap);
        List<Rect> rows = Layout.vertical()
                .constraints(Constraint.length(tabsShown ? 1 : 0), Constraint.fill(), Constraint.length(footerHeight))
                .split(area);
        Rect body = rows.get(1);
        renderScreen(frame, body);
        if (tabsShown) {
            renderTabs(frame, rows.get(0));
        }
        frame.renderWidget(Paragraph.from(Text.from(footer)), rows.get(2));
        if (exportPrompt != null) {
            exportPrompt.render(frame, body);
        }
        if (prompt != null) {
            prompt.render(frame, body);
        }
        if (jars != null) {
            jars.render(frame, body);
        }
        if (runtimeWindow != null) {
            runtimeWindow.render(frame, body);
        }
        if (logWindow != null) {
            logWindow.render(frame, body);
        }
    }

    /** Draws the shown screen into {@code body}. */
    private void renderScreen(Frame frame, Rect body) {
        if (screen == Screen.ANALYSIS) {
            analysis.render(frame, body);
        } else if (opened == null) {
            renderAbout(frame, body);
        } else if (screen == Screen.COMMITS) {
            commits.render(frame, body);
        } else if (screen == Screen.CHECK && check != null) {
            check.render(frame, body);
        } else if (overview == null) {
            renderWithoutReader(frame, body);
        } else if (screen == Screen.SEARCH) {
            search.render(frame, body);
        } else if (screen == Screen.TERMS) {
            terms.render(frame, body);
        } else if (screen == Screen.DOCUMENTS) {
            documents.render(frame, body);
        } else {
            overview.render(frame, body);
        }
    }

    /** The footer's keys for the shown screen; they follow the branches of {@link #renderScreen}. */
    private String footerKeys() {
        String reopen = overview != null && opened.supports(Feature.REOPEN) ? "R reopen   " : "";
        String cancel = cancelKeys();
        String screens = "1-7 screen   " + (back.isEmpty() ? "" : "⌫ back   ");
        if (screen == Screen.ANALYSIS) {
            return analysisFooter(cancel, screens);
        }
        if (opened == null) {
            // Fits 80 columns, on one line.
            return " q quit   o open   6 analysis   X jars   D download runtimes   L log   T theme";
        }
        if (screen == Screen.COMMITS) {
            return " " + cancel + APP_KEYS + reopen + screens + commitsKeys();
        }
        if (screen == Screen.CHECK && check != null) {
            return " " + cancel + APP_KEYS + reopen + screens + check.keys();
        }
        if (overview == null) {
            return " q quit   o open   c close   1-7 screen";
        }
        if (screen == Screen.SEARCH) {
            return searchFooter(screens);
        }
        if (screen == Screen.TERMS) {
            return terms.prompting() ? " type to seek   ⏎/Esc done" : " " + cancel + APP_KEYS + screens + terms.keys();
        }
        if (screen == Screen.DOCUMENTS) {
            return documentsFooter(cancel, screens);
        }
        return overviewFooter(reopen, screens);
    }

    /** What Esc stops: the scans first, then the export. */
    private String cancelKeys() {
        if (scan != null) {
            return "Esc cancel scan   ";
        }
        return export != null ? "Esc cancel export   " : "";
    }

    private String commitsKeys() {
        return commits != null ? commits.keys() : "r read again   ⏎ segments   Tab panel   ↑↓";
    }

    private String analysisFooter(String cancel, String screens) {
        if (analysis.prompting()) {
            return analysis.keys();
        }
        return hasIndex()
                ? " " + cancel + APP_KEYS + screens + analysis.keys()
                : " q quit   o open   " + analysis.keys();
    }

    private String searchFooter(String screens) {
        if (search.prompting()) {
            return search.keys();
        }
        String stop = scan != null || search.running() ? "Esc cancel   " : "";
        return " " + stop + APP_KEYS + screens + search.keys();
    }

    private String documentsFooter(String cancel, String screens) {
        Optional<Viewer> viewer = documents.viewer();
        if (viewer.isPresent()) {
            return viewer.get().keys();
        }
        if (documents.seeking()) {
            return " type to seek a field   ⏎/Esc done";
        }
        if (documents.goingTo()) {
            return " type a document id   ⏎ go   Esc cancel";
        }
        // Esc stops the scan first.
        String stop = cancel;
        if (stop.isEmpty() && documents.rebuilding()) {
            stop = "Esc stop rebuild   ";
        } else if (stop.isEmpty() && documents.filterBuilding()) {
            stop = "Esc stop filter   ";
        }
        return " " + stop + APP_KEYS + screens + documents.keys();
    }

    private String overviewFooter(String reopen, String screens) {
        if (scan != null) {
            return " Esc cancel scan   q quit   o open   c close   " + reopen + screens + "s/S sort   Tab panel   ↑↓";
        }
        if (overview.selectedTopTerm().isPresent()) {
            String docs = opened.supports(Feature.TERMS) ? "⏎ term's docs   " : "";
            String searchTerm = searchesTerms() ? "/ search term   " : "";
            return " q quit   o open   c close   " + reopen + screens + docs + searchTerm + "+/- top N   "
                    + "s/S sort   Tab panel   ↑↓";
        }
        String topTerms = opened.supports(Feature.TOP_TERMS) ? "⏎ terms   a all terms   +/- top N   " : "";
        return " q quit   o open   c close   " + reopen + screens + topTerms + "s/S sort   Tab panel   ↑↓";
    }

    private void renderAbout(Frame frame, Rect body) {
        List<Line> lines = new ArrayList<>(Logo.aboutLines(version));
        lines.add(Line.empty());
        lines.add(Line.from("Java " + version.java().version() + " ("
                + version.java().vendor() + ") in " + version.java().home()));
        lines.add(Line.empty());
        lines.addAll(runtimesLines());
        lines.add(Line.from(
                "No index open. Press o to open one, 6 to try an analyzer, X to add jars, or D to download runtimes."));
        lines.add(Line.from("L shows the log"
                + logFile.map(file -> ", which is written to " + file + " too").orElse("") + "."));
        Block block = bordered(" leia ");
        Rect inner = block.inner(body);
        frame.renderWidget(block, body);
        // The logo, a row below the border and two columns right of it, which look about the same,
        // only where it leaves room for the text; its top is cut off where it is too tall.
        Rect padded = new Rect(
                inner.x() + ABOUT_PADDING_LEFT,
                inner.y() + ABOUT_PADDING_TOP,
                Math.max(inner.width() - ABOUT_PADDING_LEFT, 0),
                Math.max(inner.height() - ABOUT_PADDING_TOP, 0));
        if (Logo.fitsBeside(padded, lines)) {
            Logo.renderBeside(frame, padded, lines);
        } else {
            frame.renderWidget(wrapped(lines, null, inner), inner);
        }
    }

    /**
     * The loaded runtimes, the missing ones with where to put them, the incomplete and refused ones
     * with why (ADR 0072, ADR 0073), and an empty line after them; none until they are known.
     */
    private List<Line> runtimesLines() {
        if (runtimes == null) {
            return List.of();
        }
        List<RuntimeFolder> loaded = runtimes.runtimes().stream()
                .filter(runtime -> runtime.state() == RuntimeState.LOADED)
                .toList();
        List<RuntimeFolder> missing = runtimes.runtimes().stream()
                .filter(runtime -> runtime.state() == RuntimeState.MISSING)
                .toList();
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from("Runtimes: "
                + (loaded.isEmpty()
                        ? "none"
                        : "Lucene "
                                + String.join(
                                        ", ",
                                        loaded.stream()
                                                .map(runtime -> runtime.luceneVersion()
                                                                .orElseThrow() + " (" + majors(runtime) + ")")
                                                .toList()))));
        for (ServerRuntimeInfo server : runtimes.servers()) {
            lines.add(Line.from(
                    "Server: " + server.label().map(label -> label + ", ").orElse("") + "Lucene "
                            + server.luceneVersion() + " (" + majors(server.indexMajors()) + "), in " + server.home()));
        }
        Style warning = Palette.current().warning();
        if (!missing.isEmpty()) {
            String where = runtimeFolders
                    .dir()
                    .map(dir -> "press D to download them into " + dir)
                    .orElse("(not run from leia.jar)");
            lines.add(Line.styled(
                    "Missing "
                            + String.join(
                                    ", ",
                                    missing.stream()
                                            .map(runtime -> runtime.folder() + " (Lucene " + majors(runtime) + ")")
                                            .toList())
                            + ": " + where,
                    warning));
        }
        for (RuntimeFolder runtime : runtimes.runtimes()) {
            String problem = runtime.problem().orElse("");
            switch (runtime.state()) {
                case INCOMPLETE ->
                    lines.add(Line.styled(
                            "Incomplete runtimes/" + runtime.folder() + ": " + problem
                                    + " is missing; press D to download it",
                            warning));
                case REFUSED ->
                    lines.add(Line.styled("Refused runtimes/" + runtime.folder() + ": " + problem, warning));
                case RESTART ->
                    lines.add(Line.styled(
                            "runtimes/" + runtime.folder() + " is installed; restart leia to load it", warning));
                case LOADED, MISSING -> {}
            }
        }
        if (loaded.isEmpty()) {
            lines.add(Line.styled(
                    "Without runtimes, indexes open only without a reader, and the analysis tab is off.", warning));
        }
        lines.add(Line.empty());
        return lines;
    }

    /** The majors of the indexes a runtime reads, such as {@code 9–10} or {@code 8}. */
    private static String majors(RuntimeFolder runtime) {
        return majors(runtime.indexMajors());
    }

    /** {@code indexMajors}, oldest first, such as {@code 9–10} or {@code 8}. */
    private static String majors(List<Integer> indexMajors) {
        int first = indexMajors.getFirst();
        int last = indexMajors.getLast();
        return first == last ? Integer.toString(first) : first + "–" + last;
    }

    /**
     * The overview of an index opened without a reader: how it was opened. The screens that need a
     * reader can't be shown (ADR 0044).
     */
    private void renderWithoutReader(Frame frame, Rect body) {
        List<Line> lines = new ArrayList<>();
        lines.add(labelLine("Path", opened.path()));
        lines.add(labelLine("Directory", directoryName(opened.directory())));
        lines.add(labelLine("Reader", "none: opened without a reader, so only the commits (4) and check (7) work"));
        if (opened.indexes().size() > 1) {
            lines.add(labelLine("Indexes", Integer.toString(opened.indexes().size())));
            for (String index : opened.indexes()) {
                lines.add(Line.from("  " + Overview.relative(opened.path(), index)));
            }
        }
        frame.renderWidget(wrapped(lines, " Index ", body), body);
    }

    /** What the tab line says about the running export, once its prompt is closed. */
    private Optional<String> exportLine() {
        if (exportPrompt != null) {
            return Optional.empty();
        }
        return switch (export) {
            case null -> Optional.empty();
            case Loading _ -> Optional.empty();
            case TermsExport termsExport ->
                Optional.of("Exporting " + termsExport.field() + ": "
                        + TextFormat.count(termsExport.termsWritten(), "term"));
            case Rebuilding rebuilding ->
                Optional.of("Exporting doc " + rebuilding.docId() + ": " + rebuilding.scanned()
                        + (rebuilding.total().isPresent()
                                ? "/" + rebuilding.total().getAsLong()
                                : "") + " terms and points");
            case Writing writing -> Optional.of("Exporting doc " + writing.docId() + ": writing…");
        };
    }

    private Span tabSpan(Screen tab) {
        if (tab == screen) {
            return Span.styled(tab.title(), Style.EMPTY.reversed());
        }
        return supports(tab)
                ? Span.raw(tab.title())
                : Span.styled(tab.title(), Palette.current().muted());
    }

    /** What the tabs show after them, if anything: the notice, the running export or the index. */
    private Line tabsLine() {
        Optional<String> exporting = exportLine();
        if (notice != null && !notice.inFooter()) {
            return notice.line();
        }
        if (exporting.isPresent()) {
            return Line.from(exporting.get());
        }
        return opened != null ? marker(opened) : null;
    }

    /** The tabs, and the notice, the running export or how the index was opened, right-aligned after them. */
    private void renderTabs(Frame frame, Rect area) {
        Line line = tabsLine();
        // Without padding, the tabs leave the line more room when it needs it.
        boolean compact = line != null && Math.max(area.width() - TABS_WIDTH - 1, 0) < line.width();
        int tabsWidth = compact ? COMPACT_TABS_WIDTH : TABS_WIDTH;
        Rect tabs = area;
        if (line != null) {
            List<Rect> parts = Layout.horizontal()
                    .constraints(Constraint.length(tabsWidth), Constraint.length(1), Constraint.fill())
                    .split(area);
            tabs = parts.get(0);
            Rect room = parts.get(2);
            // TamboUI's paragraph ignores the alignment of a line, only its own counts.
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(cut(line, room.width())))
                            .right()
                            .build(),
                    room);
        }
        String padding = compact ? "" : " ";
        List<Span> spans = new ArrayList<>();
        for (Screen tab : Screen.values()) {
            if (tab.ordinal() > 0) {
                spans.add(Span.raw("│"));
            }
            spans.add(Span.raw(padding));
            spans.add(tabSpan(tab));
            spans.add(Span.raw(padding));
        }
        frame.renderWidget(Paragraph.from(Line.from(spans)), tabs);
    }

    /** {@code line} cut to {@code width} columns, ending in {@code …} if it was cut, with its styles. */
    private static Line cut(Line line, int width) {
        if (line.width() <= width) {
            return line;
        }
        List<Span> spans = new ArrayList<>();
        int left = Math.max(width - 1, 0);
        Style last = Style.EMPTY;
        for (Span span : line.spans()) {
            String text = CharWidth.substringByWidth(span.content(), left);
            spans.add(Span.styled(text, span.style()));
            last = span.style();
            left -= CharWidth.of(text);
            if (text.length() < span.content().length()) {
                break;
            }
        }
        spans.add(Span.styled("…", last));
        return Line.from(spans);
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
        Optional<String> oldLucene = opened.oldLucene();
        String text;
        if (opened.noReader()) {
            text = count == 1 ? "no reader" : "no reader, " + count + INDEXES;
        } else if (oldLucene.isPresent()) {
            text = oldLucene.get();
        } else if (count > 1) {
            text = count + INDEXES;
        } else {
            return null;
        }
        return Line.styled(text, Palette.current().warning());
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
        return Panels.box(title);
    }

    /** {@code lines} wrapped at word boundaries to fit {@code area}, in a bordered block if {@code title} isn't null. */
    private static Paragraph wrapped(List<Line> lines, String title, Rect area) {
        return title != null
                ? Panels.wrapped(lines, bordered(title), area)
                : Paragraph.from(Text.from(Panels.wrap(lines, area.width())));
    }
}
