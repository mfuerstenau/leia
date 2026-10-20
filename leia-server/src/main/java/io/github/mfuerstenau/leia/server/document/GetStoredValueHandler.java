package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;

/** {@code getStoredValue}: one stored value of a document of the {@link CurrentIndex}, whole. */
public final class GetStoredValueHandler implements MethodHandler<GetStoredValueParams, GetStoredValueResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetStoredValueHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetStoredValueParams, GetStoredValueResult> method() {
        return Methods.GET_STORED_VALUE;
    }

    @Override
    public GetStoredValueResult handle(GetStoredValueParams params) throws RpcException {
        return RuntimeResults.storedValue(currentIndex.runtime(), params);
    }
}
