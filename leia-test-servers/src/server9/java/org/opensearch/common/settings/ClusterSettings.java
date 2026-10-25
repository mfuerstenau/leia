package org.opensearch.common.settings;

import java.util.HashSet;
import java.util.Set;

/**
 * A stand-in for OpenSearch's, which refuses a setting that isn't of the node's scope and one that
 * isn't registered, as the real one does (ADR 0092).
 */
public final class ClusterSettings {

    /** What the cluster service registers its consumers for. */
    public static final String SLOW_TASK_THRESHOLD = "cluster.service.slow_cluster_manager_task_logging_threshold";

    public static final Set<Setting<?>> BUILT_IN_CLUSTER_SETTINGS = Set.of(new Setting<>(SLOW_TASK_THRESHOLD, true));

    private final Set<String> registered = new HashSet<>();

    public ClusterSettings(Settings settings, Set<Setting<?>> registered) {
        for (Setting<?> setting : registered) {
            if (!setting.hasNodeScope()) {
                throw new IllegalArgumentException(
                        "Setting " + setting.getKey() + " must be a NodeScope setting but has: [IndexScope]");
            }
            this.registered.add(setting.getKey());
        }
    }

    public boolean isRegistered(String key) {
        return registered.contains(key);
    }

    public <T> String get(Setting<T> setting) {
        if (!registered.contains(setting.getKey())) {
            throw new IllegalArgumentException("setting " + setting.getKey() + " has not been registered");
        }
        return "default";
    }
}
