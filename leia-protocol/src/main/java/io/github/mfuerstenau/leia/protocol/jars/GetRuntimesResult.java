package io.github.mfuerstenau.leia.protocol.jars;

import java.util.List;

/**
 * Result of {@code getRuntimes} (ADR 0072, ADR 0073).
 *
 * @param runtimes every runtime leia knows, newest first, loaded or not
 * @param servers the runtimes of the servers that {@code --server} names, in the order given (ADR
 *     0090), which read the indexes of their majors before those of {@code runtimes}
 */
public record GetRuntimesResult(List<RuntimeFolder> runtimes, List<ServerRuntimeInfo> servers) {}
