package org.opensearch.common.settings;

/** A stand-in for OpenSearch's, with the scope and the key (ADR 0092). */
public final class Setting<T> {

    private final String key;
    private final boolean nodeScope;

    public Setting(String key, boolean nodeScope) {
        this.key = key;
        this.nodeScope = nodeScope;
    }

    public String getKey() {
        return key;
    }

    public boolean hasNodeScope() {
        return nodeScope;
    }
}
