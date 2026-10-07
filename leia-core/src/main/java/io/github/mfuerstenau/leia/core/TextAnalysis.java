package io.github.mfuerstenau.leia.core;

import java.util.List;

/**
 * What an analyzer makes of a text, step by step (ADR 0014).
 *
 * @param charFilters the text after each char filter of a custom analyzer
 * @param stages the tokens after the tokenizer and after each token filter of a custom analyzer;
 *     one stage for any other analyzer
 */
public record TextAnalysis(List<CharFilterStep> charFilters, List<TokenStage> stages) {

    public TextAnalysis {
        charFilters = List.copyOf(charFilters);
        stages = List.copyOf(stages);
    }

    /**
     * @param name the char filter's SPI name
     * @param text the text after this char filter and every one before it
     */
    public record CharFilterStep(String name, String text) {}

    /**
     * @param name the SPI name of the tokenizer or token filter, or the analyzer's class name
     * @param tokens the tokens of the chain up to this component
     * @param truncated whether the stage stopped at the token limit with more tokens to come
     */
    public record TokenStage(String name, List<Token> tokens, boolean truncated) {

        public TokenStage {
            tokens = List.copyOf(tokens);
        }
    }

    /**
     * A token with its attributes.
     *
     * @param position the absolute position: -1 plus every position increment so far
     * @param startOffset where the token starts in the original text, before any char filter
     * @param endOffset where it ends in the original text
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
            List<TokenAttribute> attributes) {

        public Token {
            attributes = List.copyOf(attributes);
        }
    }

    /**
     * One value of an attribute, as {@code AttributeImpl.reflectWith} reports it.
     *
     * @param attribute the attribute interface's simple name, such as {@code KeywordAttribute}
     * @param key the value's name, such as {@code keyword}
     * @param value Lucene's {@code toString} of the value
     */
    public record TokenAttribute(String attribute, String key, String value) {}
}
