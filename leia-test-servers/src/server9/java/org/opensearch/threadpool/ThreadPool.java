package org.opensearch.threadpool;

import org.opensearch.common.settings.Settings;

/** A stand-in for OpenSearch's, which needs a node name (ADR 0092). */
public class ThreadPool {

    public ThreadPool(Settings settings, ExecutorBuilder<?>... customBuilders) {
        if (settings.get("node.name") == null) {
            throw new IllegalArgumentException("node.name is required");
        }
    }
}
