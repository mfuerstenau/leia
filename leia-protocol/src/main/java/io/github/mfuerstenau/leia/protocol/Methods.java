package io.github.mfuerstenau.leia.protocol;

import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexResult;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.job.CancelJobResult;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.version.VersionParams;
import io.github.mfuerstenau.leia.protocol.version.VersionResult;
import java.util.List;
import java.util.Optional;

/** The registry of all JSON-RPC methods. Each one needs fixtures in {@code protocol/fixtures/}. */
public final class Methods {

    public static final RpcMethod<VersionParams, VersionResult> VERSION =
            new RpcMethod<>("version", VersionParams.class, VersionResult.class);

    /**
     * Opens the latest commit of an index, read-only, and makes it the open index. An index that was
     * open before is closed, and its jobs stopped, once the new one has opened; if opening fails, it
     * stays open.
     */
    public static final RpcMethod<OpenIndexParams, OpenIndexResult> OPEN_INDEX =
            new RpcMethod<>("openIndex", OpenIndexParams.class, OpenIndexResult.class);

    /**
     * Statistics of the open index that are cheap to compute: the commit and every field, without
     * iterating over terms. Fails with {@code NO_INDEX_OPEN} if no index is open.
     */
    public static final RpcMethod<GetIndexStatsParams, GetIndexStatsResult> GET_INDEX_STATS =
            new RpcMethod<>("getIndexStats", GetIndexStatsParams.class, GetIndexStatsResult.class);

    /**
     * Closes the open index, if any, and stops its jobs. Closing when no index is open is not an
     * error; the result then has no path.
     */
    public static final RpcMethod<CloseIndexParams, CloseIndexResult> CLOSE_INDEX =
            new RpcMethod<>("closeIndex", CloseIndexParams.class, CloseIndexResult.class);

    /**
     * Starts a job that scans the terms of a field of the open index: counts them and finds the
     * {@code topN} with the highest document frequency (ADR 0005). Fails with {@code NO_INDEX_OPEN},
     * {@code FIELD_NOT_FOUND}, or {@code INVALID_PARAMS} for a {@code topN} out of range.
     */
    public static final RpcMethod<StartFieldTermScanParams, JobStarted> START_FIELD_TERM_SCAN =
            new RpcMethod<>("startFieldTermScan", StartFieldTermScanParams.class, JobStarted.class);

    /**
     * The progress of a field term scan, and its outcome once it is done. The outcome is handed out
     * once: a scan that failed answers with its error, and afterwards the job is gone ({@code
     * JOB_NOT_FOUND}).
     */
    public static final RpcMethod<JobParams, GetFieldTermScanResult> GET_FIELD_TERM_SCAN =
            new RpcMethod<>("getFieldTermScan", JobParams.class, GetFieldTermScanResult.class);

    /**
     * Stops a job of any kind and forgets it. An unknown or finished job is not an error; the result
     * says whether a running job was stopped.
     */
    public static final RpcMethod<JobParams, CancelJobResult> CANCEL_JOB =
            new RpcMethod<>("cancelJob", JobParams.class, CancelJobResult.class);

    public static final List<RpcMethod<?, ?>> ALL = List.of(
            VERSION, OPEN_INDEX, GET_INDEX_STATS, CLOSE_INDEX, START_FIELD_TERM_SCAN, GET_FIELD_TERM_SCAN, CANCEL_JOB);

    private Methods() {}

    public static Optional<RpcMethod<?, ?>> byName(String name) {
        return ALL.stream().filter(method -> method.name().equals(name)).findFirst();
    }
}
