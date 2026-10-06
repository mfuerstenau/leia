package io.github.mfuerstenau.leia.protocol.index;

import java.util.Optional;

/**
 * Result of {@code closeIndex}.
 *
 * @param path the directory of the index that was closed; absent if no index was open
 */
public record CloseIndexResult(Optional<String> path) {}
