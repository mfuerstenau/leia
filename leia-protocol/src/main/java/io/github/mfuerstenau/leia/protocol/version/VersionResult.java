package io.github.mfuerstenau.leia.protocol.version;

/**
 * Result of {@code version}: the server's name, version and build, and the JVM it runs on.
 *
 * @param commit short git hash of the build
 * @param commitTime ISO-8601 timestamp of that commit
 */
public record VersionResult(String name, String version, String commit, String commitTime, JavaRuntime java) {}
