package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import jakarta.inject.Inject;

/** {@code closeIndex}: closes the {@link CurrentIndex}, if one is open. */
public final class CloseIndexHandler implements MethodHandler<CloseIndexParams, CloseIndexResult> {

    private final CurrentIndex currentIndex;

    @Inject
    CloseIndexHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<CloseIndexParams, CloseIndexResult> method() {
        return Methods.CLOSE_INDEX;
    }

    @Override
    public CloseIndexResult handle(CloseIndexParams params) {
        return new CloseIndexResult(
                currentIndex.close().map(index -> index.summary().path().toString()));
    }
}
