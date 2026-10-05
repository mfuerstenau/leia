package io.github.mfuerstenau.leia.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.mfuerstenau.leia.protocol.JsonRpcVersion;

/**
 * A request without its {@code params}: read strictly before the method is known, so that envelope
 * errors are reported as {@code INVALID_REQUEST} and the method can be looked up without parsing
 * the params into an untyped tree (ADR 0003).
 */
@JsonIgnoreProperties("params")
record RequestEnvelope(JsonRpcVersion jsonrpc, long id, String method) {

    RequestEnvelope {
        if (id < 0) {
            throw new IllegalArgumentException("id must not be negative");
        }
    }
}
