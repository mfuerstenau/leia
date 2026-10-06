package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The index that is open in this server, if any. There is at most one. Requests are handled one at
 * a time ({@code RpcServer}), so this needs no locking. Jobs read the open index from their own
 * threads, so they are stopped before it is closed (ADR 0005).
 */
@Singleton
public final class CurrentIndex {

    private static final Logger LOG = Logger.getLogger(CurrentIndex.class.getName());

    private final Jobs jobs;
    private Optional<ReadOnlyIndex> index = Optional.empty();

    @Inject
    public CurrentIndex(Jobs jobs) {
        this.jobs = jobs;
    }

    public Optional<ReadOnlyIndex> get() {
        return index;
    }

    /**
     * Makes {@code newIndex} the open index, stops the jobs, which all read the previous one, and
     * closes it.
     */
    public void replace(ReadOnlyIndex newIndex) {
        Optional<ReadOnlyIndex> previous = index;
        index = Optional.of(newIndex);
        jobs.cancelAll();
        previous.ifPresent(CurrentIndex::closeQuietly);
    }

    /** Stops the jobs and closes the open index, if any, and returns it. */
    public Optional<ReadOnlyIndex> close() {
        Optional<ReadOnlyIndex> previous = index;
        index = Optional.empty();
        jobs.cancelAll();
        previous.ifPresent(closed -> {
            closeQuietly(closed);
            LOG.info(() -> "Closed " + closed.summary().path());
        });
        return previous;
    }

    /**
     * Closing a read-only index releases memory maps and file handles only; nothing is written, so a
     * failure loses no data and is just logged.
     */
    private static void closeQuietly(ReadOnlyIndex index) {
        try {
            index.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot close " + index.summary().path());
        }
    }
}
