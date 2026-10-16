package io.github.mfuerstenau.leia.server.commits;

import io.github.mfuerstenau.leia.core.CommitDetails;
import io.github.mfuerstenau.leia.core.MissingCodecException;
import io.github.mfuerstenau.leia.core.OpenedIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.commits.CodecFormats;
import io.github.mfuerstenau.leia.protocol.commits.CommitFile;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.EOFException;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.util.Version;

/**
 * {@code getCommit}: one commit of an index directory of the {@link CurrentIndex} whole (ADR 0009),
 * read by the Lucene that reads the directory (ADR 0045).
 */
public final class GetCommitHandler implements MethodHandler<GetCommitParams, GetCommitResult> {

    private static final Logger LOG = Logger.getLogger(GetCommitHandler.class.getName());

    private final CurrentIndex currentIndex;
    private final IndexDirectories directories;

    @Inject
    GetCommitHandler(CurrentIndex currentIndex, IndexDirectories directories) {
        this.currentIndex = currentIndex;
        this.directories = directories;
    }

    @Override
    public RpcMethod<GetCommitParams, GetCommitResult> method() {
        return Methods.GET_COMMIT;
    }

    @Override
    public GetCommitResult handle(GetCommitParams params) throws RpcException {
        IndexDirectories.IndexDirectory dir = directories.opened(params.index());
        long generation = params.generation();
        switch (dir.route()) {
            case IndexDirectories.Route.Runtime(LuceneRuntime runtime) -> {
                return RuntimeResults.commit(runtime, dir, generation);
            }
            case IndexDirectories.Route.Missing missing -> throw missing.error(dir.path());
            case IndexDirectories.Route.Core() -> {}
        }
        OpenedIndex opened = currentIndex.opened();
        int index = dir.position().orElseThrow();
        CommitDetails details;
        try {
            details = opened.commit(index, generation)
                    .orElseThrow(() -> new RpcException(
                            ErrorCode.COMMIT_NOT_FOUND, "No commit of generation " + generation + " in the index"));
        } catch (MissingCodecException e) {
            throw new RpcException(
                    e.isLucenes() ? ErrorCode.UNSUPPORTED_INDEX_FORMAT : ErrorCode.MISSING_CODEC,
                    "Cannot read the commit of generation " + generation + ": " + e.getMessage(),
                    e);
        } catch (IndexFormatTooOldException | IndexFormatTooNewException e) {
            throw new RpcException(
                    ErrorCode.UNSUPPORTED_INDEX_FORMAT,
                    "Unsupported format of the commit of generation " + generation + ": " + e.getMessage(),
                    e);
        } catch (CorruptIndexException | EOFException e) {
            throw new RpcException(
                    ErrorCode.CORRUPT_INDEX, "Corrupt commit of generation " + generation + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the commit of generation " + generation + ": " + e.getMessage(),
                    e);
        }
        for (CommitDetails.Segment segment : details.segments()) {
            segment.fieldInfosError()
                    .ifPresent(error -> LOG.log(
                            Level.WARNING,
                            error,
                            () -> "Cannot read the field infos of segment " + segment.name() + " in commit "
                                    + generation + ": " + error.getMessage()));
        }
        return new GetCommitResult(
                GetCommitsHandler.commit(details.commit()),
                details.segments().stream().map(GetCommitHandler::segment).toList(),
                details.files().stream()
                        .map(file -> new CommitFile(file.name(), file.segment(), file.size()))
                        .toList());
    }

    private static Segment segment(CommitDetails.Segment segment) {
        CommitDetails.CodecFormats formats = segment.formats();
        return new Segment(
                segment.name(),
                Optional.of(segment.id()),
                segment.maxDoc(),
                segment.docBase(),
                segment.delCount(),
                segment.softDelCount(),
                segment.delGen(),
                segment.fieldInfosGen(),
                segment.docValuesGen(),
                segment.version().toString(),
                segment.minVersion().map(Version::toString),
                Optional.of(segment.codec()),
                Optional.of(new CodecFormats(
                        formats.postings(),
                        formats.docValues(),
                        formats.storedFields(),
                        formats.termVectors(),
                        formats.norms(),
                        formats.points(),
                        formats.knnVectors(),
                        formats.fieldInfos(),
                        formats.segmentInfo(),
                        formats.liveDocs(),
                        formats.compound())),
                segment.fieldFormats().stream()
                        .map(field -> new FieldFormats(
                                field.field(), field.postings(), field.docValues(), field.knnVectors()))
                        .toList(),
                segment.fieldInfosMessage(),
                segment.compound(),
                segment.hasBlocks(),
                segment.indexSort(),
                segment.sizeInBytes(),
                segment.diagnostics(),
                segment.attributes());
    }
}
