package io.github.mfuerstenau.leia.protocol.index;

/**
 * Params of {@code openIndex}.
 *
 * @param path the index directory; a relative path is resolved against the server's working
 *     directory, which is the TUI's
 */
public record OpenIndexParams(String path) {}
