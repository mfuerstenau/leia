package org.opensearch.common.settings;

import java.util.HashMap;
import java.util.Map;

/** A stand-in for OpenSearch's, built the way its builder is (ADR 0092). */
public final class Settings {

    private final Map<String, String> values;

    private Settings(Map<String, String> values) {
        this.values = values;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String get(String key) {
        return values.get(key);
    }

    public static final class Builder {

        private final Map<String, String> values = new HashMap<>();

        public Builder put(String key, String value) {
            values.put(key, value);
            return this;
        }

        public Settings build() {
            return new Settings(Map.copyOf(values));
        }
    }
}
