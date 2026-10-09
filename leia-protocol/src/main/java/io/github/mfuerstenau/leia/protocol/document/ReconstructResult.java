package io.github.mfuerstenau.leia.protocol.document;

import java.util.List;

/**
 * The outcome of {@code startReconstruct} (ADR 0029).
 *
 * @param fields the fields in the order they were asked for
 * @param tookMillis how long the job took
 */
public record ReconstructResult(List<ReconstructedField> fields, long tookMillis) {}
