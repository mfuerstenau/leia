package io.github.mfuerstenau.leia.protocol.version;

/**
 * The JVM the server runs on.
 *
 * @param version {@code Runtime.version()}
 * @param vendor the {@code java.vendor} system property
 * @param home the {@code java.home} system property
 */
public record JavaRuntime(String version, String vendor, String home) {}
