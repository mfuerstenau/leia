package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The index that is open in this server, if any. There is at most one. Requests are handled one at
 * a time ({@code RpcServer}), so this needs no locking.
 */
@Singleton
public final class CurrentIndex {

    private static final Logger LOG = Logger.getLogger(CurrentIndex.class.getName());

    private Optional<ReadOnlyIndex> index = Optional.empty();

    @Inject
    CurrentIndex() {}

    public Optional<ReadOnlyIndex> get() {
        return index;
    }

    /** Makes {@code newIndex} the open index and closes the one that was open before. */
    public void replace(ReadOnlyIndex newIndex) {
        Optional<ReadOnlyIndex> previous = index;
        index = Optional.of(newIndex);
        previous.ifPresent(CurrentIndex::closeQuietly);
    }

    /** Closes the open index, if any, and returns it. */
    public Optional<ReadOnlyIndex> close() {
        Optional<ReadOnlyIndex> previous = index;
        index = Optional.empty();
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
