package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * One level of the order of the hits, as one of several alternatives: exactly one is present (ADR
 * 0012, ADR 0025).
 *
 * @param field by a field's doc values
 * @param score by the score
 * @param doc by the document id
 */
public record SortKey(Optional<FieldSort> field, Optional<ScoreSort> score, Optional<DocSort> doc) {}
