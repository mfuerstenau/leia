package io.github.mfuerstenau.leia.protocol.terms;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.util.Optional;

/**
 * Params of {@code getTerms}.
 *
 * @param field the field
 * @param from the first term of the page, or else the term after it; absent for the first term of
 *     the field. A {@code next} from an earlier page continues where that page ended.
 * @param limit the most terms the page has, from 1 to {@code MAX_LIMIT}
 */
public record GetTermsParams(String field, Optional<BytesValue> from, int limit) {

    /** The largest {@code limit} the server accepts. */
    public static final int MAX_LIMIT = 1000;
}
