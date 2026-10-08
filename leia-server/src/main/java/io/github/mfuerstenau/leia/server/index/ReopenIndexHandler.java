package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;

/**
 * {@code reopenIndex}: opens the latest commit of the {@link CurrentIndex} again and makes it the
 * current index, if it changed (ADR 0019).
 */
public final class ReopenIndexHandler implements MethodHandler<ReopenIndexParams, ReopenIndexResult> {

    private static final Logger LOG = Logger.getLogger(ReopenIndexHandler.class.getName());

    private final CurrentIndex currentIndex;

    @Inject
    ReopenIndexHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<ReopenIndexParams, ReopenIndexResult> method() {
        return Methods.REOPEN_INDEX;
    }

    @Override
    public ReopenIndexResult handle(ReopenIndexParams params) throws RpcException {
        ReadOnlyIndex index = currentIndex.readable();
        Optional<ReadOnlyIndex> reopened;
        try {
            reopened = index.reopen();
        } catch (IndexOpenException e) {
            throw new RpcException(OpenIndexHandler.errorCode(e.reason()), e.getMessage(), e);
        }
        if (reopened.isEmpty()) {
            LOG.fine(() -> "Unchanged " + index.path() + " (" + OpenIndexHandler.describe(index) + ")");
            return toResult(false, index);
        }
        currentIndex.replace(reopened.get());
        LOG.info(() -> "Reopened " + index.path() + " (" + OpenIndexHandler.describe(reopened.get()) + ")");
        return toResult(true, reopened.get());
    }

    private static ReopenIndexResult toResult(boolean changed, ReadOnlyIndex index) {
        return new ReopenIndexResult(
                changed,
                index.path().toString(),
                OpenMode.READER,
                OptionalInt.of(index.numDocs()),
                OptionalInt.of(index.maxDoc()),
                OpenIndexHandler.entries(index),
                OpenIndexHandler.skipped(index));
    }
}
