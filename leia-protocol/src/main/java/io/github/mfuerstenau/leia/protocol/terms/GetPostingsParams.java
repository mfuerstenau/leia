package io.github.mfuerstenau.leia.protocol.terms;

import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import java.util.Optional;

/**
 * Params of {@code getPostings}.
 *
 * @param field the field
 * @param term the term
 * @param fromDoc the first document id of the page, or else the one after it; 0 for the first
 *     posting. A {@code nextDoc} from an earlier page continues where that page ended.
 * @param limit the most postings the page has, from 1 to {@code MAX_LIMIT}
 * @param filter the filter to mark the postings with, or to keep only those in it (ADR 0032)
 */
public record GetPostingsParams(
        String field, BytesValue term, int fromDoc, int limit, Optional<PostingsFilter> filter) {

    /** The largest {@code limit} the server accepts. */
    public static final int MAX_LIMIT = 1000;
}
