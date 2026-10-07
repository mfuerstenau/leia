package io.github.mfuerstenau.leia.protocol.analysis;

/**
 * Params of {@code analyze}.
 *
 * @param analyzer the analyzer
 * @param text the text to analyze, at most {@code MAX_TEXT_LENGTH} UTF-16 code units
 */
public record AnalyzeParams(Analyzer analyzer, String text) {

    /** The longest {@code text} the server accepts, in UTF-16 code units. */
    public static final int MAX_TEXT_LENGTH = 10_000;
}
