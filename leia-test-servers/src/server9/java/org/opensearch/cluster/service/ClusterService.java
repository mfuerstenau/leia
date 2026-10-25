package org.opensearch.cluster.service;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.threadpool.ThreadPool;

/**
 * A stand-in for OpenSearch's, which wants its own cluster settings registered, as the real one's
 * services do when they add their consumers (ADR 0092).
 */
public class ClusterService {

    private final ClusterSettings clusterSettings;

    public ClusterService(Settings settings, ClusterSettings clusterSettings, ThreadPool threadPool) {
        if (!clusterSettings.isRegistered(ClusterSettings.SLOW_TASK_THRESHOLD)) {
            throw new IllegalArgumentException(
                    "Setting is not registered for key [" + ClusterSettings.SLOW_TASK_THRESHOLD + "]");
        }
        this.clusterSettings = clusterSettings;
    }

    public ClusterSettings getClusterSettings() {
        return clusterSettings;
    }
}
