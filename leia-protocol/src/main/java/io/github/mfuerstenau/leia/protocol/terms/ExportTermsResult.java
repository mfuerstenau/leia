package io.github.mfuerstenau.leia.protocol.terms;

/**
 * The outcome of an export of the terms of a field (ADR 0022).
 *
 * @param path the file written
 * @param terms the terms written, one per line
 * @param bytes the size of the file
 */
public record ExportTermsResult(String path, long terms, long bytes) {}
