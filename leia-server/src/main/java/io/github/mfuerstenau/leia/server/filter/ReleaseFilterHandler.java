package io.github.mfuerstenau.leia.server.filter;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.filter.ReleaseFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.ReleaseFilterResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import jakarta.inject.Inject;

/** {@code releaseFilter}: forgets the kept filter. Idempotent, like {@code cancelJob}. */
public final class ReleaseFilterHandler implements MethodHandler<ReleaseFilterParams, ReleaseFilterResult> {

    private final Filters filters;

    @Inject
    ReleaseFilterHandler(Filters filters) {
        this.filters = filters;
    }

    @Override
    public RpcMethod<ReleaseFilterParams, ReleaseFilterResult> method() {
        return Methods.RELEASE_FILTER;
    }

    @Override
    public ReleaseFilterResult handle(ReleaseFilterParams params) {
        return new ReleaseFilterResult(filters.release(params.filterId()));
    }
}
