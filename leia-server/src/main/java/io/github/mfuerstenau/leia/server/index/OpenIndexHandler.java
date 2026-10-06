package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.core.IndexSummary;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.apache.lucene.util.Version;

/** {@code openIndex}: opens an index read-only and makes it the {@link CurrentIndex}. */
public final class OpenIndexHandler implements MethodHandler<OpenIndexParams, OpenIndexResult> {

    private static final Logger LOG = Logger.getLogger(OpenIndexHandler.class.getName());

    private final CurrentIndex currentIndex;

    @Inject
    OpenIndexHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<OpenIndexParams, OpenIndexResult> method() {
        return Methods.OPEN_INDEX;
    }

    @Override
    public OpenIndexResult handle(OpenIndexParams params) throws RpcException {
        Path path;
        try {
            path = Path.of(params.path());
        } catch (InvalidPathException e) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: not a path: " + e.getMessage(), e);
        }

        ReadOnlyIndex index;
        try {
            index = ReadOnlyIndex.open(path);
        } catch (IndexOpenException e) {
            throw new RpcException(errorCode(e.reason()), e.getMessage(), e);
        }
        currentIndex.replace(index);
        IndexSummary summary = index.summary();
        LOG.info(() -> "Opened " + summary.path() + " (" + summary.segmentsFile() + ", " + summary.maxDoc()
                + " docs, created by Lucene " + summary.createdVersionMajor() + ")");
        return toResult(summary);
    }

    static ErrorCode errorCode(IndexOpenException.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> ErrorCode.INDEX_NOT_FOUND;
            case NOT_AN_INDEX -> ErrorCode.NOT_AN_INDEX;
            case UNSUPPORTED_FORMAT -> ErrorCode.UNSUPPORTED_INDEX_FORMAT;
            case CORRUPT -> ErrorCode.CORRUPT_INDEX;
            case NOT_READABLE -> ErrorCode.INDEX_NOT_READABLE;
        };
    }

    private static OpenIndexResult toResult(IndexSummary summary) {
        return new OpenIndexResult(
                summary.path().toString(),
                summary.segmentsFile(),
                summary.numDocs(),
                summary.maxDoc(),
                summary.createdVersionMajor(),
                summary.minSegmentLuceneVersion().map(Version::toString));
    }
}
