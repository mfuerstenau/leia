package io.github.mfuerstenau.leia.protocol.analysis;

import java.util.List;

/**
 * The tokens after a tokenizer or token filter: what the chain up to it makes of the text.
 *
 * @param name the SPI name of the tokenizer or token filter, or the analyzer's class name
 * @param tokens the tokens, at most {@code AnalyzeResult.MAX_TOKENS}
 * @param truncated whether the stage stopped at {@code AnalyzeResult.MAX_TOKENS} with more to come
 */
public record TokenStage(String name, List<Token> tokens, boolean truncated) {}
