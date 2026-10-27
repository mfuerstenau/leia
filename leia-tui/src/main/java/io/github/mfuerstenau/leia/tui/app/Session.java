package io.github.mfuerstenau.leia.tui.app;

import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsParams;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.filter.ReleaseFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.LoadRuntimesParams;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.tui.Osc52;
import io.github.mfuerstenau.leia.tui.documents.DocumentExport;
import io.github.mfuerstenau.leia.tui.documents.Documents;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeInstall;
import io.github.mfuerstenau.leia.tui.runtimes.RuntimeWindow;
import io.github.mfuerstenau.leia.tui.terms.Terms;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs leia's {@link App} on a {@link TuiRunner}: hands it the keys, makes the calls of its {@link
 * Action}s on the {@link LeiaBackend}, and reports their outcome back on the UI thread (ADR 0034
 * §3). The app is only ever touched on the UI thread: the backend's futures complete on the backend
 * thread, so each outcome comes back as a {@link Reply}.
 */
public final class Session {

    private static final Logger LOG = Logger.getLogger(Session.class.getName());

    /** Something to do with the app on the UI thread, and the action that follows, if any. */
    public record Reply(Function<App, Optional<Action>> apply) implements Event {}

    /**
     * How many polls of a new job come at {@link #FIRST_POLL_INTERVAL}, before the 100 ms tick: most
     * searches take a few milliseconds (Rust's {@code FIRST_POLLS}).
     */
    private static final int FIRST_POLLS = 10;

    private static final Duration FIRST_POLL_INTERVAL = Duration.ofMillis(10);

    /** A first poll of the jobs, from the timer; one of an older round is ignored. */
    private record FirstPoll(long round) implements Event {}

    /** Sends the first polls; a daemon, so that it never keeps leia running. */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "leia-first-polls");
        thread.setDaemon(true);
        return thread;
    });

    /** The jobs polled after the last event, to see a new one: on the UI thread. */
    private List<Poll> polled = List.of();

    /** The round of first polls, new with each change of the jobs, and how many are left of it. */
    private long round;

    private int firstPolls;

    private final App ui;
    private final LeiaBackend backend;

    /** Installs into the runtimes folder (ADR 0098); empty if leia doesn't run from {@code leia.jar}. */
    private final Optional<RuntimeInstall> runtimeInstall;

    /** The thread that downloads runtimes, while one runs (ADR 0080). */
    // An immutable value, published through volatile (ADR 0088)
    @SuppressWarnings("java:S3077")
    private volatile Thread installer;

    /** Set once leia quits, which cancels a download. */
    private final AtomicBoolean quitting = new AtomicBoolean();

    /** What made leia quit, if not the user: set on the UI thread. */
    private Throwable failure;

    /** The polls whose call hasn't come back yet, so that a slow one isn't sent again: on the UI thread. */
    private final Set<Poll> polling = new HashSet<>();

    public Session(App app, LeiaBackend backend) {
        this(app, backend, Optional.empty());
    }

    /**
     * @param runtimeInstall installs the runtimes that the window of {@code D} downloads (ADR 0080)
     */
    public Session(App app, LeiaBackend backend, Optional<RuntimeInstall> runtimeInstall) {
        this.ui = app;
        this.backend = backend;
        this.runtimeInstall = runtimeInstall;
    }

    /**
     * Cancels a download that still runs, such as after Ctrl+C, and waits for it, so that it leaves
     * no unfinished file behind.
     */
    public void awaitInstall() throws InterruptedException {
        quitting.set(true);
        Thread running = installer;
        if (running != null) {
            running.join();
        }
    }

    /** An event that performs {@code action}, for an action that no key started. */
    public static Reply perform(Action action) {
        return new Reply(app -> Optional.of(action));
    }

    /** What made leia quit, if it wasn't the user. */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /** Handles an event on the UI thread; returns whether to redraw. */
    public boolean handle(Event event, TuiRunner runner) {
        if (event instanceof FirstPoll first) {
            if (first.round() == round && firstPolls > 0) {
                firstPolls--;
                poll(runner);
                scheduleFirstPoll(runner);
            }
            return false;
        }
        boolean redraw = handleEvent(event, runner);
        startFirstPolls(runner);
        return redraw;
    }

    /**
     * Polls a job that has just started, or a set of jobs that changed, every 10 ms for the first
     * {@link #FIRST_POLLS} polls, as the Rust TUI did; the tick polls every 100 ms after that (ADR
     * 0034 §10). A regex scan's next hit and a check's next output line don't count as a change.
     */
    private void startFirstPolls(TuiRunner runner) {
        List<Poll> polls = new ArrayList<>();
        for (Poll poll : ui.polls()) {
            polls.add(
                    switch (poll) {
                        case Poll.StoredScan scan -> new Poll.StoredScan(scan.scanId(), scan.jobId(), 0);
                        case Poll.Check check -> new Poll.Check(check.checkId(), check.jobId(), 0);
                        default -> poll;
                    });
        }
        if (polls.equals(polled)) {
            return;
        }
        polled = polls;
        round++;
        firstPolls = polls.isEmpty() ? 0 : FIRST_POLLS;
        scheduleFirstPoll(runner);
    }

    private void scheduleFirstPoll(TuiRunner runner) {
        if (firstPolls > 0) {
            FirstPoll next = new FirstPoll(round);
            timer.schedule(() -> runner.dispatch(next), FIRST_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private boolean handleEvent(Event event, TuiRunner runner) {
        return switch (event) {
            case KeyEvent key -> {
                perform(ui.handleKey(key), runner);
                yield true;
            }
            case Reply reply -> {
                perform(reply.apply().apply(ui), runner);
                yield true;
            }
            case TickEvent _ -> {
                poll(runner);
                yield ui.tick();
            }
            default -> false;
        };
    }

    public void render(Frame frame) {
        ui.render(frame);
    }

    private void perform(Optional<Action> action, TuiRunner runner) {
        action.ifPresent(next -> perform(next, runner));
    }

    // The dispatch over the sealed Action (ADR 0088)
    @SuppressWarnings("java:S1479")
    private void perform(Action action, TuiRunner runner) {
        switch (action) {
            case Action.Quit _ -> runner.quit();
            case Action.Open open -> open(open, runner);
            case Action.Close _ ->
                backend.call(Methods.CLOSE_INDEX, new CloseIndexParams()).whenComplete((closed, error) -> {
                    if (error != null) {
                        fail(error, runner);
                    } else {
                        reply(runner, app -> {
                            app.closed();
                            return Optional.empty();
                        });
                    }
                });
            case Action.Reopen _ -> reopen(runner);
            case Action.StartScan start ->
                complete(
                        backend.call(
                                Methods.START_FIELD_TERM_SCAN,
                                new StartFieldTermScanParams(start.field(), start.topN())),
                        runner,
                        job -> app -> app.scanStarted(start.scanId(), job.jobId()),
                        message -> app -> app.scanFailed(start.scanId(), message));
            case Action.LoadCommits load ->
                complete(
                        backend.call(Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.of(load.index()))),
                        runner,
                        result -> app -> app.commitsLoaded(load.index(), result),
                        message -> changing(app -> app.commitsFailed(load.index(), message)));
            case Action.LoadCommit load ->
                complete(
                        backend.call(
                                Methods.GET_COMMIT,
                                new GetCommitParams(load.generation(), OptionalInt.of(load.index()))),
                        runner,
                        result -> changing(app -> app.commitLoaded(load.index(), load.generation(), result)),
                        message -> changing(app -> app.commitFailed(load.index(), load.generation(), message)));
            case Action.LoadAnalysisComponents _ -> loadAnalysisComponents(runner);
            case Action.Analyze analyze ->
                complete(
                        backend.call(Methods.ANALYZE, analyze.params()),
                        runner,
                        result -> app -> app.analyzed(analyze.params().text(), result),
                        message -> app -> app.analyzeFailed(message));
            case Action.LoadTerms load ->
                complete(
                        backend.call(
                                Methods.GET_TERMS, new GetTermsParams(load.field(), load.from(), Terms.TERMS_PAGE)),
                        runner,
                        result -> app -> app.termsLoaded(load, result),
                        message -> app -> app.termsFailed(load, message));
            case Action.LoadPostings load ->
                complete(
                        backend.call(
                                Methods.GET_POSTINGS,
                                new GetPostingsParams(
                                        load.field(), load.term(), load.fromDoc(), Terms.POSTINGS_PAGE, load.filter())),
                        runner,
                        result -> app -> app.postingsLoaded(load, result),
                        message -> changing(app -> app.postingsFailed(load, message)));
            case Action.LoadPointTree load ->
                complete(
                        backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams(load.field())),
                        runner,
                        result -> app -> app.pointTreeLoaded(load, result),
                        message -> app -> app.pointTreeFailed(load, message));
            case Action.LoadPointCell load ->
                complete(
                        backend.call(
                                Methods.GET_POINT_CELL,
                                new GetPointCellParams(load.field(), load.segment(), load.path())),
                        runner,
                        result -> app -> app.pointCellLoaded(load, result),
                        message -> app -> app.pointCellFailed(load, message));
            case Action.LoadKnnGraph load ->
                complete(
                        backend.call(Methods.GET_KNN_GRAPH, new GetKnnGraphParams(load.field())),
                        runner,
                        result -> app -> app.knnGraphLoaded(load, result),
                        message -> app -> app.knnGraphFailed(load, message));
            case Action.LoadKnnNodes load ->
                complete(
                        backend.call(
                                Methods.GET_KNN_GRAPH_NODES,
                                new GetKnnGraphNodesParams(
                                        load.field(),
                                        load.segment(),
                                        load.level(),
                                        load.offset(),
                                        GetKnnGraphNodesParams.MAX_NODES)),
                        runner,
                        result -> app -> app.knnNodesLoaded(load, result),
                        message -> app -> app.knnNodesFailed(load, message));
            case Action.LoadKnnNode load ->
                complete(
                        backend.call(
                                Methods.GET_KNN_GRAPH_NODE,
                                new GetKnnGraphNodeParams(load.field(), load.segment(), load.ord(), load.docId())),
                        runner,
                        result -> app -> app.knnNodeLoaded(load, result),
                        message -> app -> app.knnNodeFailed(load, message));
            case Action.LoadDocument load -> loadDocument(load, runner);
            case Action.LoadStoredValue load ->
                complete(
                        backend.call(
                                Methods.GET_STORED_VALUE,
                                new GetStoredValueParams(load.docId(), load.field(), load.index())),
                        runner,
                        result -> changing(
                                app -> app.storedValueLoaded(load.docId(), load.field(), load.index(), result.value())),
                        message -> changing(
                                app -> app.storedValueFailed(load.docId(), load.field(), load.index(), message)));
            case Action.LoadTermVector load ->
                complete(
                        backend.call(Methods.GET_TERM_VECTOR, new GetTermVectorParams(load.docId(), load.field())),
                        runner,
                        result -> changing(app -> app.termVectorLoaded(load.docId(), load.field(), result.terms())),
                        message -> changing(app -> app.termVectorFailed(load.docId(), load.field(), message)));
            case Action.LoadPositions load ->
                complete(
                        backend.call(
                                Methods.GET_POSITIONS, new GetPositionsParams(load.docId(), load.field(), load.term())),
                        runner,
                        result -> changing(app ->
                                app.positionsLoaded(load.docId(), load.field(), load.term(), result.positions())),
                        message ->
                                changing(app -> app.positionsFailed(load.docId(), load.field(), load.term(), message)));
            case Action.Copy copy -> copy(copy.text(), runner);
            case Action.StartSearch start -> {
                cancelJobs(start.cancel(), runner);
                startJob(
                        backend.call(Methods.START_SEARCH, start.params()),
                        runner,
                        app -> jobId -> app.searchStarted(start.searchId(), jobId),
                        app -> message -> app.searchFailed(start.searchId(), message));
            }
            case Action.StartExplain start -> {
                cancelJobs(start.cancel(), runner);
                startJob(
                        backend.call(Methods.START_EXPLAIN, start.params()),
                        runner,
                        app -> jobId -> app.explainStarted(start.explainId(), jobId),
                        app -> message -> app.explainFailed(start.explainId(), message));
            }
            case Action.StartHighlight start -> {
                cancelJobs(start.cancel(), runner);
                startJob(
                        backend.call(Methods.START_HIGHLIGHT, start.params()),
                        runner,
                        app -> jobId -> app.highlightStarted(start.highlightId(), jobId),
                        app -> message -> app.highlightFailed(start.highlightId(), message));
            }
            case Action.StartStoredScan start -> {
                cancelJobs(start.cancel(), runner);
                startJob(
                        backend.call(Methods.START_STORED_SCAN, start.params()),
                        runner,
                        app -> jobId -> app.storedScanStarted(start.scanId(), jobId),
                        app -> message -> app.storedScanFailed(start.scanId(), message));
            }
            case Action.StartKnnRecall start -> {
                cancelJobs(start.cancel(), runner);
                startJob(
                        backend.call(Methods.START_KNN_RECALL, start.params()),
                        runner,
                        app -> jobId -> app.knnRecallStarted(start.recallId(), jobId),
                        app -> message -> app.knnRecallFailed(start.recallId(), message));
            }
            case Action.CancelSearch cancel -> cancelJobs(cancel.jobs(), runner);
            case Action.LoadCheckTargets _ ->
                complete(
                        backend.call(Methods.GET_CHECK_TARGETS, new GetCheckTargetsParams()),
                        runner,
                        result -> changing(app -> app.checkTargetsLoaded(result)),
                        message -> changing(app -> app.checkTargetsFailed(message)));
            case Action.StartCheck start ->
                startJob(
                        backend.call(
                                Methods.START_CHECK_INDEX,
                                new StartCheckIndexParams(start.level(), start.threads(), start.only())),
                        runner,
                        app -> jobId -> app.checkStarted(start.checkId(), jobId),
                        app -> message -> app.checkFailed(start.checkId(), message));
            case Action.CancelCheck cancel -> cancelJobs(List.of(cancel.jobId()), runner);
            case Action.StartAutoStopWords start ->
                startJob(
                        backend.call(Methods.START_AUTO_STOP_WORDS, start.params()),
                        runner,
                        app -> jobId -> app.autoStopWordsStarted(start.id(), jobId),
                        app -> message -> app.autoStopWordsFailed(start.id(), message));
            case Action.CancelAutoStopWords cancel -> cancelJobs(List.of(cancel.jobId()), runner);
            case Action.StartExportTerms start ->
                startJob(
                        backend.call(Methods.START_EXPORT_TERMS, start.params()),
                        runner,
                        app -> jobId -> app.exportStarted(start.exportId(), jobId),
                        app -> message -> app.exportFailed(start.exportId(), message));
            case Action.StartReconstruct start -> {
                cancelJobs(start.cancel(), runner);
                complete(
                        backend.call(
                                Methods.START_RECONSTRUCT,
                                new StartReconstructParams(start.docId(), Optional.of(List.of(start.field())))),
                        runner,
                        job -> app -> app.reconstructStarted(start.docId(), start.field(), job.jobId()),
                        message -> changing(app -> app.reconstructStartFailed(start.docId(), start.field(), message)));
            }
            case Action.CancelReconstruct cancel -> cancelJobs(List.of(cancel.jobId()), runner);
            case Action.StartFilter start -> {
                clearFilter(start.cancel(), start.release(), runner);
                complete(
                        backend.call(Methods.START_FILTER, start.params()),
                        runner,
                        job -> app -> app.filterStarted(start.serial(), job.jobId()),
                        message -> changing(app -> app.filterFailed(start.serial(), message)));
            }
            case Action.StepFilter step ->
                complete(
                        backend.call(Methods.STEP_FILTER, step.params()),
                        runner,
                        result -> app -> app.filterStepped(step.serial(), step.params(), step.step(), result.match()),
                        message -> changing(app -> app.filterStepFailed(step.serial(), message)));
            case Action.ClearFilter clear -> clearFilter(clear.cancel(), clear.release(), runner);
            case Action.CancelScan cancel -> cancelJobs(List.of(cancel.jobId()), runner);
            case Action.LoadDocumentExport load ->
                complete(
                        DocumentExport.load(exportFetch(), load.docId(), load.termVectors()),
                        runner,
                        data -> app -> app.documentExportLoaded(load.exportId(), data),
                        message -> changing(app -> app.exportFailed(load.exportId(), message)));
            case Action.StartDocumentExport start ->
                complete(
                        backend.call(
                                Methods.START_RECONSTRUCT, new StartReconstructParams(start.docId(), Optional.empty())),
                        runner,
                        job -> app -> app.exportStarted(start.exportId(), job.jobId()),
                        message -> changing(app -> app.exportFailed(start.exportId(), message)));
            case Action.CancelExport cancel -> cancelJobs(List.of(cancel.jobId()), runner);
            case Action.LoadJars _ ->
                complete(
                        backend.call(Methods.GET_JARS, new GetJarsParams()),
                        runner,
                        result -> changing(app -> app.jarsLoaded(result.jars())),
                        message -> changing(app -> app.jarsFailed(message)));
            case Action.AddJars add -> addJars(add, runner);
            case Action.PlanRuntimes plan -> planRuntimes(plan, runner);
            case Action.InstallRuntimes install -> installRuntimes(install.install(), runner);
            case Action.LoadRuntimes _ ->
                complete(
                        backend.call(Methods.LOAD_RUNTIMES, new LoadRuntimesParams()),
                        runner,
                        result -> changing(app -> app.runtimesReloaded(result)),
                        message -> changing(app -> app.reloadRuntimesFailed(message)));
            case Action.WriteExport write -> {
                // On the UI thread, as the Rust TUI wrote it in its loop: a few writes of text.
                Optional<String> error = DocumentExport.writeFile(
                        Path.of(write.path()), write.text(), write.overwrite(), write.indexDirs());
                if (error.isPresent()) {
                    ui.exportFailed(write.exportId(), error.get());
                } else {
                    ui.documentExported(write.exportId(), write.docId(), write.path());
                }
            }
        }
    }

    /** Loads the analysis components; an index whose runtime has no analysis shows none. */
    private void loadAnalysisComponents(TuiRunner runner) {
        backend.call(Methods.GET_ANALYSIS_COMPONENTS, new GetAnalysisComponentsParams())
                .whenComplete((result, error) -> {
                    if (error == null) {
                        reply(runner, app -> {
                            app.componentsLoaded(result);
                            return Optional.empty();
                        });
                        return;
                    }
                    RpcException rpc = rpcException(error, runner);
                    if (rpc != null && rpc.code() == ErrorCode.NOT_SUPPORTED) {
                        reply(runner, app -> {
                            app.noAnalysis();
                            return Optional.empty();
                        });
                    } else if (rpc != null) {
                        String message = describe(rpc);
                        reply(runner, app -> {
                            app.componentsFailed(message);
                            return Optional.empty();
                        });
                    }
                });
    }

    /** Loads a document, and starts finding its matches if asked to. */
    private void loadDocument(Action.LoadDocument load, TuiRunner runner) {
        cancelJobs(load.cancel(), runner);
        load.matches()
                .ifPresent(matches -> startJob(
                        backend.call(Methods.START_HIGHLIGHT, matches.params()),
                        runner,
                        app -> jobId -> app.documentMatchesStarted(matches.matchesId(), jobId),
                        app -> message -> app.documentMatchesFailed(matches.matchesId(), message)));
        backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(load.docId())).whenComplete((result, error) -> {
            if (error == null) {
                if (load.locate().isPresent()) {
                    locate(load.locate().get(), result, runner);
                } else {
                    reply(runner, app -> app.documentLoaded(result));
                }
                return;
            }
            RpcException rpc = rpcException(error, runner);
            if (rpc != null) {
                String message = describe(rpc);
                reply(runner, app -> {
                    app.documentFailed(load.docId(), message);
                    return Optional.empty();
                });
            }
        });
    }

    private void addJars(Action.AddJars add, TuiRunner runner) {
        backend.call(Methods.ADD_JARS, add.params()).whenComplete((result, error) -> {
            if (error == null) {
                reply(runner, app -> app.jarsAdded(result));
                // The jars may hold a runtime (ADR 0072).
                loadRuntimes(runner);
                return;
            }
            RpcException rpc = rpcException(error, runner);
            if (rpc != null) {
                String message = describe(rpc);
                reply(runner, app -> {
                    app.addJarsFailed(message);
                    return Optional.empty();
                });
            }
        });
    }

    /** Plans the download on a thread of its own: it hashes the jars that are there (ADR 0080). */
    private void planRuntimes(Action.PlanRuntimes plan, TuiRunner runner) {
        if (runtimeInstall.isEmpty()) {
            reply(runner, app -> {
                app.runtimesPlanFailed("leia doesn't run from leia.jar, so it has no runtimes folder");
                return Optional.empty();
            });
            return;
        }
        RuntimeInstall install = runtimeInstall.get();
        Thread.ofPlatform().daemon().name("leia-runtime-plan").start(() -> {
            try {
                RuntimeInstall.Plan planned = install.plan(plan.runtimes());
                // Only read: the present runtimes' plans never reach the install (ADR 0081).
                List<RuntimeInstall.RuntimePlan> present =
                        install.plan(plan.present()).runtimes();
                reply(runner, app -> {
                    app.runtimesPlanned(install, planned, present);
                    return Optional.empty();
                });
            } catch (RuntimeInstall.InstallException | RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot plan the runtimes' download", e);
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                reply(runner, app -> {
                    app.runtimesPlanFailed(message);
                    return Optional.empty();
                });
            }
        });
    }

    /**
     * Downloads the runtimes on a thread of its own, not as a backend job (ADR 0080): no index is
     * involved, and the backend thread stays free. Quitting leia cancels it.
     */
    private void installRuntimes(RuntimeWindow.Install install, TuiRunner runner) {
        installer = Thread.ofPlatform().daemon().name("leia-runtime-install").start(() -> {
            RuntimeInstall.Outcome outcome;
            try {
                outcome = install.install()
                        .install(
                                install.plan(),
                                install.chosen(),
                                install.listener(),
                                () -> quitting.get() || install.cancelled().getAsBoolean());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "The runtimes' download failed", e);
                outcome = RuntimeWindow.failedOutcome(install.chosen(), e);
            }
            RuntimeInstall.Outcome done = outcome;
            reply(runner, app -> {
                installer = null;
                return app.runtimesInstalled(done);
            });
        });
    }

    /** Hands the app the runtime jars that are loaded and missing now, for the about screen. */
    private void loadRuntimes(TuiRunner runner) {
        backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams()).whenComplete((result, error) -> {
            if (error == null) {
                reply(runner, app -> {
                    app.runtimesLoaded(result);
                    return Optional.empty();
                });
            } else {
                RpcException rpc = rpcException(error, runner);
                if (rpc != null) {
                    LOG.log(Level.WARNING, "Cannot list the runtimes", rpc);
                }
            }
        });
    }

    /**
     * Finds out whether the loaded {@code document} matches the filter of {@code locate}, then shows
     * it: both in one reply, so the title knows which match it is when the document shows.
     */
    private void locate(Documents.Locate locate, GetDocumentResult document, TuiRunner runner) {
        StepFilterParams params = new StepFilterParams(locate.filterId(), document.docId(), true);
        backend.call(Methods.STEP_FILTER, params).whenComplete((result, error) -> {
            if (error == null) {
                reply(runner, app -> {
                    app.filterLocated(locate.serial(), params, result.match());
                    return app.documentLoaded(document);
                });
                return;
            }
            RpcException rpc = rpcException(error, runner);
            if (rpc != null) {
                String message = describe(rpc);
                reply(runner, app -> {
                    app.filterFailed(locate.serial(), message);
                    return app.documentLoaded(document);
                });
            }
        });
    }

    /** The calls a document export fetches the document with (ADR 0030). */
    private DocumentExport.Fetch exportFetch() {
        return new DocumentExport.Fetch() {
            @Override
            public CompletionStage<GetDocumentResult> document(int docId) {
                return backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(docId));
            }

            @Override
            public CompletionStage<StoredValue> storedValue(int docId, String field, int index) {
                return backend.call(Methods.GET_STORED_VALUE, new GetStoredValueParams(docId, field, index))
                        .thenApply(GetStoredValueResult::value);
            }

            @Override
            public CompletionStage<List<TermVectorTerm>> termVector(int docId, String field) {
                return backend.call(Methods.GET_TERM_VECTOR, new GetTermVectorParams(docId, field))
                        .thenApply(GetTermVectorResult::terms);
            }
        };
    }

    /**
     * Hands the app the outcome of {@code call} on the UI thread: its result through {@code done}, or
     * the message of an expected failure through {@code failed}. An unexpected failure ends leia.
     */
    private <R> void complete(
            CompletionStage<R> call,
            TuiRunner runner,
            Function<R, Function<App, Optional<Action>>> done,
            Function<String, Function<App, Optional<Action>>> failed) {
        complete(call, runner, apply -> reply(runner, apply), done, failed);
    }

    /** As {@link #complete} does, for the call that fetches {@code poll}, which may then be sent again. */
    private <R> void fetch(
            Poll poll,
            CompletionStage<R> call,
            TuiRunner runner,
            Function<R, Function<App, Optional<Action>>> done,
            Function<String, Function<App, Optional<Action>>> failed) {
        complete(call, runner, apply -> polled(runner, poll, apply), done, failed);
    }

    private <R> void complete(
            CompletionStage<R> call,
            TuiRunner runner,
            Consumer<Function<App, Optional<Action>>> deliver,
            Function<R, Function<App, Optional<Action>>> done,
            Function<String, Function<App, Optional<Action>>> failed) {
        call.whenComplete((result, error) -> {
            if (error == null) {
                deliver.accept(done.apply(result));
                return;
            }
            RpcException rpc = rpcException(error, runner);
            if (rpc != null) {
                deliver.accept(failed.apply(describe(rpc)));
            }
        });
    }

    /** A reply that only changes the app. */
    private static Function<App, Optional<Action>> changing(Consumer<App> change) {
        return app -> {
            change.accept(app);
            return Optional.empty();
        };
    }

    /**
     * Reports a job that started with {@code started}, or why it didn't with {@code failed}, on the UI
     * thread.
     */
    private void startJob(
            CompletionStage<JobStarted> call,
            TuiRunner runner,
            Function<App, LongFunction<Optional<Action>>> started,
            Function<App, Consumer<String>> failed) {
        call.whenComplete((job, error) -> {
            if (error == null) {
                reply(runner, app -> started.apply(app).apply(job.jobId()));
                return;
            }
            RpcException rpc = rpcException(error, runner);
            if (rpc != null) {
                String message = describe(rpc);
                reply(runner, app -> {
                    failed.apply(app).accept(message);
                    return Optional.empty();
                });
            }
        });
    }

    /** Cancels these jobs; nothing is reported back. The backend answers in order, so this comes before any later call. */
    private void cancelJobs(List<Long> jobs, TuiRunner runner) {
        for (long jobId : jobs) {
            backend.call(Methods.CANCEL_JOB, new JobParams(jobId)).whenComplete((cancelled, error) -> {
                if (error != null) {
                    rpcException(error, runner);
                }
            });
        }
    }

    /** Cancels the job that builds a filter, and releases a built one; nothing is reported back. */
    private void clearFilter(OptionalLong cancel, OptionalLong release, TuiRunner runner) {
        cancel.ifPresent(jobId -> cancelJobs(List.of(jobId), runner));
        release.ifPresent(filterId -> backend.call(Methods.RELEASE_FILTER, new ReleaseFilterParams(filterId))
                .whenComplete((released, error) -> {
                    if (error != null) {
                        rpcException(error, runner);
                    }
                }));
    }

    /**
     * Puts {@code text} on the terminal's clipboard: written on the UI thread, between two frames,
     * so the sequence can't land inside one. A terminal that can't take it ignores it.
     */
    private static void copy(String text, TuiRunner runner) {
        try {
            runner.backend().writeRaw(Osc52.of(text));
            runner.backend().flush();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot copy to the clipboard", e);
        }
    }

    /** Fetches the progress or the outcome of each running job that isn't being fetched already. */
    private void poll(TuiRunner runner) {
        for (Poll poll : ui.polls()) {
            if (!polling.add(poll)) {
                continue;
            }
            switch (poll) {
                case Poll.Scan scan ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_FIELD_TERM_SCAN, new JobParams(scan.jobId())),
                            runner,
                            result -> app -> app.scanProgress(scan.scanId(), result),
                            message -> app -> app.scanFailed(scan.scanId(), message));
                case Poll.Reconstruct rebuild ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_RECONSTRUCT, new JobParams(rebuild.jobId())),
                            runner,
                            result -> changing(app -> app.reconstructProgress(rebuild.jobId(), result)),
                            message -> changing(app -> app.reconstructFailed(rebuild.jobId(), message)));
                case Poll.Filter filter ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_FILTER, new JobParams(filter.jobId())),
                            runner,
                            result -> app -> app.filterProgress(filter.serial(), result),
                            message -> changing(app -> app.filterFailed(filter.serial(), message)));
                case Poll.Search searching ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_SEARCH, new JobParams(searching.jobId())),
                            runner,
                            result -> app -> app.searchProgress(searching.searchId(), result),
                            message -> changing(app -> app.searchFailed(searching.searchId(), message)));
                case Poll.AutoStopWords finding ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_AUTO_STOP_WORDS, new JobParams(finding.jobId())),
                            runner,
                            result -> changing(app -> app.autoStopWordsProgress(finding.id(), result)),
                            message -> changing(app -> app.autoStopWordsFailed(finding.id(), message)));
                case Poll.Explain explaining ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_EXPLAIN, new JobParams(explaining.jobId())),
                            runner,
                            result -> changing(app -> app.explainProgress(explaining.explainId(), result)),
                            message -> changing(app -> app.explainFailed(explaining.explainId(), message)));
                case Poll.Highlight highlighting ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_HIGHLIGHT, new JobParams(highlighting.jobId())),
                            runner,
                            result -> app -> app.highlightProgress(highlighting.highlightId(), result),
                            message -> changing(app -> app.highlightFailed(highlighting.highlightId(), message)));
                case Poll.DocumentMatches finding ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_HIGHLIGHT, new JobParams(finding.jobId())),
                            runner,
                            result -> changing(app -> app.documentMatchesProgress(finding.matchesId(), result)),
                            message -> changing(app -> app.documentMatchesFailed(finding.matchesId(), message)));
                case Poll.KnnRecall checking ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_KNN_RECALL, new JobParams(checking.jobId())),
                            runner,
                            result -> changing(app -> app.knnRecallProgress(checking.recallId(), result)),
                            message -> changing(app -> app.knnRecallFailed(checking.recallId(), message)));
                case Poll.StoredScan scanning ->
                    fetch(
                            poll,
                            backend.call(
                                    Methods.GET_STORED_SCAN,
                                    new GetStoredScanParams(scanning.jobId(), scanning.fromHit())),
                            runner,
                            result -> changing(app -> app.storedScanProgress(scanning.scanId(), result)),
                            message -> changing(app -> app.storedScanFailed(scanning.scanId(), message)));
                case Poll.DocumentExport exporting ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_RECONSTRUCT, new JobParams(exporting.jobId())),
                            runner,
                            result -> app -> app.documentExportProgress(exporting.exportId(), result),
                            message -> changing(app -> app.exportFailed(exporting.exportId(), message)));
                case Poll.Check checking ->
                    fetch(
                            poll,
                            backend.call(
                                    Methods.GET_CHECK_INDEX,
                                    new GetCheckIndexParams(checking.jobId(), checking.fromLine())),
                            runner,
                            result -> changing(app -> app.checkProgress(checking.checkId(), result)),
                            message -> changing(app -> app.checkFailed(checking.checkId(), message)));
                case Poll.ExportTerms exporting ->
                    fetch(
                            poll,
                            backend.call(Methods.GET_EXPORT_TERMS, new JobParams(exporting.jobId())),
                            runner,
                            result -> changing(app -> app.termsExportProgress(exporting.exportId(), result)),
                            message -> changing(app -> app.exportFailed(exporting.exportId(), message)));
            }
        }
    }

    /** Reports what {@code poll} brought back, and lets it be sent again. */
    private void polled(TuiRunner runner, Poll poll, Function<App, Optional<Action>> apply) {
        reply(runner, app -> {
            polling.remove(poll);
            return apply.apply(app);
        });
    }

    /**
     * Opens the index and, with a reader, fetches its statistics for the overview. An index that
     * opens but whose statistics can't be read is closed rather than kept open unseen.
     */
    private void open(Action.Open open, TuiRunner runner) {
        OpenIndexParams params =
                new OpenIndexParams(open.path(), Optional.of(open.directory()), Optional.of(open.mode()));
        backend.call(Methods.OPEN_INDEX, params).whenComplete((result, error) -> {
            if (error != null) {
                RpcException rpc = rpcException(error, runner);
                if (rpc != null) {
                    // A reader can't open a corrupt index, but its commits may still be readable; and Lucene 10
                    // checks a Lucene 8 index without its runtime (ADR 0044).
                    boolean noReaderHelps = rpc.code() == ErrorCode.CORRUPT_INDEX
                            || rpc.code() == ErrorCode.MISSING_CODEC
                            || rpc.code() == ErrorCode.MISSING_RUNTIME;
                    String message = describe(withJarHint(rpc));
                    reply(runner, app -> {
                        app.openFailed(message, noReaderHelps);
                        return Optional.empty();
                    });
                }
                return;
            }
            if (result.mode() == OpenMode.DIRECTORY_ONLY) {
                reply(runner, app -> app.openedWithoutReader(result));
                return;
            }
            backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams()).whenComplete((stats, statsError) -> {
                if (statsError == null) {
                    reply(
                            runner,
                            app -> app.opened(
                                    stats,
                                    result.skipped(),
                                    result.features(),
                                    result.luceneRuntime(),
                                    result.server()));
                } else {
                    closeUnseen(statsError, runner);
                }
            });
        });
    }

    /**
     * Reopens the index if it changed, and fetches its statistics for the overview (ADR 0019). A
     * failed reopen leaves the open index open, and only says why.
     */
    private void reopen(TuiRunner runner) {
        backend.call(Methods.REOPEN_INDEX, new ReopenIndexParams()).whenComplete((result, error) -> {
            if (error != null) {
                RpcException rpc = rpcException(error, runner);
                if (rpc != null) {
                    String message = describe(withJarHint(rpc));
                    reply(runner, app -> {
                        app.reopenFailed(message);
                        return Optional.empty();
                    });
                }
                return;
            }
            if (!result.changed()) {
                reply(runner, app -> {
                    app.reopenUnchanged(result);
                    return Optional.empty();
                });
                return;
            }
            backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams()).whenComplete((stats, statsError) -> {
                if (statsError == null) {
                    reply(runner, app -> {
                        Optional<Action> next = app.reopened(stats, result.features());
                        // The backend forgot the filter with the old index: build it again (ADR 0032).
                        app.rebuildFilter().ifPresent(rebuild -> perform(rebuild, runner));
                        return next;
                    });
                } else {
                    closeUnseen(statsError, runner);
                }
            });
        });
    }

    /** Opened, but the overview can't show it: closes it rather than keep it open unseen. */
    private void closeUnseen(Throwable statsError, TuiRunner runner) {
        RpcException rpc = rpcException(statsError, runner);
        if (rpc == null) {
            return;
        }
        String message = "cannot read the index statistics: " + describe(rpc);
        backend.call(Methods.CLOSE_INDEX, new CloseIndexParams()).whenComplete((closed, error) -> {
            if (error != null) {
                fail(error, runner);
            } else {
                reply(runner, app -> {
                    app.closed();
                    app.openFailed(message, false);
                    return Optional.empty();
                });
            }
        });
    }

    /**
     * The {@link RpcException} that {@code error} is, an expected failure that the app shows; or
     * null after anything else, which ends leia.
     */
    private RpcException rpcException(Throwable error, TuiRunner runner) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RpcException rpc) {
            return rpc;
        }
        fail(cause, runner);
        return null;
    }

    /** Ends leia after something that shouldn't happen; {@code Main} says so once the terminal is back. */
    private void fail(Throwable error, TuiRunner runner) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        LOG.log(Level.SEVERE, "Unexpected failure", cause);
        reply(runner, app -> {
            failure = cause;
            return Optional.of(new Action.Quit());
        });
    }

    private static void reply(TuiRunner runner, Function<App, Optional<Action>> apply) {
        runner.dispatch(new Reply(apply));
    }

    /** How an expected failure is shown: its message, then its code. */
    static String describe(RpcException error) {
        return error.getMessage() + " (" + error.code().name() + " "
                + error.code().code() + ")";
    }

    /**
     * A codec or format that isn't loaded can come from a jar that X adds or leia is started with
     * (ADR 0018, ADR 0024), or from the Elasticsearch or OpenSearch installation of {@code --server}
     * (ADR 0090). A runtime, which the message names, comes from its folder in {@code
     * runtimes/}, which {@code leia --download-runtimes} installs (ADR 0073).
     */
    static RpcException withJarHint(RpcException error) {
        String hint = switch (error.code()) {
            case MISSING_CODEC ->
                "; add a jar that has it with X, or start leia with --jar; for an Elasticsearch or OpenSearch"
                        + " index, start leia with --server and its installation";
            case MISSING_RUNTIME -> "; close the index (c) and press D on the start screen to download it";
            default -> null;
        };
        if (hint == null) {
            return error;
        }
        return new RpcException(error.code(), error.getMessage() + hint, error);
    }
}
