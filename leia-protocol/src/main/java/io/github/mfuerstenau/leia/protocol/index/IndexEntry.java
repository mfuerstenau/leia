package io.github.mfuerstenau.leia.protocol.index;

import java.util.Optional;

/**
 * One index directory of an opened path (ADR 0021).
 *
 * @param path the absolute, normalized index directory
 * @param reader what its reader opened; absent if the path was opened without a reader
 */
public record IndexEntry(String path, Optional<ReaderInfo> reader) {}
