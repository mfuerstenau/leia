package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.OpenedIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The index that is open in this server, if any: a path opened with or without a reader (ADR 0021).
 * There is at most one. Calls are handled one at
 * a time, on the backend thread of {@code LeiaBackend}, so this needs no locking. Jobs read the open index from their own
 * threads, so they are stopped before it is closed (ADR 0005). The kept filter holds document ids of
 * the open index, so it is forgotten with it (ADR 0031).
 *
 * <p>An index that Lucene 10 can't read, such as one written by Lucene 2.x or 3.x, is open through a
 * runtime instead, as an {@link OpenRuntime} (ADR 0044). Then {@link #opened()} and {@link
 * #readable()} refuse with {@code NOT_SUPPORTED}, so every handler without a branch for it refuses
 * too; those with one ask {@link #runtime()} first.
 */
@Singleton
public final class CurrentIndex {

    private static final Logger LOG = Logger.getLogger(CurrentIndex.class.getName());

    private final Jobs jobs;
    private final Filters filters;
    private Optional<OpenedIndex> index = Optional.empty();
    private Optional<OpenRuntime> runtime = Optional.empty();

    @Inject
    public CurrentIndex(Jobs jobs, Filters filters) {
        this.jobs = jobs;
        this.filters = filters;
    }

    /** The open index if Lucene 10 reads it; empty if none is open or a runtime reads it (ADR 0044). */
    public Optional<OpenedIndex> get() {
        return index;
    }

    /**
     * The open index, with or without a reader.
     *
     * @throws RpcException {@code NO_INDEX_OPEN} if no index is open
     */
    public OpenedIndex opened() throws RpcException {
        if (runtime.isPresent()) {
            throw runtime.get().notSupported();
        }
        return index.orElseThrow(() -> new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open"));
    }

    /** The open index if a runtime reads it (ADR 0044); empty if none is open or Lucene 10 reads it. */
    public Optional<OpenRuntime> runtime() {
        return runtime;
    }

    /**
     * The open index, which has a reader.
     *
     * @throws RpcException {@code NO_INDEX_OPEN} if no index is open, {@code NO_READER} if it was
     *     opened without a reader
     */
    public ReadOnlyIndex readable() throws RpcException {
        return switch (opened()) {
            case ReadOnlyIndex readOnly -> readOnly;
            default ->
                throw new RpcException(ErrorCode.NO_READER, "Opened without a reader: only the commits can be read");
        };
    }

    /**
     * Makes {@code newIndex} the open index, stops the jobs, which all read the previous one, forgets
     * the filter, and closes it.
     */
    public void replace(OpenedIndex newIndex) {
        Optional<Closeable> previous = take();
        index = Optional.of(newIndex);
        jobs.cancelAll();
        filters.clear();
        previous.ifPresent(CurrentIndex::closeQuietly);
    }

    /** As {@link #replace(OpenedIndex)}, for an index that a runtime reads (ADR 0044). */
    public void replace(OpenRuntime newIndex) {
        Optional<Closeable> previous = take();
        runtime = Optional.of(newIndex);
        jobs.cancelAll();
        filters.clear();
        previous.ifPresent(CurrentIndex::closeQuietly);
    }

    /** Forgets the open index of either kind, and returns it. */
    private Optional<Closeable> take() {
        Optional<Closeable> previous = index.<Closeable>map(opened -> opened).or(() -> runtime.map(OpenRuntime::index));
        index = Optional.empty();
        runtime = Optional.empty();
        return previous;
    }

    private static Path path(Closeable closeable) {
        return switch (closeable) {
            case OpenedIndex opened -> opened.path();
            case RuntimeIndex old -> old.path();
            default -> throw new IllegalStateException("Not an index: " + closeable);
        };
    }

    /** Stops the jobs, forgets the filter, and closes the open index, if any, and returns its path. */
    public Optional<Path> close() {
        Optional<Closeable> previous = take();
        jobs.cancelAll();
        filters.clear();
        previous.ifPresent(closed -> {
            closeQuietly(closed);
            LOG.info(() -> "Closed " + path(closed));
        });
        return previous.map(CurrentIndex::path);
    }

    /**
     * Closing a read-only index releases memory maps and file handles only; nothing is written, so a
     * failure loses no data and is just logged.
     */
    private static void closeQuietly(Closeable index) {
        try {
            index.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot close " + path(index));
        }
    }
}
