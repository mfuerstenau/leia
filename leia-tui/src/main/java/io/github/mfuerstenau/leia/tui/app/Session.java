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
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
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

    private final App app;
    private final LeiaBackend backend;

    /** What made leia quit, if not the user: set on the UI thread. */
    private Throwable failure;

    /** The polls whose call hasn't come back yet, so that a slow one isn't sent again: on the UI thread. */
    private final Set<Poll> polling = new HashSet<>();

    public Session(App app, LeiaBackend backend) {
        this.app = app;
        this.backend = backend;
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
        for (Poll poll : app.polls()) {
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
                perform(app.handleKey(key), runner);
                yield true;
            }
            case Reply reply -> {
                perform(reply.apply().apply(app), runner);
                yield true;
            }
            case TickEvent _ -> {
                poll(runner);
                yield false;
            }
            default -> false;
        };
    }

    public void render(Frame frame) {
        app.render(frame);
    }

    private void perform(Optional<Action> action, TuiRunner runner) {
        action.ifPresent(next -> perform(next, runner));
    }

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
                backend.call(Methods.START_FIELD_TERM_SCAN, new StartFieldTermScanParams(start.field(), start.topN()))
                        .whenComplete((job, error) -> {
                            if (error == null) {
                                reply(runner, app -> app.scanStarted(start.scanId(), job.jobId()));
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> app.scanFailed(start.scanId(), message));
                            }
                        });
            case Action.LoadCommits load ->
                backend.call(Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.of(load.index())))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> app.commitsLoaded(load.index(), result));
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.commitsFailed(load.index(), message);
                                    return Optional.empty();
                                });
                            }
                        });
            case Action.LoadCommit load ->
                backend.call(Methods.GET_COMMIT, new GetCommitParams(load.generation(), OptionalInt.of(load.index())))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> {
                                    app.commitLoaded(load.index(), load.generation(), result);
                                    return Optional.empty();
                                });
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.commitFailed(load.index(), load.generation(), message);
                                    return Optional.empty();
                                });
                            }
                        });
            case Action.LoadAnalysisComponents _ ->
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
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.componentsFailed(message);
                                    return Optional.empty();
                                });
                            }
                        });
            case Action.Analyze analyze ->
                backend.call(Methods.ANALYZE, analyze.params()).whenComplete((result, error) -> {
                    if (error == null) {
                        reply(runner, app -> app.analyzed(analyze.params().text(), result));
                        return;
                    }
                    RpcException rpc = rpcException(error, runner);
                    if (rpc != null) {
                        String message = describe(rpc);
                        reply(runner, app -> app.analyzeFailed(message));
                    }
                });
            case Action.LoadTerms load ->
                backend.call(Methods.GET_TERMS, new GetTermsParams(load.field(), load.from(), Terms.TERMS_PAGE))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> app.termsLoaded(load, result));
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> app.termsFailed(load, message));
                            }
                        });
            case Action.LoadPostings load ->
                backend.call(
                                Methods.GET_POSTINGS,
                                new GetPostingsParams(
                                        load.field(), load.term(), load.fromDoc(), Terms.POSTINGS_PAGE, load.filter()))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> app.postingsLoaded(load, result));
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.postingsFailed(load, message);
                                    return Optional.empty();
                                });
                            }
                        });
            case Action.LoadPointTree load ->
                backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams(load.field()))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> app.pointTreeLoaded(load, result));
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> app.pointTreeFailed(load, message));
                            }
                        });
            case Action.LoadPointCell load ->
                backend.call(Methods.GET_POINT_CELL, new GetPointCellParams(load.field(), load.segment(), load.path()))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> app.pointCellLoaded(load, result));
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> app.pointCellFailed(load, message));
                            }
                        });
            case Action.LoadDocument load -> {
                cancelJobs(load.cancel(), runner);
                load.matches()
                        .ifPresent(matches -> startJob(
                                backend.call(Methods.START_HIGHLIGHT, matches.params()),
                                runner,
                                app -> jobId -> app.documentMatchesStarted(matches.matchesId(), jobId),
                                app -> message -> app.documentMatchesFailed(matches.matchesId(), message)));
                backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(load.docId()))
                        .whenComplete((result, error) -> {
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
            case Action.LoadStoredValue load ->
                backend.call(
                                Methods.GET_STORED_VALUE,
                                new GetStoredValueParams(load.docId(), load.field(), load.index()))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> {
                                    app.storedValueLoaded(load.docId(), load.field(), load.index(), result.value());
                                    return Optional.empty();
                                });
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.storedValueFailed(load.docId(), load.field(), load.index(), message);
                                    return Optional.empty();
                                });
                            }
                        });
            case Action.LoadTermVector load ->
                backend.call(Methods.GET_TERM_VECTOR, new GetTermVectorParams(load.docId(), load.field()))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> {
                                    app.termVectorLoaded(load.docId(), load.field(), result.terms());
                                    return Optional.empty();
                                });
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.termVectorFailed(load.docId(), load.field(), message);
                                    return Optional.empty();
                                });
                            }
                        });
            case Action.LoadPositions load ->
                backend.call(Methods.GET_POSITIONS, new GetPositionsParams(load.docId(), load.field(), load.term()))
                        .whenComplete((result, error) -> {
                            if (error == null) {
                                reply(runner, app -> {
                                    app.positionsLoaded(load.docId(), load.field(), load.term(), result.positions());
                                    return Optional.empty();
                                });
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.positionsFailed(load.docId(), load.field(), load.term(), message);
                                    return Optional.empty();
                                });
                            }
                        });
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
            case Action.CancelSearch cancel -> cancelJobs(cancel.jobs(), runner);
            case Action.StartCheck start ->
                startJob(
                        backend.call(
                                Methods.START_CHECK_INDEX, new StartCheckIndexParams(OptionalInt.of(start.level()))),
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
                backend.call(
                                Methods.START_RECONSTRUCT,
                                new StartReconstructParams(start.docId(), Optional.of(List.of(start.field()))))
                        .whenComplete((job, error) -> {
                            if (error == null) {
                                reply(runner, app -> app.reconstructStarted(start.docId(), start.field(), job.jobId()));
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.reconstructStartFailed(start.docId(), start.field(), message);
                                    return Optional.empty();
                                });
                            }
                        });
            }
            case Action.CancelReconstruct cancel -> cancelJobs(List.of(cancel.jobId()), runner);
            case Action.StartFilter start -> {
                clearFilter(start.cancel(), start.release(), runner);
                backend.call(Methods.START_FILTER, start.params()).whenComplete((job, error) -> {
                    if (error == null) {
                        reply(runner, app -> app.filterStarted(start.serial(), job.jobId()));
                        return;
                    }
                    RpcException rpc = rpcException(error, runner);
                    if (rpc != null) {
                        String message = describe(rpc);
                        reply(runner, app -> {
                            app.filterFailed(start.serial(), message);
                            return Optional.empty();
                        });
                    }
                });
            }
            case Action.StepFilter step ->
                backend.call(Methods.STEP_FILTER, step.params()).whenComplete((result, error) -> {
                    if (error == null) {
                        reply(
                                runner,
                                app -> app.filterStepped(step.serial(), step.params(), step.step(), result.match()));
                        return;
                    }
                    RpcException rpc = rpcException(error, runner);
                    if (rpc != null) {
                        String message = describe(rpc);
                        reply(runner, app -> {
                            app.filterStepFailed(step.serial(), message);
                            return Optional.empty();
                        });
                    }
                });
            case Action.ClearFilter clear -> clearFilter(clear.cancel(), clear.release(), runner);
            case Action.CancelScan cancel -> cancelJobs(List.of(cancel.jobId()), runner);
            case Action.LoadDocumentExport load ->
                DocumentExport.load(exportFetch(), load.docId()).whenComplete((data, error) -> {
                    if (error == null) {
                        reply(runner, app -> app.documentExportLoaded(load.exportId(), data));
                        return;
                    }
                    RpcException rpc = rpcException(error, runner);
                    if (rpc != null) {
                        String message = describe(rpc);
                        reply(runner, app -> {
                            app.exportFailed(load.exportId(), message);
                            return Optional.empty();
                        });
                    }
                });
            case Action.StartDocumentExport start ->
                backend.call(Methods.START_RECONSTRUCT, new StartReconstructParams(start.docId(), Optional.empty()))
                        .whenComplete((job, error) -> {
                            if (error == null) {
                                reply(runner, app -> app.exportStarted(start.exportId(), job.jobId()));
                                return;
                            }
                            RpcException rpc = rpcException(error, runner);
                            if (rpc != null) {
                                String message = describe(rpc);
                                reply(runner, app -> {
                                    app.exportFailed(start.exportId(), message);
                                    return Optional.empty();
                                });
                            }
                        });
            case Action.CancelExport cancel -> cancelJobs(List.of(cancel.jobId()), runner);
            case Action.LoadJars _ ->
                backend.call(Methods.GET_JARS, new GetJarsParams()).whenComplete((result, error) -> {
                    if (error == null) {
                        reply(runner, app -> {
                            app.jarsLoaded(result.jars());
                            return Optional.empty();
                        });
                        return;
                    }
                    RpcException rpc = rpcException(error, runner);
                    if (rpc != null) {
                        String message = describe(rpc);
                        reply(runner, app -> {
                            app.jarsFailed(message);
                            return Optional.empty();
                        });
                    }
                });
            case Action.AddJars add ->
                backend.call(Methods.ADD_JARS, add.params()).whenComplete((result, error) -> {
                    if (error == null) {
                        reply(runner, app -> app.jarsAdded(result));
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
            case Action.WriteExport write -> {
                // On the UI thread, as the Rust TUI wrote it in its loop: a few writes of text.
                Optional<String> error = DocumentExport.writeFile(
                        Path.of(write.path()), write.text(), write.overwrite(), write.indexDirs());
                if (error.isPresent()) {
                    app.exportFailed(write.exportId(), error.get());
                } else {
                    app.documentExported(write.exportId(), write.docId(), write.path());
                }
            }
        }
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
        for (Poll poll : app.polls()) {
            if (!polling.add(poll)) {
                continue;
            }
            switch (poll) {
                case Poll.Scan scan ->
                    backend.call(Methods.GET_FIELD_TERM_SCAN, new JobParams(scan.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> app.scanProgress(scan.scanId(), result));
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> app.scanFailed(scan.scanId(), message));
                                }
                            });
                case Poll.Reconstruct rebuild ->
                    backend.call(Methods.GET_RECONSTRUCT, new JobParams(rebuild.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> {
                                        app.reconstructProgress(rebuild.jobId(), result);
                                        return Optional.empty();
                                    });
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.reconstructFailed(rebuild.jobId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.Filter filter ->
                    backend.call(Methods.GET_FILTER, new JobParams(filter.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> app.filterProgress(filter.serial(), result));
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.filterFailed(filter.serial(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.Search searching ->
                    backend.call(Methods.GET_SEARCH, new JobParams(searching.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> app.searchProgress(searching.searchId(), result));
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.searchFailed(searching.searchId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.AutoStopWords finding ->
                    backend.call(Methods.GET_AUTO_STOP_WORDS, new JobParams(finding.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> {
                                        app.autoStopWordsProgress(finding.id(), result);
                                        return Optional.empty();
                                    });
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.autoStopWordsFailed(finding.id(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.Explain explaining ->
                    backend.call(Methods.GET_EXPLAIN, new JobParams(explaining.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> {
                                        app.explainProgress(explaining.explainId(), result);
                                        return Optional.empty();
                                    });
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.explainFailed(explaining.explainId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.Highlight highlighting ->
                    backend.call(Methods.GET_HIGHLIGHT, new JobParams(highlighting.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(
                                            runner,
                                            poll,
                                            app -> app.highlightProgress(highlighting.highlightId(), result));
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.highlightFailed(highlighting.highlightId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.DocumentMatches finding ->
                    backend.call(Methods.GET_HIGHLIGHT, new JobParams(finding.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> {
                                        app.documentMatchesProgress(finding.matchesId(), result);
                                        return Optional.empty();
                                    });
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.documentMatchesFailed(finding.matchesId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.StoredScan scanning ->
                    backend.call(Methods.GET_STORED_SCAN, new GetStoredScanParams(scanning.jobId(), scanning.fromHit()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> {
                                        app.storedScanProgress(scanning.scanId(), result);
                                        return Optional.empty();
                                    });
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.storedScanFailed(scanning.scanId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.DocumentExport exporting ->
                    backend.call(Methods.GET_RECONSTRUCT, new JobParams(exporting.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(
                                            runner,
                                            poll,
                                            app -> app.documentExportProgress(exporting.exportId(), result));
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.exportFailed(exporting.exportId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.Check checking ->
                    backend.call(
                                    Methods.GET_CHECK_INDEX,
                                    new GetCheckIndexParams(checking.jobId(), checking.fromLine()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> {
                                        app.checkProgress(checking.checkId(), result);
                                        return Optional.empty();
                                    });
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.checkFailed(checking.checkId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
                case Poll.ExportTerms exporting ->
                    backend.call(Methods.GET_EXPORT_TERMS, new JobParams(exporting.jobId()))
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    polled(runner, poll, app -> {
                                        app.termsExportProgress(exporting.exportId(), result);
                                        return Optional.empty();
                                    });
                                    return;
                                }
                                RpcException rpc = rpcException(error, runner);
                                if (rpc != null) {
                                    String message = describe(rpc);
                                    polled(runner, poll, app -> {
                                        app.exportFailed(exporting.exportId(), message);
                                        return Optional.empty();
                                    });
                                }
                            });
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
                            app -> app.opened(stats, result.skipped(), result.features(), result.luceneRuntime()));
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
                        Optional<Action> next = app.reopened(stats);
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
     * (ADR 0018, ADR 0024), and so can a runtime for an old index, which the message names (ADR 0044).
     */
    static RpcException withJarHint(RpcException error) {
        String hint = switch (error.code()) {
            case MISSING_CODEC -> "; add a jar that has it with X, or start leia with --jar";
            case MISSING_RUNTIME -> "; add it with X, or start leia with --jar";
            default -> null;
        };
        if (hint == null) {
            return error;
        }
        return new RpcException(error.code(), error.getMessage() + hint, error);
    }
}
