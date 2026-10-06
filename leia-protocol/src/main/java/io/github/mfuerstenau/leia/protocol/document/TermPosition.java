package io.github.mfuerstenau.leia.protocol.document;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * One occurrence of a term in a term vector.
 *
 * @param position the position; absent if the term vector has no positions
 * @param startOffset the start offset; absent if the term vector has no offsets
 * @param endOffset the end offset; absent if the term vector has no offsets
 * @param payload the payload in lowercase hex; absent if there is none
 */
public record TermPosition(
        OptionalInt position, OptionalInt startOffset, OptionalInt endOffset, Optional<String> payload) {}
