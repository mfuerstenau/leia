package org.opensearch.knn.index;

import java.util.List;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Setting;

/**
 * A stand-in for the k-NN plugin's, whose values come from the node's cluster service, which the
 * plugin is given as it starts (ADR 0092): without one every read fails with a {@code
 * NullPointerException}, as the real plugin's does when an index is closed or checked.
 */
public final class KNNSettings {

    public static final String CACHE_SIZE = "knn.memory.circuit_breaker.limit";

    private static final Setting<String> CACHE = new Setting<>(CACHE_SIZE, true);
    /** An index setting, which a cluster's settings refuse. */
    private static final Setting<String> INDEX_KNN = new Setting<>("index.knn", false);

    private static KNNSettings state;

    private ClusterService clusterService;

    private KNNSettings() {}

    public static synchronized KNNSettings state() {
        if (state == null) {
            state = new KNNSettings();
        }
        return state;
    }

    public List<Setting<?>> getSettings() {
        return List.of(INDEX_KNN, CACHE);
    }

    public void setClusterService(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    public String cacheSize() {
        return clusterService.getClusterSettings().get(CACHE);
    }
}
