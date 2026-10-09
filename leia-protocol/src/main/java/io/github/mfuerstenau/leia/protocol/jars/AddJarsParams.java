package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;

/**
 * Params of {@code addJars} (ADR 0024).
 *
 * @param paths jars, or directories that stand for the jars directly in them; at least one. A
 *     relative path is resolved against the server's working directory.
 */
public record AddJarsParams(List<String> paths) {}
