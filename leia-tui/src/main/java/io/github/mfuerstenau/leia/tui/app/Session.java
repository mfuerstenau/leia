package io.github.mfuerstenau.leia.tui.app;

import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.server.LeiaBackend;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.tui.terms.Terms;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
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
            case Action.CancelScan cancel ->
                backend.call(Methods.CANCEL_JOB, new JobParams(cancel.jobId())).whenComplete((cancelled, error) -> {
                    if (error != null) {
                        rpcException(error, runner);
                    }
                });
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
                    // A reader can't open a corrupt index, but its commits may still be readable.
                    boolean noReaderHelps =
                            rpc.code() == ErrorCode.CORRUPT_INDEX || rpc.code() == ErrorCode.MISSING_CODEC;
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
                    reply(runner, app -> app.opened(stats, result.skipped()));
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
                    reply(runner, app -> app.reopened(stats));
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
     * A codec or format that isn't loaded can come from a jar that leia is started with (ADR 0018,
     * ADR 0024). The jars popup (X) comes back with its port.
     */
    static RpcException withJarHint(RpcException error) {
        if (error.code() != ErrorCode.MISSING_CODEC) {
            return error;
        }
        return new RpcException(
                error.code(), error.getMessage() + "; start leia with --jar and a jar that has it", error);
    }
}
