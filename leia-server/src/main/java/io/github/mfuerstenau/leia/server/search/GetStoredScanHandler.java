package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/**
 * {@code getStoredScan}: the hits of a job of {@link StartStoredScanHandler} from a given hit on,
 * then its outcome with the last hits.
 */
public final class GetStoredScanHandler implements MethodHandler<GetStoredScanParams, GetStoredScanResult> {

    private final Jobs jobs;

    @Inject
    GetStoredScanHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<GetStoredScanParams, GetStoredScanResult> method() {
        return Methods.GET_STORED_SCAN;
    }

    @Override
    public GetStoredScanResult handle(GetStoredScanParams params) throws RpcException {
        if (params.fromHit() < 0) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: fromHit must not be negative, not " + params.fromHit());
        }
        StoredScanHits hits = jobs.attachment(params.jobId(), StoredScanResult.class, StoredScanHits.class);
        // Polled first: once the job is done, every hit is in the list.
        Jobs.Poll<StoredScanResult> poll = jobs.poll(params.jobId(), StoredScanResult.class);
        StoredScanHits.Snapshot snapshot = hits.from(params.fromHit());
        return new GetStoredScanResult(
                poll.progress(), snapshot.matchedValues(), snapshot.hitCount(), snapshot.hits(), poll.outcome());
    }
}
