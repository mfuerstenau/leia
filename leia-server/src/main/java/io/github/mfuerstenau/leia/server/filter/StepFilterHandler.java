package io.github.mfuerstenau.leia.server.filter;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;

/** {@code stepFilter}: the next or previous match of the kept filter from a document id on. */
public final class StepFilterHandler implements MethodHandler<StepFilterParams, StepFilterResult> {

    private final Filters filters;

    @Inject
    StepFilterHandler(Filters filters) {
        this.filters = filters;
    }

    @Override
    public RpcMethod<StepFilterParams, StepFilterResult> method() {
        return Methods.STEP_FILTER;
    }

    @Override
    public StepFilterResult handle(StepFilterParams params) throws RpcException {
        return new StepFilterResult(filters.get(params.filterId())
                .step(params.fromDoc(), params.forward())
                .map(match -> new FilterMatch(match.docId(), match.index())));
    }
}
