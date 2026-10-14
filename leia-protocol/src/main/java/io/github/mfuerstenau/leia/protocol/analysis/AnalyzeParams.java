package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.Optional;

/**
 * Params of {@code analyze}.
 *
 * @param analyzer the analyzer
 * @param text the text to analyze, at most {@code MAX_TEXT_LENGTH} UTF-16 code units
 * @param field the field the text is analyzed for: its own analyzer and its stop words, if the
 *     analyzer has them (ADR 0039); absent for the default analyzer, with the field name {@code ""}
 */
public record AnalyzeParams(Analyzer analyzer, String text, Optional<String> field) {

    /** The longest {@code text} the server accepts, in UTF-16 code units. */
    public static final int MAX_TEXT_LENGTH = 10_000;
}
