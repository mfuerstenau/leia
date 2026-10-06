package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import org.apache.lucene.util.BytesRef;

/**
 * {@code startFieldTermScan}: checks the params at once, then counts the terms of a field of the
 * {@link CurrentIndex} and finds its top terms in a job ({@link GetFieldTermScanHandler}).
 */
public final class StartFieldTermScanHandler implements MethodHandler<StartFieldTermScanParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartFieldTermScanHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartFieldTermScanParams, JobStarted> method() {
        return Methods.START_FIELD_TERM_SCAN;
    }

    @Override
    public JobStarted handle(StartFieldTermScanParams params) throws RpcException {
        int topN = params.topN();
        if (topN < 0 || topN > StartFieldTermScanParams.MAX_TOP_N) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: topN must be between 0 and " + StartFieldTermScanParams.MAX_TOP_N + ", not "
                            + topN);
        }
        ReadOnlyIndex index =
                currentIndex.get().orElseThrow(() -> new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open"));
        String field = params.field();
        if (!index.hasField(field)) {
            throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index");
        }
        long jobId = jobs.start(
                "term scan of field " + field + ", top " + topN,
                FieldTerms.class,
                progress -> scan(index, field, topN, progress));
        return new JobStarted(jobId);
    }

    private static FieldTerms scan(ReadOnlyIndex index, String field, int topN, ScanProgress progress)
            throws RpcException {
        try {
            io.github.mfuerstenau.leia.core.FieldTerms terms = index.fieldTerms(field, topN, progress);
            return new FieldTerms(
                    terms.field(),
                    terms.uniqueTerms(),
                    terms.topTerms().stream()
                            .map(StartFieldTermScanHandler::topTerm)
                            .toList());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the terms of field '" + field + "': " + e.getMessage(),
                    e);
        }
    }

    static TopTerm topTerm(io.github.mfuerstenau.leia.core.FieldTerms.TopTerm top) {
        BytesRef bytes = top.term();
        Optional<String> text = utf8(bytes);
        return new TopTerm(
                text.orElseGet(() -> HexFormat.of().formatHex(bytes.bytes, bytes.offset, bytes.offset + bytes.length)),
                text.isEmpty(),
                top.docFreq(),
                top.totalTermFreq());
    }

    /** The bytes as text, if they are valid UTF-8. */
    private static Optional<String> utf8(BytesRef bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes.bytes, bytes.offset, bytes.length))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}
