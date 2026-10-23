package io.github.mfuerstenau.leia.server.knn;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeKnnGraph;
import jakarta.inject.Inject;

/** {@code getKnnGraphNode}: a node of a segment's HNSW graph in the {@link CurrentIndex}, with its neighbours (ADR 0085). */
public final class GetKnnGraphNodeHandler implements MethodHandler<GetKnnGraphNodeParams, GetKnnGraphNodeResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetKnnGraphNodeHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetKnnGraphNodeParams, GetKnnGraphNodeResult> method() {
        return Methods.GET_KNN_GRAPH_NODE;
    }

    @Override
    public GetKnnGraphNodeResult handle(GetKnnGraphNodeParams params) throws RpcException {
        return RuntimeKnnGraph.node(currentIndex.runtime(), params);
    }
}
