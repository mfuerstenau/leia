package io.github.mfuerstenau.leia.server.filter;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * The filter that the server keeps for the open index (ADR 0031): at most one, since the TUI browses
 * through one at a time and each takes maxDoc / 8 bytes. The postings of a term can be read through
 * it (ADR 0032). Only the request loop touches it, so it
 * needs no locking.
 */
@Singleton
public final class Filters {

    private record Kept(long id, DocFilter filter) {}

    private Optional<Kept> kept = Optional.empty();

    @Inject
    public Filters() {
        // For Dagger.
    }

    /** Keeps {@code filter} under {@code id}, in place of the filter kept before. */
    public void put(long id, DocFilter filter) {
        kept = Optional.of(new Kept(id, filter));
    }

    /**
     * The filter kept under {@code id}.
     *
     * @throws RpcException {@code FILTER_NOT_FOUND} if no filter is kept under it
     */
    public DocFilter get(long id) throws RpcException {
        return kept.filter(k -> k.id() == id)
                .map(Kept::filter)
                .orElseThrow(() -> new RpcException(
                        ErrorCode.FILTER_NOT_FOUND,
                        "No filter " + id
                                + ": unknown, released, replaced, or of an index that was closed or reopened"));
    }

    /**
     * Forgets the filter kept under {@code id}.
     *
     * @return whether one was kept under it
     */
    boolean release(long id) {
        boolean released = kept.filter(k -> k.id() == id).isPresent();
        if (released) {
            kept = Optional.empty();
        }
        return released;
    }

    /** Forgets the filter, whose document ids belong to an index that is being closed. */
    public void clear() {
        kept = Optional.empty();
    }
}
