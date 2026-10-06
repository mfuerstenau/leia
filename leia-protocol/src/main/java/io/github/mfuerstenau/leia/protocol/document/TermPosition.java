package io.github.mfuerstenau.leia.protocol.document;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * One occurrence of a term in a document, from a term vector or from the postings.
 *
 * @param position the position; absent if positions are not indexed
 * @param startOffset the start offset; absent if offsets are not indexed
 * @param endOffset the end offset; absent if offsets are not indexed
 * @param payload the payload in lowercase hex; absent if there is none
 */
public record TermPosition(
        OptionalInt position, OptionalInt startOffset, OptionalInt endOffset, Optional<String> payload) {}
