package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;

/**
 * Result of {@code getRuntimes} (ADR 0072, ADR 0073).
 *
 * @param runtimes every runtime leia knows, newest first, loaded or not
 */
public record GetRuntimesResult(List<RuntimeFolder> runtimes) {}
