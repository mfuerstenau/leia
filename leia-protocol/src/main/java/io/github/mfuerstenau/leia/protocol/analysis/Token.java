package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;

/**
 * A token with its attributes (ADR 0014).
 *
 * @param term the term text
 * @param position the absolute position: -1 plus every position increment so far
 * @param positionIncrement the position increment; more than 1 after a removed token
 * @param positionLength how many positions the token spans
 * @param startOffset where the token starts in the original text, before any char filter
 * @param endOffset where it ends in the original text
 * @param type the token type, such as {@code <ALPHANUM>}
 * @param attributes every other value of the token's attributes
 */
public record Token(
        String term,
        int position,
        int positionIncrement,
        int positionLength,
        int startOffset,
        int endOffset,
        String type,
        List<TokenAttribute> attributes) {}
