package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/** {@code getTerms}: a page of the terms of a field of the {@link CurrentIndex}. */
public final class GetTermsHandler implements MethodHandler<GetTermsParams, GetTermsResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetTermsHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetTermsParams, GetTermsResult> method() {
        return Methods.GET_TERMS;
    }

    @Override
    public GetTermsResult handle(GetTermsParams params) throws RpcException {
        return RuntimeResults.terms(currentIndex.runtime(), params);
    }
}
