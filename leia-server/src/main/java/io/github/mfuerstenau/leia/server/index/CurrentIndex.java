package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.OpenDirectories;
import io.github.mfuerstenau.leia.server.runtime.OpenIndex;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The index that is open in this server, if any: a path opened with or without a reader (ADR 0021),
 * through a runtime (ADR 0071). There is at most one. Calls are handled one at a time, on the
 * backend thread of {@code LeiaBackend}, so this needs no locking. Jobs read the open index from
 * their own threads, so they are stopped before it is closed (ADR 0005). The kept filter holds
 * document ids of the open index, so it is forgotten with it (ADR 0031).
 */
@Singleton
public final class CurrentIndex {

    private static final Logger LOG = Logger.getLogger(CurrentIndex.class.getName());

    private final Jobs jobs;
    private final Filters filters;
    private Optional<OpenIndex> index = Optional.empty();

    @Inject
    public CurrentIndex(Jobs jobs, Filters filters) {
        this.jobs = jobs;
        this.filters = filters;
    }

    /** The open index; empty if none is open. */
    public Optional<OpenIndex> get() {
        return index;
    }

    /**
     * The open index, with or without a reader.
     *
     * @throws RpcException {@code NO_INDEX_OPEN} if no index is open
     */
    public OpenIndex opened() throws RpcException {
        return index.orElseThrow(() -> new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open"));
    }

    /**
     * The open index, which has a reader.
     *
     * @throws RpcException {@code NO_INDEX_OPEN} if no index is open, {@code NO_READER} if it was
     *     opened without a reader
     */
    public OpenRuntime runtime() throws RpcException {
        return switch (opened()) {
            case OpenRuntime open -> open;
            case OpenDirectories _ ->
                throw new RpcException(ErrorCode.NO_READER, "Opened without a reader: only the commits can be read");
        };
    }

    /**
     * Makes {@code newIndex} the open index, stops the jobs, which all read the previous one, forgets
     * the filter, and closes it.
     */
    public void replace(OpenIndex newIndex) {
        Optional<OpenIndex> previous = index;
        index = Optional.of(newIndex);
        jobs.cancelAll();
        filters.clear();
        previous.ifPresent(CurrentIndex::closeQuietly);
    }

    /** Stops the jobs, forgets the filter, and closes the open index, if any, and returns its path. */
    public Optional<Path> close() {
        Optional<OpenIndex> previous = index;
        index = Optional.empty();
        jobs.cancelAll();
        filters.clear();
        previous.ifPresent(closed -> {
            closeQuietly(closed);
            LOG.info(() -> "Closed " + closed.path());
        });
        return previous.map(OpenIndex::path);
    }

    /**
     * Closing a read-only index releases memory maps and file handles only; nothing is written, so a
     * failure loses no data and is just logged.
     */
    private static void closeQuietly(OpenIndex index) {
        try {
            index.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot close " + index.path());
        }
    }
}
