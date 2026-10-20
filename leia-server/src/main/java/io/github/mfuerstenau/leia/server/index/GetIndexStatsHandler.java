package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/** {@code getIndexStats}: the cheap statistics of the {@link CurrentIndex}. */
public final class GetIndexStatsHandler implements MethodHandler<GetIndexStatsParams, GetIndexStatsResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetIndexStatsHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetIndexStatsParams, GetIndexStatsResult> method() {
        return Methods.GET_INDEX_STATS;
    }

    @Override
    public GetIndexStatsResult handle(GetIndexStatsParams params) throws RpcException {
        return RuntimeResults.indexStats(currentIndex.runtime());
    }
}
