package io.github.mfuerstenau.leia.client.export;

import io.github.mfuerstenau.leia.client.export.DocumentExport.DocumentData;
import io.github.mfuerstenau.leia.client.export.DocumentExporter.Request;
import io.github.mfuerstenau.leia.client.export.DocumentExporter.Target;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * One export of a document (ADR 0030), as a handle that a UI polls (ADR 0126): {@link #snapshot}
 * says how far it is, {@link #cancel} stops it and the backend's rebuild job, and {@link #done}
 * completes once it has ended.
 *
 * <p>Every method may be called from any thread.
 */
public final class ExportJob {

    /** Where the export is. */
    public enum State {
        /** The document is being fetched. */
        LOADING,
        /** Its indexed terms and points are being rebuilt. */
        REBUILDING,
        /** It is put together and written. */
        WRITING,
        DONE,
        FAILED,
        CANCELLED
    }

    /**
     * How far the export is.
     *
     * @param scanned what the rebuild went through so far
     * @param total what the rebuild goes through; null if not known
     * @param text the JSON, once done, for {@link Target.Text}; else null
     * @param path the file written, once done, for {@link Target.File}; else null
     * @param error why it failed, as a sentence; null unless it failed
     * @param cause the failure of the call that failed it, if one did, such as an {@code
     *     RpcException}, so that a UI can describe it as it does its other calls; else null
     */
    public record Snapshot(
            State state,
            long scanned,
            @Nullable Long total,
            @Nullable String text,
            @Nullable Path path,
            @Nullable String error,
            @Nullable Throwable cause) {

        static Snapshot of(State state) {
            return new Snapshot(state, 0, null, null, null, null, null);
        }

        Snapshot with(State next) {
            return new Snapshot(next, scanned, total, text, path, error, cause);
        }

        /** Whether the export has ended. */
        public boolean ended() {
            return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
        }
    }

    private final DocumentExporter.Calls calls;
    private final Request request;
    private final Executor later;
    private final CompletableFuture<Snapshot> done = new CompletableFuture<>();

    // Guarded by this.
    private Snapshot snapshot = Snapshot.of(State.LOADING);
    private @Nullable Long rebuildJob;

    private ExportJob(DocumentExporter.Calls calls, Request request, Duration poll) {
        this.calls = calls;
        this.request = request;
        this.later = CompletableFuture.delayedExecutor(poll.toNanos(), TimeUnit.NANOSECONDS);
    }

    static ExportJob start(DocumentExporter.Calls calls, Request request, Duration poll) {
        ExportJob job = new ExportJob(calls, request, poll);
        job.load();
        return job;
    }

    /** How far the export is. */
    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    /** Completes with the last snapshot once the export has ended; never exceptionally. */
    public CompletableFuture<Snapshot> done() {
        return done;
    }

    /**
     * Stops the export, and the backend's rebuild job if one runs; nothing is written after it. An
     * export that writes already, which takes a moment, ends as it would have.
     */
    public void cancel() {
        Long job;
        synchronized (this) {
            if (snapshot.ended() || snapshot.state() == State.WRITING) {
                return;
            }
            snapshot = snapshot.with(State.CANCELLED);
            job = rebuildJob;
        }
        if (job != null) {
            calls.cancelJob(job);
        }
        done.complete(snapshot());
    }

    private void load() {
        DocumentExport.load(calls, request.docId(), request.termVectors()).whenComplete((data, error) -> {
            if (error != null) {
                fail(error);
            } else if (request.indexedTerms()) {
                rebuild(data);
            } else {
                finish(data, null);
            }
        });
    }

    private void rebuild(DocumentData data) {
        if (!advance(State.REBUILDING)) {
            return;
        }
        calls.startReconstruct(request.docId()).whenComplete((job, error) -> {
            if (error != null) {
                fail(error);
                return;
            }
            boolean cancelled;
            synchronized (this) {
                rebuildJob = job;
                cancelled = snapshot.state() == State.CANCELLED;
            }
            if (cancelled) {
                // Cancelled before the job had started: it is cancelled now.
                calls.cancelJob(job);
                return;
            }
            poll(data, job);
        });
    }

    // The handler of whenComplete takes every failure; nothing waits on the futures
    @SuppressWarnings("FutureReturnValueIgnored")
    private void poll(DocumentData data, long job) {
        calls.reconstruct(job).whenComplete((result, error) -> {
            if (error != null) {
                fail(error);
                return;
            }
            if (result.result().isPresent()) {
                finish(data, result.result().get().fields());
                return;
            }
            if (progress(result)) {
                CompletableFuture.runAsync(() -> poll(data, job), later);
            }
        });
    }

    /** Takes the rebuild's progress; returns whether the export still runs. */
    private synchronized boolean progress(GetReconstructResult result) {
        if (snapshot.state() != State.REBUILDING) {
            return false;
        }
        Long total = result.total().isPresent() ? result.total().getAsLong() : null;
        snapshot = new Snapshot(State.REBUILDING, result.scanned(), total, null, null, null, null);
        return true;
    }

    private void finish(DocumentData data, @Nullable List<ReconstructedField> rebuilt) {
        if (!advance(State.WRITING)) {
            return;
        }
        String text;
        try {
            text = DocumentExport.toJson(DocumentExport.assemble(
                    data, rebuilt, request.schema(), request.index(), request.indexDirectory()));
        } catch (RuntimeException e) {
            fail(e);
            return;
        }
        Snapshot ended;
        switch (request.target()) {
            case Target.Text _ -> ended = ended(State.DONE, text, null, null);
            case Target.File file -> {
                Optional<String> refused =
                        DocumentExport.writeFile(file.path(), text, file.overwrite(), file.indexDirs());
                ended = refused.isPresent()
                        ? ended(State.FAILED, null, null, refused.get())
                        : ended(State.DONE, null, file.path(), null);
            }
        }
        end(ended);
    }

    private Snapshot ended(State state, @Nullable String text, @Nullable Path path, @Nullable String error) {
        Snapshot last = snapshot();
        return new Snapshot(state, last.scanned(), last.total(), text, path, error, null);
    }

    private void fail(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        Snapshot last = snapshot();
        end(new Snapshot(
                State.FAILED,
                last.scanned(),
                last.total(),
                null,
                null,
                cause.getMessage() != null ? cause.getMessage() : cause.toString(),
                cause));
    }

    /** Moves on to {@code next}; returns false if the export was cancelled meanwhile. */
    private synchronized boolean advance(State next) {
        if (snapshot.ended()) {
            return false;
        }
        snapshot = snapshot.with(next);
        return true;
    }

    private void end(Snapshot ended) {
        synchronized (this) {
            if (snapshot.ended()) {
                return;
            }
            snapshot = ended;
        }
        done.complete(ended);
    }
}
