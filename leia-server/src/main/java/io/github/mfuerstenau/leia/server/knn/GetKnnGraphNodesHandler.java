package io.github.mfuerstenau.leia.server.knn;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesParams;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeKnnGraph;
import jakarta.inject.Inject;

/** {@code getKnnGraphNodes}: a page of the nodes of one level of a segment's HNSW graph in the {@link CurrentIndex} (ADR 0085). */
public final class GetKnnGraphNodesHandler implements MethodHandler<GetKnnGraphNodesParams, GetKnnGraphNodesResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetKnnGraphNodesHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetKnnGraphNodesParams, GetKnnGraphNodesResult> method() {
        return Methods.GET_KNN_GRAPH_NODES;
    }

    @Override
    public GetKnnGraphNodesResult handle(GetKnnGraphNodesParams params) throws RpcException {
        return RuntimeKnnGraph.nodes(currentIndex.runtime(), params);
    }
}
