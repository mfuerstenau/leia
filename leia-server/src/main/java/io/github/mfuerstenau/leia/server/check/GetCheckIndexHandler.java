package io.github.mfuerstenau.leia.server.check;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;

/**
 * {@code getCheckIndex}: the output of a job of {@link StartCheckIndexHandler} from a given line on,
 * then its outcome with the last lines.
 */
public final class GetCheckIndexHandler implements MethodHandler<GetCheckIndexParams, GetCheckIndexResult> {

    private final Jobs jobs;

    @Inject
    GetCheckIndexHandler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<GetCheckIndexParams, GetCheckIndexResult> method() {
        return Methods.GET_CHECK_INDEX;
    }

    @Override
    public GetCheckIndexResult handle(GetCheckIndexParams params) throws RpcException {
        if (params.fromLine() < 0) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: fromLine must not be negative, not " + params.fromLine());
        }
        CheckLog log = jobs.attachment(params.jobId(), CheckIndexResult.class, CheckLog.class);
        // Polled first: once the job is done, every line is in the log.
        Jobs.Poll<CheckIndexResult> poll = jobs.poll(params.jobId(), CheckIndexResult.class);
        CheckLog.Snapshot snapshot = log.from(params.fromLine());
        return new GetCheckIndexResult(snapshot.lineCount(), snapshot.lines(), poll.result());
    }
}
