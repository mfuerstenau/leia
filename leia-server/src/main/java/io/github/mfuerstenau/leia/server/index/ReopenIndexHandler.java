package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;

/**
 * {@code reopenIndex}: opens the latest commit of the {@link CurrentIndex} again and makes it the
 * current index, if it changed (ADR 0019), also on an index that a runtime reads (ADR 0060).
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
        Optional<OpenRuntime> runtime = currentIndex.runtime();
        if (runtime.isPresent()) {
            return reopenRuntime(runtime.get());
        }
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

    /**
     * Reopens an index that a runtime reads. If that fails, the index stays open as it was, with its
     * jobs and filter.
     */
    private ReopenIndexResult reopenRuntime(OpenRuntime open) throws RpcException {
        RuntimeIndex index = open.index();
        for (Path dir : index.directories()) {
            if (!Files.isDirectory(dir)) {
                throw new RpcException(ErrorCode.INDEX_NOT_FOUND, "No such directory: " + dir);
            }
        }
        Optional<RuntimeIndex> reopened;
        try {
            reopened = index.reopen();
        } catch (IndexReadException e) {
            throw RuntimeResults.readFailure(e);
        } catch (IOException | RuntimeException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot reopen " + open.path() + " with Lucene "
                            + open.runtime().luceneVersion() + ": " + e.getMessage(),
                    e);
        }
        if (reopened.isEmpty()) {
            LOG.fine(() -> "Unchanged " + open.path() + " (" + RuntimeResults.describe(open) + ")");
            return RuntimeResults.reopened(false, open);
        }
        OpenRuntime newOpen = new OpenRuntime(open.path(), open.runtime(), reopened.get(), open.skipped());
        currentIndex.replace(newOpen);
        LOG.info(() -> "Reopened " + open.path() + " (" + RuntimeResults.describe(newOpen) + ")");
        return RuntimeResults.reopened(true, newOpen);
    }

    private static ReopenIndexResult toResult(boolean changed, ReadOnlyIndex index) {
        return new ReopenIndexResult(
                changed,
                index.path().toString(),
                OpenMode.READER,
                OptionalInt.of(index.numDocs()),
                OptionalInt.of(index.maxDoc()),
                OpenIndexHandler.entries(index),
                OpenIndexHandler.skipped(index),
                Feature.all());
    }
}
