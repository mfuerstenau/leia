package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.core.IndexSummary;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.logging.Logger;
import org.apache.lucene.util.Version;

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
        ReadOnlyIndex index =
                currentIndex.get().orElseThrow(() -> new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open"));
        Optional<ReadOnlyIndex> reopened;
        try {
            reopened = index.reopen();
        } catch (IndexOpenException e) {
            throw new RpcException(OpenIndexHandler.errorCode(e.reason()), e.getMessage(), e);
        }
        if (reopened.isEmpty()) {
            LOG.fine(() -> "Unchanged " + index.summary().path() + " ("
                    + index.summary().segmentsFile() + ")");
            return toResult(false, index.summary());
        }
        currentIndex.replace(reopened.get());
        IndexSummary summary = reopened.get().summary();
        LOG.info(() ->
                "Reopened " + summary.path() + " (" + summary.segmentsFile() + ", " + summary.maxDoc() + " docs)");
        return toResult(true, summary);
    }

    private static ReopenIndexResult toResult(boolean changed, IndexSummary summary) {
        return new ReopenIndexResult(
                changed,
                summary.path().toString(),
                summary.segmentsFile(),
                summary.numDocs(),
                summary.maxDoc(),
                summary.createdVersionMajor(),
                summary.minSegmentLuceneVersion().map(Version::toString));
    }
}
