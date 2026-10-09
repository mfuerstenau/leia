package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.util.Optional;

/**
 * The value a hit was sorted by in one sort key (ADR 0012, ADR 0025). At most one is present;
 * neither is for a document without a value in a field sort.
 *
 * @param number the number of a numeric field sort, the score of a score sort or the id of a doc
 *     sort, as Java writes it ({@code Number.toString()})
 * @param term the term of a {@code STRING} sort, as text or hex
 */
public record SortValue(Optional<String> number, Optional<BytesValue> term) {}
