package io.github.mfuerstenau.leia.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The {@code jsonrpc} member of every message. Only JSON-RPC 2.0 exists. */
public enum JsonRpcVersion {
    @JsonProperty("2.0")
    V2_0
}
