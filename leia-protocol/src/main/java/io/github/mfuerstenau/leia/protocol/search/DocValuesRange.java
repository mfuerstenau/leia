package io.github.mfuerstenau.leia.protocol.search;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.util.Optional;

/**
 * A range of doc values (ADR 0033). Bytes compare unsigned, byte by byte; numbers compare as the
 * numbers they encode.
 *
 * @param lower the lower bound, as {@link DocValuesQuery#values()} has them; absent for none
 * @param upper the upper bound; absent for none
 * @param includeLower whether the lower bound itself matches
 * @param includeUpper whether the upper bound itself matches
 */
public record DocValuesRange(
        Optional<BytesValue> lower, Optional<BytesValue> upper, boolean includeLower, boolean includeUpper) {}
