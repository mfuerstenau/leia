package io.github.mfuerstenau.leia.protocol.search;

import java.util.List;

/**
 * A passage of a stored value, usually a sentence, with matches in it (ADR 0042). Positions are
 * UTF-8 byte offsets into the whole value, as in ADR 0027.
 *
 * @param valueIndex the value's position among the document's stored values in the field, as
 *     {@code getStoredValue} takes it
 * @param snippet the passage's text, cut to {@link HighlightOptions#MAX_PASSAGE_CHARS} around its
 *     first match
 * @param spans the matches in the snippet, in order and not overlapping
 * @param score how well the passage matches, as the highlighter's {@code PassageScorer} scores it
 */
public record HighlightPassage(int valueIndex, Snippet snippet, List<MatchSpan> spans, float score) {}
