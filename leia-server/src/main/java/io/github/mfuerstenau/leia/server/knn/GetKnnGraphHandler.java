package io.github.mfuerstenau.leia.server.knn;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeKnnGraph;
import jakarta.inject.Inject;

/** {@code getKnnGraph}: the HNSW graphs of a KNN vector field in the {@link CurrentIndex}, segment by segment (ADR 0085). */
public final class GetKnnGraphHandler implements MethodHandler<GetKnnGraphParams, GetKnnGraphResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetKnnGraphHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetKnnGraphParams, GetKnnGraphResult> method() {
        return Methods.GET_KNN_GRAPH;
    }

    @Override
    public GetKnnGraphResult handle(GetKnnGraphParams params) throws RpcException {
        return RuntimeKnnGraph.graph(currentIndex.runtime(), params);
    }
}
