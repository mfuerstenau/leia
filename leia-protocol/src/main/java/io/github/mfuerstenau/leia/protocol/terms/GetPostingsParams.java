package io.github.mfuerstenau.leia.protocol.terms;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Params of {@code getPostings}.
 *
 * @param field the field
 * @param term the term
 * @param fromDoc the first document id of the page, or else the one after it; 0 for the first
 *     posting. A {@code nextDoc} from an earlier page continues where that page ended.
 * @param limit the most postings the page has, from 1 to {@code MAX_LIMIT}
 * @param filter the filter to mark the postings with, or to keep only those in it (ADR 0032)
 * @param maxPositions if present, each posting carries its first {@code maxPositions} occurrences
 *     of the term, from 1 to {@code MAX_POSITIONS}; absent, no positions (ADR 0119)
 */
public record GetPostingsParams(
        String field,
        BytesValue term,
        int fromDoc,
        int limit,
        Optional<PostingsFilter> filter,
        OptionalInt maxPositions) {

    /** Params without positions; a {@code null} {@code filter} means none. */
    public GetPostingsParams(String field, BytesValue term, int fromDoc, int limit, @Nullable PostingsFilter filter) {
        this(field, term, fromDoc, limit, Optional.ofNullable(filter), OptionalInt.empty());
    }

    /** The largest {@code limit} the server accepts. */
    public static final int MAX_LIMIT = 1000;

    /** The largest {@code maxPositions} the server accepts. */
    public static final int MAX_POSITIONS = 1000;
}
