package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;

/**
 * Result of {@code analyze}: what the analyzer makes of the text, step by step (ADR 0014).
 *
 * @param charFilters the text after each char filter of a custom analyzer; empty for a standard one
 * @param stages the tokens after the tokenizer and after each token filter of a custom analyzer;
 *     one stage, {@code StandardAnalyzer}, for a standard one
 */
public record AnalyzeResult(List<CharFilterStep> charFilters, List<TokenStage> stages) {

    /** The most tokens a stage has. */
    public static final int MAX_TOKENS = 10_000;
}
