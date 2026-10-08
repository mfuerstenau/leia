package io.github.mfuerstenau.leia.server.commits;

import io.github.mfuerstenau.leia.core.CommitDetails;
import io.github.mfuerstenau.leia.core.MissingCodecException;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.commits.CodecFormats;
import io.github.mfuerstenau.leia.protocol.commits.CommitFile;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import jakarta.inject.Inject;
import java.io.EOFException;
import java.io.IOException;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.util.Version;

/** {@code getCommit}: one commit of the {@link CurrentIndex}'s directory whole (ADR 0009). */
public final class GetCommitHandler implements MethodHandler<GetCommitParams, GetCommitResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetCommitHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetCommitParams, GetCommitResult> method() {
        return Methods.GET_COMMIT;
    }

    @Override
    public GetCommitResult handle(GetCommitParams params) throws RpcException {
        ReadOnlyIndex index = GetDocumentHandler.openIndex(currentIndex);
        long generation = params.generation();
        CommitDetails details;
        try {
            details = index.commit(generation)
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
                segment.id(),
                segment.maxDoc(),
                segment.docBase(),
                segment.delCount(),
                segment.softDelCount(),
                segment.delGen(),
                segment.fieldInfosGen(),
                segment.docValuesGen(),
                segment.version().toString(),
                segment.minVersion().map(Version::toString),
                segment.codec(),
                new CodecFormats(
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
                        formats.compound()),
                segment.compound(),
                segment.hasBlocks(),
                segment.indexSort(),
                segment.sizeInBytes(),
                segment.diagnostics(),
                segment.attributes());
    }
}
