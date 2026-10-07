package io.github.mfuerstenau.leia.core;

/**
 * The options that only the classic query parser has (ADR 0011).
 *
 * @param fuzzyMaxEdits the edit distance of fuzzy terms that give none, 0 to 2; the standard parser
 *     always uses 2
 * @param splitOnWhitespace whether whitespace splits the text before it is analyzed
 * @param autoGeneratePhraseQueries whether text that analyzes to several terms becomes a phrase;
 *     Lucene allows it only with {@code splitOnWhitespace}
 */
public record ClassicOptions(int fuzzyMaxEdits, boolean splitOnWhitespace, boolean autoGeneratePhraseQueries) {

    /** The largest edit distance Lucene's fuzzy queries support. */
    public static final int MAX_FUZZY_EDITS = 2;

    /** Lucene's defaults. */
    public static final ClassicOptions DEFAULTS = new ClassicOptions(MAX_FUZZY_EDITS, false, false);

    /**
     * @throws IllegalArgumentException if {@code fuzzyMaxEdits} is out of range, or for {@code
     *     autoGeneratePhraseQueries} without {@code splitOnWhitespace}
     */
    public ClassicOptions {
        if (fuzzyMaxEdits < 0 || fuzzyMaxEdits > MAX_FUZZY_EDITS) {
            throw new IllegalArgumentException(
                    "fuzzyMaxEdits must be 0 to " + MAX_FUZZY_EDITS + ", not " + fuzzyMaxEdits);
        }
        if (autoGeneratePhraseQueries && !splitOnWhitespace) {
            throw new IllegalArgumentException("autoGeneratePhraseQueries needs splitOnWhitespace");
        }
    }
}
