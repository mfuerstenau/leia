package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;

/**
 * Result of {@code loadRuntimes} (ADR 0080).
 *
 * @param loaded the folders whose runtime this call loaded, such as {@code lucene9}, newest first
 * @param runtimes every runtime leia knows afterwards, as {@code getRuntimes} returns them
 */
public record LoadRuntimesResult(List<String> loaded, List<RuntimeFolder> runtimes) {}
