package io.github.mfuerstenau.leia.testserver.ext;

/**
 * A class of the module that the fake k-NN plugin extends (ADR 0092), in {@code modules/}, as
 * OpenSearch 3's k-NN plugin gets Guava from {@code transport-grpc}: the plugin's format fails
 * without it.
 */
public final class LeiaExtSupport {

    private LeiaExtSupport() {}

    public static void touch() {}
}
