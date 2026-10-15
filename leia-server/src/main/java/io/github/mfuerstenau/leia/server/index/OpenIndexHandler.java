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
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.SkippedIndex;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.IndexVersion;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import io.github.mfuerstenau.leia.server.runtime.SegmentsHeader;
import jakarta.inject.Inject;
import java.io.IOException;
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
    private final Runtimes runtimes;

    @Inject
    OpenIndexHandler(CurrentIndex currentIndex, Runtimes runtimes) {
        this.currentIndex = currentIndex;
        this.runtimes = runtimes;
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
        // Routed by the version of the index (ADR 0044 §3); a path with indexes below it has none.
        Optional<IndexVersion> version = SegmentsHeader.read(path);
        if (version.isPresent() && version.get().major() < Version.MIN_SUPPORTED_MAJOR) {
            Path normalized = path.toAbsolutePath().normalize();
            if (mode == OpenMode.READER) {
                return openWithRuntime(normalized, version.get(), directoryImpl);
            }
            if (version.get().major() < 4) {
                throw new RpcException(
                        ErrorCode.UNSUPPORTED_INDEX_FORMAT,
                        "An index written by Lucene " + version.get().writtenBy() + " opens only with a reader: "
                                + normalized);
            }
            // Without a reader, Lucene 10 lists their commits and checks some, such as a Lucene 8 index (ADR 0022).
        }
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
                        skipped(readOnly),
                        Feature.all(),
                        Version.LATEST.toString());
            case DirectoryOnlyIndex directoryOnly ->
                new OpenIndexResult(
                        directoryOnly.path().toString(),
                        OpenMode.DIRECTORY_ONLY,
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        directoryOnly.directories().stream()
                                .map(dir -> new IndexEntry(dir.toString(), Optional.empty()))
                                .toList(),
                        List.of(),
                        Feature.withoutReader(),
                        Version.LATEST.toString());
        };
    }

    /**
     * Opens an index that Lucene 10 can't read through a runtime (ADR 0044). Only with a reader: the
     * commits screen and the check need Lucene 10.
     */
    private OpenIndexResult openWithRuntime(Path path, IndexVersion version, DirectoryImpl directoryImpl)
            throws RpcException {
        LuceneRuntime runtime = runtimes.forMajor(version.major())
                .orElseThrow(() -> new RpcException(
                        ErrorCode.MISSING_RUNTIME,
                        "The index was written by Lucene " + version.writtenBy() + "; reading it needs the runtime "
                                + Runtimes.jarFor(version.major()).orElse("for Lucene " + version.major())
                                + ", which isn't loaded: " + path));
        RuntimeIndex index;
        try {
            index = runtime.open(path, DirectoryKind.valueOf(directoryImpl.name()));
        } catch (IOException | RuntimeException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the index written by Lucene " + version.writtenBy() + " with Lucene "
                            + runtime.luceneVersion() + ": " + e,
                    e);
        }
        OpenRuntime open = new OpenRuntime(runtime, index);
        currentIndex.replace(open);
        LOG.info(() -> "Opened " + path + " (" + RuntimeResults.describe(open) + ", " + directoryImpl + ")");
        return RuntimeResults.opened(open);
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
