package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.OpenedIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The index that is open in this server, if any: a path opened with or without a reader (ADR 0021).
 * There is at most one. Requests are handled one at
 * a time ({@code RpcServer}), so this needs no locking. Jobs read the open index from their own
 * threads, so they are stopped before it is closed (ADR 0005).
 */
@Singleton
public final class CurrentIndex {

    private static final Logger LOG = Logger.getLogger(CurrentIndex.class.getName());

    private final Jobs jobs;
    private Optional<OpenedIndex> index = Optional.empty();

    @Inject
    public CurrentIndex(Jobs jobs) {
        this.jobs = jobs;
    }

    public Optional<OpenedIndex> get() {
        return index;
    }

    /**
     * The open index, with or without a reader.
     *
     * @throws RpcException {@code NO_INDEX_OPEN} if no index is open
     */
    public OpenedIndex opened() throws RpcException {
        return index.orElseThrow(() -> new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open"));
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
     * Makes {@code newIndex} the open index, stops the jobs, which all read the previous one, and
     * closes it.
     */
    public void replace(OpenedIndex newIndex) {
        Optional<OpenedIndex> previous = index;
        index = Optional.of(newIndex);
        jobs.cancelAll();
        previous.ifPresent(CurrentIndex::closeQuietly);
    }

    /** Stops the jobs and closes the open index, if any, and returns it. */
    public Optional<OpenedIndex> close() {
        Optional<OpenedIndex> previous = index;
        index = Optional.empty();
        jobs.cancelAll();
        previous.ifPresent(closed -> {
            closeQuietly(closed);
            LOG.info(() -> "Closed " + closed.path());
        });
        return previous;
    }

    /**
     * Closing a read-only index releases memory maps and file handles only; nothing is written, so a
     * failure loses no data and is just logged.
     */
    private static void closeQuietly(OpenedIndex index) {
        try {
            index.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot close " + index.path());
        }
    }
}
