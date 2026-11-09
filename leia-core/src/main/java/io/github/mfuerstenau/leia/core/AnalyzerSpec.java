package io.github.mfuerstenau.leia.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.lucene.analysis.Analyzer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link Analyzers#build} makes an {@link Analyzer} from (ADR 0014): {@code StandardAnalyzer},
 * a preset analyzer by class name (ADR 0015) or a {@code CustomAnalyzer}, each of which can be
 * wrapped in shingles and a token limit (ADR 0023); then analyzers per field, and stop words per
 * field around them (ADR 0039).
 */
public sealed interface AnalyzerSpec {

    /** Lucene's {@code StandardAnalyzer}. */
    record Standard() implements AnalyzerSpec {}

    /**
     * An analyzer created by class name (ADR 0015), with its default settings and, if given, stop
     * words and stem exclusions of its own (ADR 0017), and the options it takes (ADR 0023).
     *
     * @param className the class's fully qualified name, one that {@link Analyzers#components} lists
     * @param stopwords the stop words; null for the analyzer's default ones
     * @param stemExclusion the words kept from stemming; null for none
     * @param maxTokenLength the longest token; null for the analyzer's default
     * @param stemOverride the words whose stem is given; null for the analyzer's default ones
     * @param japanese the options of Kuromoji's {@code JapaneseAnalyzer}; null for its defaults
     * @param korean the options of Nori's {@code KoreanAnalyzer}; null for its defaults
     * @param collation the collator of a collation analyzer; null for the root locale and the
     *     collator's defaults (ADR 0038)
     * @param japaneseCompletion the options of Kuromoji's {@code JapaneseCompletionAnalyzer}; null
     *     for its defaults (ADR 0038)
     */
    record Preset(
            String className,
            @Nullable WordSet stopwords,
            @Nullable WordSet stemExclusion,
            @Nullable Integer maxTokenLength,
            @Nullable StemDictionary stemOverride,
            @Nullable Japanese japanese,
            @Nullable Korean korean,
            @Nullable Collation collation,
            @Nullable JapaneseCompletion japaneseCompletion)
            implements AnalyzerSpec {

        public Preset {
            Objects.requireNonNull(className);
        }

        /** The analyzer with these word sets, and no other options. */
        public Preset(String className, @Nullable WordSet stopwords, @Nullable WordSet stemExclusion) {
            this(className, stopwords, stemExclusion, null, null, null, null, null, null);
        }

        /** The analyzer with its default stop words and no stem exclusions. */
        public Preset(String className) {
            this(className, null, null);
        }
    }

    /** The words of a preset's stop words or stem exclusions (ADR 0017). */
    sealed interface WordSet {

        /** The words themselves; none for an empty set. */
        record Words(List<String> words) implements WordSet {

            public Words {
                words = List.copyOf(words);
            }
        }

        /**
         * A word list file: UTF-8, one word per line, and lines starting with {@code #} are skipped.
         */
        record File(Path path) implements WordSet {

            public File {
                Objects.requireNonNull(path);
            }
        }
    }

    /**
     * The stem override dictionary of a preset (ADR 0023): each word is stemmed to the given stem.
     */
    sealed interface StemDictionary {

        /** The words and their stems; none for an empty dictionary. */
        record Entries(Map<String, String> entries) implements StemDictionary {

            public Entries {
                entries = Map.copyOf(entries);
            }
        }

        /**
         * A dictionary file: UTF-8, {@code word<TAB>stem} per line, and blank lines and lines
         * starting with {@code #} are skipped.
         */
        record File(Path path) implements StemDictionary {

            public File {
                Objects.requireNonNull(path);
            }
        }
    }

    /**
     * The options of Kuromoji's {@code JapaneseAnalyzer} (ADR 0023); each is the analyzer's default
     * when null.
     *
     * @param userDictionary a user dictionary file in Kuromoji's CSV format, UTF-8
     * @param mode the tokenizer's mode
     * @param stopTags the parts of speech that are removed, such as {@code 助詞-格助詞-一般}
     */
    record Japanese(
            @Nullable Path userDictionary,
            @Nullable JapaneseMode mode,
            @Nullable WordSet stopTags) {}

    /** {@code JapaneseTokenizer.Mode}. */
    enum JapaneseMode {
        NORMAL,
        SEARCH,
        EXTENDED
    }

    /**
     * The options of Nori's {@code KoreanAnalyzer} (ADR 0023); each is the analyzer's default when
     * null.
     *
     * @param userDictionary a user dictionary file in Nori's format, UTF-8
     * @param decompoundMode what happens to compound words
     * @param stopTags the parts of speech that are removed, as {@code POS.Tag} names such as {@code JX}
     * @param outputUnknownUnigrams whether unknown words are also split into unigrams
     */
    record Korean(
            @Nullable Path userDictionary,
            @Nullable KoreanDecompoundMode decompoundMode,
            @Nullable WordSet stopTags,
            @Nullable Boolean outputUnknownUnigrams) {}

    /** {@code KoreanTokenizer.DecompoundMode}. */
    enum KoreanDecompoundMode {
        NONE,
        DISCARD,
        MIXED
    }

    /**
     * The collator of a collation analyzer (ADR 0038): the JDK's or ICU's, whichever the analyzer
     * takes; each setting is the collator's default when null.
     *
     * @param locale a BCP 47 language tag; empty for the root locale
     * @param strength the collator's strength
     * @param decomposition the collator's decomposition
     */
    record Collation(
            String locale,
            @Nullable CollationStrength strength,
            @Nullable CollationDecomposition decomposition) {

        public Collation {
            Objects.requireNonNull(locale);
        }
    }

    /** The strength of a collator; {@code QUATERNARY} is ICU's only. */
    enum CollationStrength {
        PRIMARY,
        SECONDARY,
        TERTIARY,
        QUATERNARY,
        IDENTICAL
    }

    /** The decomposition of a collator; {@code FULL} is the JDK's only. */
    enum CollationDecomposition {
        NO,
        CANONICAL,
        FULL
    }

    /**
     * The options of Kuromoji's {@code JapaneseCompletionAnalyzer} (ADR 0038); each is the
     * analyzer's default when null.
     *
     * @param userDictionary a user dictionary file in Kuromoji's CSV format, UTF-8
     * @param mode the romanization mode
     */
    record JapaneseCompletion(
            @Nullable Path userDictionary, @Nullable JapaneseCompletionMode mode) {}

    /** {@code JapaneseCompletionFilter.Mode}. */
    enum JapaneseCompletionMode {
        INDEX,
        QUERY
    }

    /**
     * A {@code CustomAnalyzer}: char filters, one tokenizer, then token filters.
     *
     * @param configDir the directory that resource files are read from before the classpath; null
     *     for the classpath only
     */
    record Custom(
            List<Component> charFilters,
            Component tokenizer,
            List<Component> tokenFilters,
            @Nullable Path configDir) implements AnalyzerSpec {

        public Custom {
            charFilters = List.copyOf(charFilters);
            Objects.requireNonNull(tokenizer);
            tokenFilters = List.copyOf(tokenFilters);
        }
    }

    /**
     * An analyzer wrapped by Lucene's {@code ShingleAnalyzerWrapper}, then by its {@code
     * LimitTokenCountAnalyzer} (ADR 0023); at least one of them is present.
     *
     * @param analyzer what is wrapped
     */
    record Wrapped(
            AnalyzerSpec analyzer,
            @Nullable Shingles shingles,
            @Nullable LimitTokenCount limitTokenCount) implements AnalyzerSpec {

        public Wrapped {
            Objects.requireNonNull(analyzer);
        }
    }

    /**
     * An analyzer per field, as Lucene's {@code PerFieldAnalyzerWrapper} has it (ADR 0039).
     *
     * @param defaultAnalyzer the analyzer of every other field
     * @param fields the fields with an analyzer of their own
     */
    record PerField(AnalyzerSpec defaultAnalyzer, Map<String, AnalyzerSpec> fields) implements AnalyzerSpec {

        public PerField {
            Objects.requireNonNull(defaultAnalyzer);
            fields = Map.copyOf(fields);
        }
    }

    /**
     * An analyzer whose tokens of some fields lose stop words, as Lucene's {@code
     * QueryAutoStopWordAnalyzer} removes them (ADR 0039).
     *
     * @param analyzer what is wrapped
     * @param words the stop words of each field, case-sensitive; a field without an entry keeps every
     *     token
     */
    record AutoStopWords(AnalyzerSpec analyzer, Map<String, List<String>> words) implements AnalyzerSpec {

        public AutoStopWords {
            Objects.requireNonNull(analyzer);
            words = Map.copyOf(words);
            words.values().forEach(Objects::requireNonNull);
        }
    }

    /**
     * The arguments of Lucene's {@code ShingleAnalyzerWrapper}: word n-grams of the analyzer's
     * tokens.
     *
     * @param minShingleSize the fewest tokens in a shingle, at least 2
     * @param maxShingleSize the most tokens in a shingle, at least {@code minShingleSize}
     * @param tokenSeparator what goes between the tokens of a shingle
     * @param outputUnigrams whether the tokens themselves are kept
     * @param outputUnigramsIfNoShingles whether the tokens are kept when there are no shingles
     * @param fillerToken what stands for a removed token, such as a stop word
     */
    record Shingles(
            int minShingleSize,
            int maxShingleSize,
            String tokenSeparator,
            boolean outputUnigrams,
            boolean outputUnigramsIfNoShingles,
            String fillerToken) {

        public Shingles {
            Objects.requireNonNull(tokenSeparator);
            Objects.requireNonNull(fillerToken);
        }
    }

    /**
     * The arguments of Lucene's {@code LimitTokenCountAnalyzer}.
     *
     * @param maxTokenCount the most tokens, at least 1
     * @param consumeAllTokens whether the analyzer's stream is read to its end after the last token
     */
    record LimitTokenCount(int maxTokenCount, boolean consumeAllTokens) {}

    /**
     * A char filter, tokenizer or token filter.
     *
     * @param name the factory's SPI name, such as {@code lowercase}; case doesn't matter
     * @param params the factory's parameters
     */
    record Component(String name, Map<String, String> params) {

        public Component {
            Objects.requireNonNull(name);
            params = Map.copyOf(params);
        }
    }
}
