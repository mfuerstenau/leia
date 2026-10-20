package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/** {@code getTermVector}: the term vector of a document of the {@link CurrentIndex} for a field. */
public final class GetTermVectorHandler implements MethodHandler<GetTermVectorParams, GetTermVectorResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetTermVectorHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetTermVectorParams, GetTermVectorResult> method() {
        return Methods.GET_TERM_VECTOR;
    }

    @Override
    public GetTermVectorResult handle(GetTermVectorParams params) throws RpcException {
        return RuntimeResults.termVector(currentIndex.runtime(), params);
    }
}
