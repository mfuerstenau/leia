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
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
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
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import io.github.mfuerstenau.leia.tui.commits.Commits;
import io.github.mfuerstenau.leia.tui.documents.DocFilter.FilterStep;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport.DocumentData;
import io.github.mfuerstenau.leia.tui.documents.Documents;
import io.github.mfuerstenau.leia.tui.overview.FieldScan;
import io.github.mfuerstenau.leia.tui.overview.Overview;
import io.github.mfuerstenau.leia.tui.prompt.ExportPrompt;
import io.github.mfuerstenau.leia.tui.prompt.ExportPrompt.ExportTarget;
import io.github.mfuerstenau.leia.tui.prompt.PathCompletion;
import io.github.mfuerstenau.leia.tui.prompt.PathPrompt;
import io.github.mfuerstenau.leia.tui.terms.Terms;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Logo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

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

    /** The directory that a relative export path is resolved against. */
    private Optional<Path> cwd = Optional.empty();

    /** Where to export a document to, while it is asked (ADR 0030); or null. */
    private ExportPrompt exportPrompt;

    /**
     * The export that runs, or null. There is at most one. Each has an id, so that a reply for an
     * export that was stopped or forgotten is dropped, and a job that starts for one is cancelled.
     */
    private sealed interface RunningExport {
        long id();

        int docId();
    }

    /** The document is being fetched; the prompt says so. */
    private record Loading(long id, int docId, boolean indexedTerms, ExportTarget target) implements RunningExport {}

    /**
     * The indexed terms of the document are being rebuilt.
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
        commits = new Commits(indexNames(opened));
        terms = new Terms(stats.fields());
        documents = newDocuments(stats, opened);
        prompt = null;
        forgetExport();
        busy = false;
        return initialRequest();
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
        forgetScans();
        opened = new Opened(
                result.path(),
                true,
                directory,
                result.indexes().stream().map(IndexEntry::path).toList(),
                result.skipped().stream().map(SkippedIndex::path).toList());
        commits = new Commits(indexNames(opened));
        if (screen != Screen.ANALYSIS && screen != Screen.CHECK) {
            screen = Screen.COMMITS;
        }
        prompt = null;
        forgetExport();
        busy = false;
        return initialRequest();
    }

    /**
     * Shows the reopened index (ADR 0019) where the user was. The backend stopped the jobs of the old
     * index, and forgot the filter, which {@link #rebuildFilter} builds again. Returns what the shown
     * screen has to fetch first.
     */
    public Optional<Action> reopened(GetIndexStatsResult stats) {
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
            reopened = changed + " of " + indexes.size() + " indexes";
        }
        overview = overview.reopened(stats, topN);
        terms = terms != null ? terms.reopened(stats.fields()) : new Terms(stats.fields());
        forgetScans();
        opened = new Opened(
                opened.path(),
                false,
                opened.directory(),
                indexes.stream().map(IndexCommitStats::path).toList(),
                opened.skipped());
        commits = new Commits(indexNames(opened));
        documents =
                documents != null ? documents.reopened(stats.fields(), stats.maxDoc()) : newDocuments(stats, opened);
        syncTermsFilter();
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
    private static Documents newDocuments(GetIndexStatsResult stats, Opened opened) {
        List<String> names = indexNames(opened);
        List<Documents.IndexStart> starts = new ArrayList<>();
        for (int i = 0; i < Math.min(names.size(), stats.indexes().size()); i++) {
            starts.add(new Documents.IndexStart(stats.indexes().get(i).docBase(), names.get(i)));
        }
        return new Documents(stats.fields(), stats.maxDoc()).withIndexes(starts);
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
        commits = null;
        terms = null;
        documents = null;
        forgetScans();
        forgetExport();
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
        return Optional.empty();
    }

    private static Optional<Action> commitsAction(Optional<Commits.Request> request) {
        return request.map(next -> switch (next) {
            case Commits.Request.LoadList list -> new Action.LoadCommits(list.index());
            case Commits.Request.LoadCommit commit -> new Action.LoadCommit(commit.index(), commit.generation());
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
     * screen. An export comes back with the tools screen.
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
                screen = Screen.DOCUMENTS;
                yield loadDocument(document.hit().docId());
            }
            case Terms.Request.Export _ -> Optional.<Action>empty();
        });
    }

    /**
     * Fetches a document for the documents screen, which stops rebuilding a field of the shown one,
     * and finds out whether it matches the filter if that isn't known yet.
     */
    private Optional<Action> loadDocument(int docId) {
        List<Long> cancel = new ArrayList<>();
        documents.stopRebuild().ifPresent(cancel::add);
        Optional<Documents.Locate> locate = documents.filterToLocate(docId);
        documents.want(docId);
        documentFetches++;
        return Optional.of(new Action.LoadDocument(docId, List.copyOf(cancel), locate));
    }

    /**
     * Starts a filter on the documents screen (ADR 0031) on {@code label}, built from {@code params},
     * which starts on {@code startDoc} once it is built, and shows that screen. The search screen
     * calls it, once it is ported.
     */
    public Optional<Action> startFilter(String label, StartFilterParams params, int startDoc) {
        if (documents == null) {
            return Optional.empty();
        }
        screen = Screen.DOCUMENTS;
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

    /**
     * Turns what the documents screen needs into an action. What leads to the search and analysis
     * screens is dropped until they are ported (ADR 0034 §10).
     */
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
            case Documents.Request.Export asked -> {
                if (export != null) {
                    notice = new Notice.ExportRunning();
                } else {
                    exportPrompt = ExportPrompt.document(asked.docId(), indexDirs(), home, cwd);
                }
                yield Optional.empty();
            }
            case Documents.Request.Step step
            when step.walk() == Documents.Walk.TERM_DOCS ->
                terms == null ? Optional.empty() : termsAction(terms.step(step.forward()));
            case Documents.Request.Step _,
                    Documents.Request.MoreLikeThis _,
                    Documents.Request.Analyze _,
                    Documents.Request.SearchDocValues _ -> Optional.empty();
        };
    }

    /** Shows the document, if it is the one asked for. Returns where the term occurs in it, if the terms screen opened it. */
    public Optional<Action> documentLoaded(GetDocumentResult document) {
        documentFetches--;
        if (documents == null || !documents.loaded(document)) {
            return Optional.empty();
        }
        Optional<Documents.Request> next = documents.positionsRequest();
        return documentsAction(next.isPresent() ? next : documents.openPendingView());
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

    /** Whether a document export runs, from its fetch to its write. */
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

    /** Starts the export the prompt asks for: the prompt says "Exporting…" until it is fetched. */
    private Optional<Action> startExport(ExportPrompt.ExportRequest request) {
        return switch (request) {
            case ExportPrompt.ExportRequest.Document document -> {
                exportPrompt.setBusy();
                long id = nextExportId++;
                export = new Loading(id, document.docId(), document.indexedTerms(), document.target());
                yield Optional.of(new Action.LoadDocumentExport(id, document.docId()));
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
     * The rebuild of export {@code exportId} runs: the prompt closes, and the tab line shows the
     * progress. If that export was stopped or forgotten meanwhile, returns the action that cancels
     * the job.
     */
    public Optional<Action> exportStarted(long exportId, long jobId) {
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

    /** Shows the progress of the rebuild. Returns where the export goes once it is done. */
    public Optional<Action> documentExportProgress(long exportId, GetReconstructResult result) {
        if (!(export instanceof Rebuilding rebuilding) || rebuilding.id() != exportId) {
            return Optional.empty();
        }
        if (result.result().isPresent()) {
            return finishDocumentExport(
                    exportId,
                    rebuilding.data(),
                    Optional.of(result.result().get().fields()),
                    rebuilding.target());
        }
        export = new Rebuilding(
                exportId,
                rebuilding.docId(),
                rebuilding.data(),
                rebuilding.target(),
                rebuilding.jobId(),
                result.scannedTerms(),
                result.totalTerms());
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
     * the documents screen, and the export.
     */
    public List<Poll> polls() {
        List<Poll> polls = new ArrayList<>();
        if (scan != null && scan.jobId() != null) {
            polls.add(new Poll.Scan(scan.id(), scan.jobId()));
        }
        if (documents != null) {
            documents.reconstructJob().ifPresent(jobId -> polls.add(new Poll.Reconstruct(jobId)));
            documents
                    .filterJob()
                    .ifPresent(jobId ->
                            polls.add(new Poll.Filter(documents.filterSerial().getAsLong(), jobId)));
        }
        if (export instanceof Rebuilding rebuilding && rebuilding.jobId() != null) {
            polls.add(new Poll.DocumentExport(rebuilding.id(), rebuilding.jobId()));
        }
        return polls;
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
            Optional<Overview.FieldTerm> top = overview.selectedTopTerm();
            if (top.isPresent()) {
                screen = Screen.TERMS;
                return termsAction(terms.browse(top.get().field(), top.get().term()));
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
        if (exportPrompt != null) {
            return switch (exportPrompt.handleKey(key)) {
                case ExportPrompt.Submit submit -> startExport(submit.request());
                case ExportPrompt.Cancel _ -> {
                    exportPrompt = null;
                    yield Optional.empty();
                }
                case ExportPrompt.Nothing _ -> Optional.empty();
            };
        }
        if (screen == Screen.TERMS && terms != null && terms.prompting()) {
            return termsAction(terms.handleKey(key));
        }
        if (screen == Screen.DOCUMENTS && documents != null && documents.prompting()) {
            return documentsAction(documents.handleKey(key));
        }
        if (key.code() == KeyCode.ESCAPE) {
            // Esc stops the scans first, then the export, then the shown screen's jobs, and quits only
            // when there are none.
            if (scan != null) {
                return cancelScans();
            }
            if (export != null) {
                Long job = export instanceof Rebuilding rebuilding ? rebuilding.jobId() : null;
                export = null;
                return job != null ? Optional.of(new Action.CancelExport(job)) : Optional.empty();
            }
            if (screen == Screen.DOCUMENTS && documents != null && documents.rebuilding()) {
                OptionalLong job = documents.stopRebuild();
                return job.isPresent() ? Optional.of(new Action.CancelReconstruct(job.getAsLong())) : Optional.empty();
            }
            if (screen == Screen.DOCUMENTS && documents != null && documents.filterBuilding()) {
                return documentsAction(documents.clearFilter());
            }
            return Optional.of(new Action.Quit());
        }
        if (!isChar) {
            return screenKey(key);
        }
        switch (key.codePoint()) {
            case 'q' -> {
                return Optional.of(new Action.Quit());
            }
            case '1' -> showIf(hasIndex(), Screen.OVERVIEW);
            case '2' -> {
                if (hasIndex()) {
                    screen = Screen.DOCUMENTS;
                    return initialRequest();
                }
            }
            case '3' -> {
                if (hasIndex()) {
                    screen = Screen.TERMS;
                    return initialRequest();
                }
            }
            case '4' -> {
                if (hasIndex()) {
                    screen = Screen.COMMITS;
                    return initialRequest();
                }
            }
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
                return screenKey(key);
            }
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
        Line footer =
                notice != null && notice.inFooter() ? Line.from(Span.raw(" ")).append(notice.line()) : Line.from(keys);
        frame.renderWidget(Paragraph.from(footer), rows.get(2));
        if (exportPrompt != null) {
            exportPrompt.render(frame, body);
        }
        if (prompt != null) {
            prompt.render(frame, body);
        }
    }

    /** Draws the shown screen into {@code body}, and returns the footer's keys for it. */
    private String renderBody(Frame frame, Rect body) {
        String reopen = overview != null ? "R reopen   " : "";
        String cancel = scan != null ? "Esc cancel scan   " : export != null ? "Esc cancel export   " : "";
        if (screen == Screen.ANALYSIS) {
            renderNotPorted(frame, body, screen);
            return hasIndex() ? " " + cancel + "q quit   o open   c close   1-7 screen" : " q quit   o open";
        }
        if (opened == null) {
            renderAbout(frame, body);
            return " q quit   o open   6 analysis";
        }
        if (screen == Screen.COMMITS) {
            commits.render(frame, body);
            return " " + cancel + "q quit   o open   c close   " + reopen
                    + "1-7 screen   r read again   ⏎ segments   Tab panel   ↑↓";
        }
        if (screen == Screen.CHECK) {
            renderNotPorted(frame, body, screen);
            return " " + cancel + "q quit   o open   c close   " + reopen + "1-7 screen";
        }
        if (overview == null) {
            renderWithoutReader(frame, body);
            return " q quit   o open   c close   1-7 screen";
        }
        if (screen == Screen.TERMS) {
            terms.render(frame, body);
            if (terms.prompting()) {
                return " type to seek   ⏎/Esc done";
            }
            String filter = !terms.filtered() ? "" : terms.onlyFiltered() ? "b all postings   " : "b filter only   ";
            // e export comes back with the tools screen.
            return " " + cancel + "q quit   o open   c close   1-7 screen   / seek   ⏎ open doc   " + filter
                    + "←/→ column   ↑↓";
        }
        if (screen == Screen.DOCUMENTS) {
            documents.render(frame, body);
            if (documents.viewer().isPresent()) {
                return documents.viewer().get().keys();
            }
            if (documents.seeking()) {
                return " type to seek a field   ⏎/Esc done";
            }
            String steps = documents
                    .walk()
                    .map(walk -> walk == Documents.Walk.TERM_DOCS ? "[ ] term docs   " : "[ ] hits   ")
                    .orElse("");
            // Esc stops the scan first.
            if (cancel.isEmpty() && documents.rebuilding()) {
                cancel = "Esc stop rebuild   ";
            } else if (cancel.isEmpty() && documents.filterBuilding()) {
                cancel = "Esc stop filter   ";
            }
            String step = documents.filtered() ? "←→ match   b unfilter   " : "←→ doc   ";
            // a analyze, s search value and m similar come back with the analysis and search screens.
            return " " + cancel + "q quit   o open   c close   1-7 screen   " + step + steps
                    + "i go to   ⏎ term vector   v view   x numbers   e empty   r rebuild   E export   Tab panel   ↑↓";
        }
        if (screen != Screen.OVERVIEW) {
            renderNotPorted(frame, body, screen);
            return " " + cancel + "q quit   o open   c close   R reopen   1-7 screen";
        }
        overview.render(frame, body);
        if (scan != null) {
            return " Esc cancel scan   q quit   o open   c close   R reopen   1-7 screen   s/S sort   Tab panel   ↑↓";
        }
        // / on a top term comes back with the search screen.
        if (overview.selectedTopTerm().isPresent()) {
            return " q quit   o open   c close   R reopen   1-7 screen   ⏎ term's docs   +/- top N   s/S sort   "
                    + "Tab panel   ↑↓";
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

    /** What the tab line says about the running export, once its prompt is closed. */
    private Optional<String> exportLine() {
        if (exportPrompt != null) {
            return Optional.empty();
        }
        return switch (export) {
            case null -> Optional.empty();
            case Loading _ -> Optional.empty();
            case Rebuilding rebuilding ->
                Optional.of("Exporting doc " + rebuilding.docId() + ": " + rebuilding.scanned()
                        + (rebuilding.total().isPresent()
                                ? "/" + rebuilding.total().getAsLong()
                                : "") + " terms");
            case Writing writing -> Optional.of("Exporting doc " + writing.docId() + ": writing…");
        };
    }

    /** The tabs, and the notice, the running export or how the index was opened, right-aligned after them. */
    private void renderTabs(Frame frame, Rect area) {
        Optional<String> exporting = exportLine();
        Line line = notice != null && !notice.inFooter()
                ? notice.line()
                : exporting.isPresent() ? Line.from(exporting.get()) : opened != null ? marker(opened) : null;
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
