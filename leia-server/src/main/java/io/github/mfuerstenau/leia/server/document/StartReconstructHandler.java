package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.Reconstruct;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.GetIndexStatsHandler;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.points.PointMapper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;

/**
 * {@code startReconstruct}: checks the document and fields at once, counts the terms and points to
 * go through, then rebuilds the terms and points the document was indexed with in a job ({@link
 * GetReconstructHandler}, ADR 0029, ADR 0041).
 */
public final class StartReconstructHandler implements MethodHandler<StartReconstructParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartReconstructHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    /** What a poll of the job needs besides its progress. */
    record Count(OptionalLong total) {}

    @Override
    public RpcMethod<StartReconstructParams, JobStarted> method() {
        return Methods.START_RECONSTRUCT;
    }

    @Override
    public JobStarted handle(StartReconstructParams params) throws RpcException {
        ReadOnlyIndex index = currentIndex.readable();
        int docId = params.docId();
        GetDocumentHandler.checkDocId(index, docId);
        Optional<List<String>> fields = params.fields().map(List::copyOf);
        if (fields.isPresent()) {
            for (String name : fields.get()) {
                FieldInfo field = index.fieldInfo(name)
                        .orElseThrow(() ->
                                new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + name + "' in the index"));
                if (field.getIndexOptions() == IndexOptions.NONE && field.getPointDimensionCount() == 0) {
                    throw new RpcException(
                            ErrorCode.INVALID_PARAMS,
                            "Invalid params: the field '" + name + "' has no terms or points to rebuild");
                }
            }
        }
        OptionalLong total;
        try {
            total = index.reconstructCount(docId, fields);
        } catch (IOException e) {
            throw notReadable(docId, e);
        }
        long jobId = jobs.start(
                "reconstruct document " + docId
                        + fields.map(names -> ", fields " + names).orElse(""),
                ReconstructResult.class,
                new Count(total),
                progress -> reconstruct(index, docId, fields, progress));
        return new JobStarted(jobId);
    }

    private static ReconstructResult reconstruct(
            ReadOnlyIndex index, int docId, Optional<List<String>> fields, ScanProgress progress) throws RpcException {
        long start = System.nanoTime();
        try {
            List<ReconstructedField> result = index.reconstruct(docId, fields, progress).stream()
                    .map(field -> field(field, index.fieldInfo(field.field()).orElseThrow()))
                    .toList();
            return new ReconstructResult(
                    result, Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (IOException e) {
            throw notReadable(docId, e);
        }
    }

    private static ReconstructedField field(Reconstruct.Field field, FieldInfo info) {
        return new ReconstructedField(
                field.field(),
                GetIndexStatsHandler.indexOptions(field.indexOptions()),
                switch (field.source()) {
                    case TERM_VECTOR -> ReconstructSource.TERM_VECTOR;
                    case POSTINGS -> ReconstructSource.POSTINGS;
                },
                field.terms().stream().map(GetTermVectorHandler::term).toList(),
                field.points().stream()
                        .map(point -> PointMapper.pointValue(point, info.getPointNumBytes()))
                        .toList());
    }

    private static RpcException notReadable(int docId, IOException e) {
        return new RpcException(
                ErrorCode.INDEX_NOT_READABLE,
                "Cannot rebuild the terms and points of document " + docId + ": " + e.getMessage(),
                e);
    }
}
