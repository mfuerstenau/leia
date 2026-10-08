package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.core.IndexSummary;
import io.github.mfuerstenau.leia.core.OpenedIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;
import org.apache.lucene.util.Version;

/**
 * {@code openIndex}: opens the indexes of a path read-only, with or without a reader (ADR 0021), and
 * makes them the {@link CurrentIndex}.
 */
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

        DirectoryImpl directoryImpl = params.directoryImpl()
                .map(Enum::name)
                .map(DirectoryImpl::valueOf)
                .orElse(DirectoryImpl.FS);
        OpenMode mode = params.mode().orElse(OpenMode.READER);
        OpenedIndex index;
        try {
            index = switch (mode) {
                case READER -> ReadOnlyIndex.open(path, directoryImpl);
                case DIRECTORY_ONLY -> DirectoryOnlyIndex.open(path, directoryImpl);
            };
        } catch (IndexOpenException e) {
            throw new RpcException(errorCode(e.reason()), e.getMessage(), e);
        }
        currentIndex.replace(index);
        LOG.info(() -> "Opened " + index.path() + " (" + describe(index) + ", " + directoryImpl + ")");
        return switch (index) {
            case ReadOnlyIndex readOnly ->
                new OpenIndexResult(
                        readOnly.path().toString(),
                        OpenMode.READER,
                        OptionalInt.of(readOnly.numDocs()),
                        OptionalInt.of(readOnly.maxDoc()),
                        entries(readOnly),
                        skipped(readOnly));
            case DirectoryOnlyIndex directoryOnly ->
                new OpenIndexResult(
                        directoryOnly.path().toString(),
                        OpenMode.DIRECTORY_ONLY,
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        directoryOnly.directories().stream()
                                .map(dir -> new IndexEntry(dir.toString(), Optional.empty()))
                                .toList(),
                        List.of());
        };
    }

    /** What the log says about an opened index. */
    static String describe(OpenedIndex index) {
        return switch (index) {
            case ReadOnlyIndex readOnly
            when readOnly.indexes().size() == 1 -> {
                IndexSummary summary = readOnly.indexes().getFirst();
                yield summary.segmentsFile() + ", " + summary.maxDoc() + " docs, created by Lucene "
                        + summary.createdVersionMajor();
            }
            case ReadOnlyIndex readOnly ->
                readOnly.indexes().size() + " indexes, " + readOnly.maxDoc() + " docs, "
                        + readOnly.skipped().size() + " skipped";
            case DirectoryOnlyIndex directoryOnly -> directoryOnly.directories().size() + " indexes without a reader";
        };
    }

    /** The index directories of {@code index} with what their readers opened. */
    static List<IndexEntry> entries(ReadOnlyIndex index) {
        return index.indexes().stream()
                .map(summary -> new IndexEntry(
                        summary.path().toString(),
                        Optional.of(new ReaderInfo(
                                summary.docBase(),
                                summary.segmentsFile(),
                                summary.numDocs(),
                                summary.maxDoc(),
                                summary.createdVersionMajor(),
                                summary.minSegmentLuceneVersion().map(Version::toString)))))
                .toList();
    }

    static List<SkippedIndex> skipped(ReadOnlyIndex index) {
        return index.skipped().stream()
                .map(skipped -> new SkippedIndex(skipped.path().toString(), skipped.message()))
                .toList();
    }

    static ErrorCode errorCode(IndexOpenException.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> ErrorCode.INDEX_NOT_FOUND;
            case NOT_AN_INDEX -> ErrorCode.NOT_AN_INDEX;
            case UNSUPPORTED_FORMAT -> ErrorCode.UNSUPPORTED_INDEX_FORMAT;
            case CORRUPT -> ErrorCode.CORRUPT_INDEX;
            case NOT_READABLE -> ErrorCode.INDEX_NOT_READABLE;
            case MISSING_CODEC -> ErrorCode.MISSING_CODEC;
        };
    }
}
