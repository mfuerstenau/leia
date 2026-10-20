package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;

/**
 * Result of {@code getRuntimes} (ADR 0072).
 *
 * @param runtimes every runtime jar leia knows, newest first, loaded or not
 */
public record GetRuntimesResult(List<RuntimeJar> runtimes) {}
