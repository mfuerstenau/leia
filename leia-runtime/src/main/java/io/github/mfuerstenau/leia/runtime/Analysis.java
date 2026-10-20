package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.util.List;

/**
 * The analysis tab of a runtime (ADR 0067): what analyzers can be built from, and what one makes of
 * a text, step by step, as leia-core's {@code Analyzers} has them (ADR 0014). It doesn't depend on an
 * index.
 */
public interface Analysis {

    /** The preset analyzers, char filters, tokenizers and token filters the runtime has. */
    Components components();

    /**
     * What the analyzer of {@code spec} makes of {@code text} in {@code field}; the runtime builds the
     * analyzer and closes it.
     *
     * @param field the field, for an analyzer per field and stop words per field; empty for none
     * @param maxTokens the most tokens a stage has; it stops there and is marked truncated
     * @throws IOException if a component fails on the text
     */
    TextAnalysis analyze(Search.AnalyzerSpec spec, String field, String text, int maxTokens)
            throws Search.InvalidAnalyzerException, Search.UnsupportedAnalyzerException, IOException;

    /**
     * The factories that a {@link Search.AnalyzerSpec.Custom} can name, each sorted by name, ignoring
     * case, and the analyzers that a {@link Search.AnalyzerSpec.Preset} can name.
     *
     * @param analyzers the presets, sorted by simple class name, ignoring case
     */
    record Components(
            List<AvailableAnalyzer> analyzers,
            List<Available> charFilters,
            List<Available> tokenizers,
            List<Available> tokenFilters) {

        public Components {
            analyzers = List.copyOf(analyzers);
            charFilters = List.copyOf(charFilters);
            tokenizers = List.copyOf(tokenizers);
            tokenFilters = List.copyOf(tokenFilters);
        }
    }

    /**
     * A preset analyzer.
     *
     * @param name the simple class name, such as {@code EnglishAnalyzer}
     * @param className the class name, which a preset takes
     * @param stopwords whether the preset takes stop words
     * @param stemExclusion whether it takes stem exclusions; never {@link WordSetSupport#REQUIRED}
     * @param options what else it takes, in the enum's order
     */
    record AvailableAnalyzer(
            String name,
            String className,
            WordSetSupport stopwords,
            WordSetSupport stemExclusion,
            List<PresetOption> options) {

        public AvailableAnalyzer {
            options = List.copyOf(options);
        }
    }

    /**
     * @param name the SPI name, such as {@code lowercase}
     * @param className the factory class, whose documentation lists the parameters
     */
    record Available(String name, String className) {}

    /** Whether a preset analyzer takes stop words or stem exclusions. */
    enum WordSetSupport {
        /** It doesn't take the set. */
        NONE,
        /** It takes the set, and has a default without it. */
        OPTIONAL,
        /** It can't be created without the set. */
        REQUIRED
    }

    /** What a preset analyzer takes besides its word sets. */
    enum PresetOption {
        MAX_TOKEN_LENGTH,
        STEM_OVERRIDE,
        JAPANESE,
        KOREAN,
        COLLATION,
        JAPANESE_COMPLETION
    }

    /**
     * What an analyzer makes of a text, step by step.
     *
     * @param charFilters the text after each char filter of a custom analyzer
     * @param stages the tokens after the tokenizer and after each token filter of a custom analyzer,
     *     then after each wrapper and the stop words of the field; one stage for any other analyzer
     */
    record TextAnalysis(List<CharFilterStep> charFilters, List<TokenStage> stages) {

        public TextAnalysis {
            charFilters = List.copyOf(charFilters);
            stages = List.copyOf(stages);
        }
    }

    /**
     * @param name the char filter's SPI name
     * @param text the text after this char filter and every one before it
     */
    record CharFilterStep(String name, String text) {}

    /**
     * @param name the SPI name of the tokenizer or token filter, or the analyzer's class name
     * @param tokens the tokens of the chain up to this component
     * @param truncated whether the stage stopped at the token limit with more tokens to come
     */
    record TokenStage(String name, List<Token> tokens, boolean truncated) {

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
    record Token(
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
    record TokenAttribute(String attribute, String key, String value) {}
}
