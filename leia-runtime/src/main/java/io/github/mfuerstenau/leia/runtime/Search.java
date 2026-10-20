package io.github.mfuerstenau.leia.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.LongConsumer;

/** The classic query parser, term queries and search of a {@link RuntimeIndex}. */
public interface Search {

    /**
     * Parses {@code expression} with the classic query parser and {@code analyzer}.
     *
     * @throws UnsupportedAnalyzerException if the runtime can't build {@code analyzer}
     * @throws InvalidAnalyzerException if {@code analyzer} is wrong
     */
    ParsedQuery parse(String expression, ParserOptions options, AnalyzerSpec analyzer)
            throws InvalidQueryException, UnsupportedAnalyzerException, InvalidAnalyzerException;

    /**
     * A query for exactly one term of {@code field}, neither analyzed nor split (ADR 0058), for
     * {@link #search}, {@link #matches} and {@link #explain}.
     */
    ParsedQuery term(String field, byte[] term);

    /**
     * Searches with a query from {@link #parse}, best score first.
     *
     * @param exactTotalHits whether every hit is counted; otherwise the runtime may stop counting
     *     after a thousand, as Luke does, and says so in {@link SearchResults#totalHitsExact()} (ADR
     *     0070). A runtime whose Lucene always counts them all ignores it.
     * @param fields the stored fields to return per hit; empty for all of them
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the search
     */
    SearchResults search(
            ParsedQuery query,
            int offset,
            int limit,
            boolean exactTotalHits,
            Optional<List<String>> fields,
            LongConsumer progress)
            throws IOException;

    /** Searches as {@link #search(ParsedQuery, int, int, boolean, Optional, LongConsumer)}, counting every hit. */
    default SearchResults search(
            ParsedQuery query, int offset, int limit, Optional<List<String>> fields, LongConsumer progress)
            throws IOException {
        return search(query, offset, limit, true, fields, progress);
    }

    /**
     * Every document that matches a query from {@link #parse}, unscored, for a filter (ADR 0056):
     * the live ones, and the deleted ones too if {@code includeDeleted}.
     *
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the matching
     * @throws IllegalArgumentException if {@code includeDeleted} and not {@link #matchesDeleted()}
     */
    Matches matches(ParsedQuery query, boolean includeDeleted, LongConsumer progress) throws IOException;

    /**
     * Whether {@link #matches} takes {@code includeDeleted}: false if the runtime's Lucene can't read
     * the postings of deleted documents, as 3.6 can't.
     */
    boolean matchesDeleted();

    /**
     * How a query from {@link #parse} or {@link Knn} scores document {@code docId} (ADR 0057), with
     * the runtime's default similarity, as {@link #search} scores it. A deleted document doesn't
     * match.
     *
     * @param progress receives the documents searched so far, from time to time; it may throw to stop
     *     the explanation
     */
    Explanation explain(ParsedQuery query, int docId, LongConsumer progress) throws IOException;

    /**
     * A query from {@link #parse}, {@link #term}, {@link Knn}, {@link DocValuesSearch}, {@link
     * StandardSearch} or {@link MoreLikeThisSearch}; its {@code toString} is the parsed query.
     */
    interface ParsedQuery {}

    /**
     * The classic query parser's options (ADR 0069).
     *
     * @param fuzzyMaxEdits the edit distance of fuzzy terms that give none, 0 to 2; a Lucene without
     *     edit distances, as 3.6, ignores it
     * @param splitOnWhitespace whether whitespace splits the text before it is analyzed; a Lucene that
     *     always splits, as 3.6 and 5.5, ignores it
     * @param autoGeneratePhraseQueries whether text that analyzes to several terms becomes a phrase;
     *     leia passes it only with {@code splitOnWhitespace}
     * @param dates how range bounds are read as dates
     */
    record ParserOptions(
            String defaultField,
            boolean defaultAnd,
            boolean allowLeadingWildcard,
            int phraseSlop,
            int fuzzyPrefixLength,
            boolean enablePositionIncrements,
            boolean autoGeneratePhraseQueries,
            int fuzzyMaxEdits,
            boolean splitOnWhitespace,
            Dates dates) {

        public ParserOptions {
            Objects.requireNonNull(dates);
        }

        /** Options with 2 fuzzy edits, split on whitespace only for auto-generated phrases, and no dates. */
        public ParserOptions(
                String defaultField,
                boolean defaultAnd,
                boolean allowLeadingWildcard,
                int phraseSlop,
                int fuzzyPrefixLength,
                boolean enablePositionIncrements,
                boolean autoGeneratePhraseQueries) {
            this(
                    defaultField,
                    defaultAnd,
                    allowLeadingWildcard,
                    phraseSlop,
                    fuzzyPrefixLength,
                    enablePositionIncrements,
                    autoGeneratePhraseQueries,
                    2,
                    autoGeneratePhraseQueries,
                    Dates.NONE);
        }
    }

    /** The resolution that a date in a range bound is turned into a term with, as Lucene's {@code DateTools.Resolution}. */
    enum DateResolution {
        YEAR,
        MONTH,
        DAY,
        HOUR,
        MINUTE,
        SECOND,
        MILLISECOND
    }

    /**
     * How a query parser reads range bounds as dates (ADR 0026, ADR 0069): in the short date format
     * of {@code locale}, in {@code timeZone}, turned into terms with a resolution. A bound that isn't
     * a date, or on a field without a resolution, stays text. An inclusive upper bound includes the
     * whole day.
     *
     * @param resolution the resolution of the fields without one of their own; empty, the bounds on
     *     those fields stay text
     * @param fields the resolutions of single fields, by field name, which win over {@code resolution}
     * @param locale the locale of the date format; empty, the JVM's default
     * @param timeZone the time zone the dates are read in; empty, the JVM's default
     */
    record Dates(
            Optional<DateResolution> resolution,
            Map<String, DateResolution> fields,
            Optional<Locale> locale,
            Optional<ZoneId> timeZone) {

        /** No resolutions: every bound stays text. */
        public static final Dates NONE = new Dates(Optional.empty(), Map.of(), Optional.empty(), Optional.empty());

        public Dates {
            Objects.requireNonNull(resolution);
            fields = Map.copyOf(fields);
            Objects.requireNonNull(locale);
            Objects.requireNonNull(timeZone);
        }

        /** The resolution of {@code field}: its own, or else the default one. */
        public Optional<DateResolution> resolution(String field) {
            return Optional.ofNullable(fields.get(field)).or(() -> resolution);
        }
    }

    /**
     * An analyzer of the runtime's Lucene, as leia-core's {@code AnalyzerSpec} has it (ADR 0045, ADR
     * 0067): the standard analyzer, a preset by class name or a custom analyzer, each of which can be
     * wrapped in shingles and a token limit; then analyzers per field, and stop words per field around
     * them. A runtime builds what its Lucene has, and refuses the rest with {@link
     * UnsupportedAnalyzerException}.
     */
    sealed interface AnalyzerSpec {

        /** Lucene's {@code StandardAnalyzer}. */
        record Standard() implements AnalyzerSpec {}

        /**
         * An analyzer created by class name, with its default settings and, if given, stop words and
         * stem exclusions of its own, and the options it takes.
         *
         * @param className Lucene's class name, unrelocated, such as {@code
         *     org.apache.lucene.analysis.en.EnglishAnalyzer}; a relocated runtime matches its simple
         *     name
         * @param stopwords the stop words; empty for the analyzer's default ones
         * @param stemExclusion the words kept from stemming; empty for none
         * @param maxTokenLength the longest token; empty for the analyzer's default
         * @param stemOverride the words whose stem is given; empty for the analyzer's default ones
         * @param japanese the options of Kuromoji's {@code JapaneseAnalyzer}; empty for its defaults
         * @param korean the options of Nori's {@code KoreanAnalyzer}; empty for its defaults
         * @param collation the collator of a collation analyzer; empty for the root locale and the
         *     collator's defaults
         * @param japaneseCompletion the options of Kuromoji's {@code JapaneseCompletionAnalyzer}; empty
         *     for its defaults
         */
        record Preset(
                String className,
                Optional<WordSet> stopwords,
                Optional<WordSet> stemExclusion,
                OptionalInt maxTokenLength,
                Optional<StemDictionary> stemOverride,
                Optional<Japanese> japanese,
                Optional<Korean> korean,
                Optional<Collation> collation,
                Optional<JapaneseCompletion> japaneseCompletion)
                implements AnalyzerSpec {

            public Preset {
                Objects.requireNonNull(className);
                Objects.requireNonNull(stopwords);
                Objects.requireNonNull(stemExclusion);
                Objects.requireNonNull(maxTokenLength);
                Objects.requireNonNull(stemOverride);
                Objects.requireNonNull(japanese);
                Objects.requireNonNull(korean);
                Objects.requireNonNull(collation);
                Objects.requireNonNull(japaneseCompletion);
            }

            /** The analyzer with these stop words and this longest token, and no other options. */
            public Preset(String className, Optional<WordSet> stopwords, OptionalInt maxTokenLength) {
                this(
                        className,
                        stopwords,
                        Optional.empty(),
                        maxTokenLength,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
            }

            /** The analyzer with its default settings. */
            public Preset(String className) {
                this(className, Optional.empty(), OptionalInt.empty());
            }

            /** The simple name of {@link #className}, such as {@code EnglishAnalyzer}. */
            public String simpleName() {
                return className.substring(className.lastIndexOf('.') + 1);
            }
        }

        /** The words of a preset's stop words, stem exclusions or stop tags. */
        sealed interface WordSet {

            /** The words themselves; none for an empty set. */
            record Words(List<String> words) implements WordSet {

                public Words {
                    words = List.copyOf(words);
                }
            }

            /**
             * A word list file: UTF-8, one word per line, as Lucene's {@code WordlistLoader.getWordSet}
             * reads it: each line trimmed, and blank lines skipped.
             */
            record File(Path path) implements WordSet {

                public File {
                    Objects.requireNonNull(path);
                }
            }
        }

        /** The stem override dictionary of a preset: each word is stemmed to the given stem. */
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
         * The options of Kuromoji's {@code JapaneseAnalyzer}; each is the analyzer's default when
         * empty.
         *
         * @param userDictionary a user dictionary file in Kuromoji's CSV format, UTF-8
         * @param mode the tokenizer's mode
         * @param stopTags the parts of speech that are removed
         */
        record Japanese(Optional<Path> userDictionary, Optional<JapaneseMode> mode, Optional<WordSet> stopTags) {

            public Japanese {
                Objects.requireNonNull(userDictionary);
                Objects.requireNonNull(mode);
                Objects.requireNonNull(stopTags);
            }
        }

        /** {@code JapaneseTokenizer.Mode}. */
        enum JapaneseMode {
            NORMAL,
            SEARCH,
            EXTENDED
        }

        /**
         * The options of Nori's {@code KoreanAnalyzer}; each is the analyzer's default when empty.
         *
         * @param userDictionary a user dictionary file in Nori's format, UTF-8
         * @param decompoundMode what happens to compound words
         * @param stopTags the parts of speech that are removed, as {@code POS.Tag} names
         * @param outputUnknownUnigrams whether unknown words are also split into unigrams
         */
        record Korean(
                Optional<Path> userDictionary,
                Optional<KoreanDecompoundMode> decompoundMode,
                Optional<WordSet> stopTags,
                Optional<Boolean> outputUnknownUnigrams) {

            public Korean {
                Objects.requireNonNull(userDictionary);
                Objects.requireNonNull(decompoundMode);
                Objects.requireNonNull(stopTags);
                Objects.requireNonNull(outputUnknownUnigrams);
            }
        }

        /** {@code KoreanTokenizer.DecompoundMode}. */
        enum KoreanDecompoundMode {
            NONE,
            DISCARD,
            MIXED
        }

        /**
         * The collator of a collation analyzer: the JDK's or ICU's, whichever the analyzer takes; each
         * setting is the collator's default when empty.
         *
         * @param locale a BCP 47 language tag; empty for the root locale
         */
        record Collation(
                String locale, Optional<CollationStrength> strength, Optional<CollationDecomposition> decomposition) {

            public Collation {
                Objects.requireNonNull(locale);
                Objects.requireNonNull(strength);
                Objects.requireNonNull(decomposition);
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
         * The options of Kuromoji's {@code JapaneseCompletionAnalyzer}; each is the analyzer's default
         * when empty.
         *
         * @param userDictionary a user dictionary file in Kuromoji's CSV format, UTF-8
         * @param mode the romanization mode
         */
        record JapaneseCompletion(Optional<Path> userDictionary, Optional<JapaneseCompletionMode> mode) {

            public JapaneseCompletion {
                Objects.requireNonNull(userDictionary);
                Objects.requireNonNull(mode);
            }
        }

        /** {@code JapaneseCompletionFilter.Mode}. */
        enum JapaneseCompletionMode {
            INDEX,
            QUERY
        }

        /**
         * A {@code CustomAnalyzer}: char filters, one tokenizer, then token filters, each by its SPI
         * name.
         *
         * @param configDir the directory that resource files are read from before the classpath; empty
         *     for the classpath only
         */
        record Custom(
                List<Component> charFilters,
                Component tokenizer,
                List<Component> tokenFilters,
                Optional<Path> configDir)
                implements AnalyzerSpec {

            public Custom {
                charFilters = List.copyOf(charFilters);
                Objects.requireNonNull(tokenizer);
                tokenFilters = List.copyOf(tokenFilters);
                Objects.requireNonNull(configDir);
            }
        }

        /**
         * An analyzer wrapped by Lucene's {@code ShingleAnalyzerWrapper}, then by its {@code
         * LimitTokenCountAnalyzer}; at least one of them is present.
         */
        record Wrapped(AnalyzerSpec analyzer, Optional<Shingles> shingles, Optional<LimitTokenCount> limitTokenCount)
                implements AnalyzerSpec {

            public Wrapped {
                Objects.requireNonNull(analyzer);
                Objects.requireNonNull(shingles);
                Objects.requireNonNull(limitTokenCount);
            }
        }

        /** {@code fallback}, and for each field of {@code fields} its own analyzer. */
        record PerField(AnalyzerSpec fallback, Map<String, AnalyzerSpec> fields) implements AnalyzerSpec {

            public PerField {
                Objects.requireNonNull(fallback);
                fields = Map.copyOf(fields);
            }
        }

        /**
         * {@code analyzer}, whose tokens of each field in {@code words} lose those words,
         * case-sensitive, after the analyzer's whole chain, as Lucene's {@code
         * QueryAutoStopWordAnalyzer} removes them (ADR 0039, ADR 0061). A field without an entry keeps
         * every token.
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
         * @param consumeAllTokens whether the analyzer's stream is read to its end after the last token;
         *     it changes no token, and a Lucene without it ignores it
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

    /** An analyzer the runtime can't build, as its Lucene lacks it; the message says what it has. */
    final class UnsupportedAnalyzerException extends Exception {
        private static final long serialVersionUID = 1L;

        public UnsupportedAnalyzerException(String message) {
            super(message);
        }
    }

    /**
     * An analyzer that is wrong on any runtime (ADR 0067): a bad component or parameter, a file that
     * can't be read, or a size or option out of range. The message is leia-core's.
     */
    final class InvalidAnalyzerException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidAnalyzerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A query that the classic query parser can't parse. */
    final class InvalidQueryException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A hit, with the stored values of the fields asked for.
     *
     * @param sortValues its value of each key of a {@link Scoring#search} sort; empty without a sort
     */
    record Hit(
            int docId,
            float score,
            List<Scoring.SortValue> sortValues,
            Map<String, List<RuntimeIndex.StoredValue>> fields) {

        /** A hit of a search without a sort. */
        public Hit(int docId, float score, Map<String, List<RuntimeIndex.StoredValue>> fields) {
            this(docId, score, List.of(), fields);
        }
    }

    /**
     * The documents that match a query, by document id of the whole index.
     *
     * @param query the parsed query
     * @param rewrittenQuery the query after rewriting
     */
    record Matches(BitSet docs, String query, String rewrittenQuery, long tookMillis) {}

    /**
     * How a document is scored.
     *
     * @param value the score: a {@code Float} where the runtime's Lucene gives a {@code float}
     */
    record Explanation(boolean match, Number value, String description, List<Explanation> details) {}

    /**
     * What a search found.
     *
     * @param totalHitsExact whether {@code totalHits} counts every hit; otherwise there are at least
     *     that many (ADR 0070)
     * @param likeTerms for a query of {@link MoreLikeThisSearch}, the terms it chose, best first (ADR
     *     0066); empty for any other query
     */
    record SearchResults(
            String query,
            String rewrittenQuery,
            long totalHits,
            boolean totalHitsExact,
            List<Hit> hits,
            long tookMillis,
            Optional<List<MoreLikeThisSearch.LikeTerm>> likeTerms) {

        /** What a search found, every hit counted. */
        public SearchResults(
                String query,
                String rewrittenQuery,
                long totalHits,
                List<Hit> hits,
                long tookMillis,
                Optional<List<MoreLikeThisSearch.LikeTerm>> likeTerms) {
            this(query, rewrittenQuery, totalHits, true, hits, tookMillis, likeTerms);
        }

        /** What a search with a query other than More-Like-This found, every hit counted. */
        public SearchResults(String query, String rewrittenQuery, long totalHits, List<Hit> hits, long tookMillis) {
            this(query, rewrittenQuery, totalHits, hits, tookMillis, Optional.empty());
        }
    }
}
