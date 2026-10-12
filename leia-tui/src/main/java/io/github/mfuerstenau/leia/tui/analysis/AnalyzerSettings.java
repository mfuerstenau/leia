package io.github.mfuerstenau.leia.tui.analysis;

import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseMode;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseOptions;
import io.github.mfuerstenau.leia.protocol.analysis.KoreanDecompoundMode;
import io.github.mfuerstenau.leia.protocol.analysis.KoreanOptions;
import io.github.mfuerstenau.leia.protocol.analysis.LimitTokenCount;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StemDictionary;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The current analyzer (ADR 0014): the analysis screen tries it, and the search screen parses
 * queries and analyzes More-Like-This text with it. It is Lucene's {@code StandardAnalyzer}, a preset
 * analyzer by class name (ADR 0015) with stop words and stem exclusions of its own if it takes them
 * (ADR 0017) and the options it takes (ADR 0023), or a custom analyzer of char filters, a tokenizer
 * and token filters, each with its parameters, and a config directory for resource files. Any of them
 * can be wrapped in shingles, then in a token limit (ADR 0023). {@link AnalyzerForm} builds it.
 *
 * <p>The preset and the custom chain are kept while another kind is chosen, so that switching back
 * finds them again. The form edits a {@linkplain #copy() copy}.
 */
public final class AnalyzerSettings {

    /** The preset that the popup starts with. */
    static final String DEFAULT_PRESET = "org.apache.lucene.analysis.en.EnglishAnalyzer";

    /** Kuromoji's and Nori's analyzers, which take options of their own (ADR 0023). */
    static final String JAPANESE_ANALYZER = "org.apache.lucene.analysis.ja.JapaneseAnalyzer";

    static final String KOREAN_ANALYZER = "org.apache.lucene.analysis.ko.KoreanAnalyzer";

    /** Which alternative of the analyzer is chosen. */
    enum Kind {
        STANDARD("standard"),
        PRESET("preset"),
        CUSTOM("custom");

        final String name;

        Kind(String name) {
            this.name = name;
        }

        /** The next kind, or the previous one. */
        Kind cycled(boolean forward) {
            return switch (this) {
                case STANDARD -> forward ? PRESET : CUSTOM;
                case PRESET -> forward ? CUSTOM : STANDARD;
                case CUSTOM -> forward ? STANDARD : PRESET;
            };
        }
    }

    /** A preset's word set (ADR 0017). */
    enum WordSetKind {
        STOPWORDS("Stop words", "stop words"),
        STEM_EXCLUSION("Stem exclusions", "stem exclusions");

        final String label;
        final String noun;

        WordSetKind(String label, String noun) {
            this.label = label;
            this.noun = noun;
        }
    }

    /** Kuromoji's or Nori's analyzer, for the options that both have (ADR 0023). */
    enum Language {
        JAPANESE,
        KOREAN
    }

    Kind kind = Kind.STANDARD;

    /** The preset's class name. */
    String preset = DEFAULT_PRESET;

    /** The preset's stop words; empty for its default ones. */
    Optional<WordSet> stopwords = Optional.empty();

    /** The preset's stem exclusions; empty for none. */
    Optional<WordSet> stemExclusion = Optional.empty();

    /** The preset's longest token; empty for its default. */
    OptionalInt maxTokenLength = OptionalInt.empty();

    /** The preset's stem overrides; empty for its default ones. */
    Optional<StemDictionary> stemOverride = Optional.empty();

    /** {@code JapaneseAnalyzer}'s options; sent only when one is set. */
    JapaneseOptions japanese = noJapanese();

    /** {@code KoreanAnalyzer}'s options; sent only when one is set. */
    KoreanOptions korean = noKorean();

    /** The custom chain: by default what the standard analyzer does, the standard tokenizer, then lowercase. */
    List<AnalysisComponent> charFilters = new ArrayList<>();

    AnalysisComponent tokenizer = component("standard");
    List<AnalysisComponent> tokenFilters = new ArrayList<>(List.of(component("lowercase")));
    Optional<String> configDir = Optional.empty();

    /** The wrappers of any kind; empty for none. */
    Optional<Shingles> shingles = Optional.empty();

    Optional<LimitTokenCount> limitTokenCount = Optional.empty();

    /** The standard analyzer; {@code EnglishAnalyzer} as the preset; the standard chain as the custom one. */
    public AnalyzerSettings() {}

    /** A copy that can be changed without changing this one. */
    public AnalyzerSettings copy() {
        AnalyzerSettings copy = new AnalyzerSettings();
        copy.kind = kind;
        copy.preset = preset;
        copy.stopwords = stopwords;
        copy.stemExclusion = stemExclusion;
        copy.maxTokenLength = maxTokenLength;
        copy.stemOverride = stemOverride;
        copy.japanese = japanese;
        copy.korean = korean;
        copy.charFilters = new ArrayList<>(charFilters);
        copy.tokenizer = tokenizer;
        copy.tokenFilters = new ArrayList<>(tokenFilters);
        copy.configDir = configDir;
        copy.shingles = shingles;
        copy.limitTokenCount = limitTokenCount;
        return copy;
    }

    static JapaneseOptions noJapanese() {
        return new JapaneseOptions(Optional.empty(), Optional.empty(), Optional.empty());
    }

    static KoreanOptions noKorean() {
        return new KoreanOptions(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** The analyzer as a request sends it. */
    public Analyzer analyzer() {
        Optional<StandardAnalyzer> standard = Optional.empty();
        Optional<PresetAnalyzer> presetAnalyzer = Optional.empty();
        Optional<CustomAnalyzer> custom = Optional.empty();
        switch (kind) {
            case STANDARD -> standard = Optional.of(new StandardAnalyzer());
            case PRESET ->
                presetAnalyzer = Optional.of(new PresetAnalyzer(
                        preset,
                        stopwords,
                        stemExclusion,
                        maxTokenLength,
                        stemOverride,
                        japanese.equals(noJapanese()) ? Optional.empty() : Optional.of(japanese),
                        korean.equals(noKorean()) ? Optional.empty() : Optional.of(korean)));
            case CUSTOM -> custom = Optional.of(chain());
        }
        return new Analyzer(standard, presetAnalyzer, custom, shingles, limitTokenCount);
    }

    /** The default analyzer as a request sends it: the standard one, without wrappers. */
    public static Analyzer standardAnalyzer() {
        return new AnalyzerSettings().analyzer();
    }

    CustomAnalyzer chain() {
        return new CustomAnalyzer(List.copyOf(charFilters), tokenizer, List.copyOf(tokenFilters), configDir);
    }

    /** Whether this is the default: the standard analyzer, without wrappers. */
    public boolean isStandard() {
        return kind == Kind.STANDARD && shingles.isEmpty() && limitTokenCount.isEmpty();
    }

    public boolean isCustom() {
        return kind == Kind.CUSTOM;
    }

    Optional<WordSet> wordSet(WordSetKind which) {
        return which == WordSetKind.STOPWORDS ? stopwords : stemExclusion;
    }

    void setWordSet(WordSetKind which, Optional<WordSet> set) {
        if (which == WordSetKind.STOPWORDS) {
            stopwords = set;
        } else {
            stemExclusion = set;
        }
    }

    Optional<String> userDictionary(Language language) {
        return language == Language.JAPANESE ? japanese.userDictionary() : korean.userDictionary();
    }

    void setUserDictionary(Language language, Optional<String> path) {
        if (language == Language.JAPANESE) {
            japanese = new JapaneseOptions(path, japanese.mode(), japanese.stopTags());
        } else {
            korean =
                    new KoreanOptions(path, korean.decompoundMode(), korean.stopTags(), korean.outputUnknownUnigrams());
        }
    }

    Optional<WordSet> stopTags(Language language) {
        return language == Language.JAPANESE ? japanese.stopTags() : korean.stopTags();
    }

    void setStopTags(Language language, Optional<WordSet> tags) {
        if (language == Language.JAPANESE) {
            japanese = new JapaneseOptions(japanese.userDictionary(), japanese.mode(), tags);
        } else {
            korean = new KoreanOptions(
                    korean.userDictionary(), korean.decompoundMode(), tags, korean.outputUnknownUnigrams());
        }
    }

    /**
     * The analyzer on one line: {@code standard analyzer}, the preset's simple class name with its
     * word sets and options, such as {@code EnglishAnalyzer · stop words none}, or the chain, such as
     * {@code htmlStrip › standard › lowercase › stop(ignoreCase=true)}, and its config directory; then
     * the wrappers, such as {@code · shingles 2–3 · at most 10000 tokens}.
     */
    public String text() {
        StringBuilder text = new StringBuilder(
                switch (kind) {
                    case STANDARD -> "standard analyzer";
                    case PRESET -> presetText();
                    case CUSTOM -> chainText();
                });
        shingles.ifPresent(s -> text.append(" · shingles ").append(shingleSizeText(s)));
        limitTokenCount.ifPresent(limit ->
                text.append(" · at most ").append(limit.maxTokenCount()).append(" tokens"));
        return text.toString();
    }

    private String presetText() {
        StringBuilder text = new StringBuilder(TextFormat.escapeControl(simpleName(preset)));
        for (WordSetKind which : WordSetKind.values()) {
            wordSet(which)
                    .ifPresent(set ->
                            text.append(" · ").append(which.noun).append(' ').append(wordSetText(set)));
        }
        maxTokenLength.ifPresent(length -> text.append(" · token length ").append(length));
        stemOverride.ifPresent(dictionary -> text.append(" · stem overrides ").append(stemDictionaryText(dictionary)));
        for (Language language : Language.values()) {
            userDictionary(language)
                    .ifPresent(path -> text.append(" · user dictionary ").append(TextFormat.escapeControl(path)));
            if (language == Language.JAPANESE) {
                japanese.mode().ifPresent(mode -> text.append(" · mode ").append(japaneseModeName(mode)));
            } else {
                korean.decompoundMode()
                        .ifPresent(mode -> text.append(" · decompound ").append(decompoundModeName(mode)));
            }
            stopTags(language).ifPresent(tags -> text.append(" · stop tags ").append(wordSetText(tags)));
        }
        korean.outputUnknownUnigrams()
                .ifPresent(split -> text.append(" · unknown words ").append(unknownWordsText(split)));
        return text.toString();
    }

    private String chainText() {
        String text = Stream.of(charFilters.stream(), Stream.of(tokenizer), tokenFilters.stream())
                .flatMap(s -> s)
                .map(AnalyzerSettings::componentText)
                .collect(Collectors.joining(" › "));
        return configDir
                .map(dir -> text + " · config " + TextFormat.escapeControl(dir))
                .orElse(text);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AnalyzerSettings that
                && kind == that.kind
                && preset.equals(that.preset)
                && stopwords.equals(that.stopwords)
                && stemExclusion.equals(that.stemExclusion)
                && maxTokenLength.equals(that.maxTokenLength)
                && stemOverride.equals(that.stemOverride)
                && japanese.equals(that.japanese)
                && korean.equals(that.korean)
                && chain().equals(that.chain())
                && shingles.equals(that.shingles)
                && limitTokenCount.equals(that.limitTokenCount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, preset, chain(), shingles, limitTokenCount);
    }

    @Override
    public String toString() {
        return text();
    }

    // The texts and parsers of the values, shared with the form.

    static WordSet words(List<String> words) {
        return new WordSet(Optional.of(List.copyOf(words)), Optional.empty());
    }

    static WordSet wordFile(String path) {
        return new WordSet(Optional.empty(), Optional.of(path));
    }

    /** The words, separated by commas; {@code none} for no words; or {@code file} and the path. */
    static String wordSetText(WordSet set) {
        if (set.words().isPresent()) {
            List<String> words = set.words().get();
            return words.isEmpty() ? "none" : TextFormat.escapeControl(String.join(", ", words));
        }
        return set.file().map(file -> "file " + TextFormat.escapeControl(file)).orElse("");
    }

    /** The text that edits a word set: the words, {@code -} for no words, {@code @} and the path for a file, or blank for none. */
    static String wordSetInput(Optional<WordSet> set) {
        if (set.isEmpty()) {
            return "";
        }
        if (set.get().words().isPresent()) {
            List<String> words = set.get().words().get();
            return words.isEmpty() ? "-" : String.join(", ", words);
        }
        return set.get().file().map(file -> "@" + file).orElse("");
    }

    /** Splits at commas and white space, as Rust's {@code split(|c| c == ',' || c.is_whitespace())}. */
    private static List<String> items(String text) {
        List<String> items = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        text.codePoints().forEach(c -> {
            if (c == ',' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!item.isEmpty()) {
                    items.add(item.toString());
                    item.setLength(0);
                }
            } else {
                item.appendCodePoint(c);
            }
        });
        if (!item.isEmpty()) {
            items.add(item.toString());
        }
        return items;
    }

    /**
     * A word set from its text (see {@link #wordSetInput}): words are separated by commas or white
     * space. Empty for a blank text.
     *
     * @throws IllegalArgumentException with the message to show
     */
    static Optional<WordSet> parseWordSet(String typed) {
        String text = typed.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.equals("-")) {
            return Optional.of(words(List.of()));
        }
        if (text.startsWith("@")) {
            String path = text.substring(1).strip();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("Write the word list file's path after @.");
            }
            return Optional.of(wordFile(path));
        }
        return Optional.of(words(items(text)));
    }

    /** The overrides as {@code word → stem}, separated by commas; {@code none} for none; or {@code file} and the path. */
    static String stemDictionaryText(StemDictionary dictionary) {
        if (dictionary.entries().isPresent()) {
            Map<String, String> entries = dictionary.entries().get();
            if (entries.isEmpty()) {
                return "none";
            }
            return new TreeMap<>(entries)
                    .entrySet().stream()
                            .map(entry -> TextFormat.escapeControl(entry.getKey()) + " → "
                                    + TextFormat.escapeControl(entry.getValue()))
                            .collect(Collectors.joining(", "));
        }
        return dictionary
                .file()
                .map(file -> "file " + TextFormat.escapeControl(file))
                .orElse("");
    }

    /** The text that edits stem overrides: {@code word=stem} pairs, {@code -} for none, {@code @} and the path for a file, or blank for the default. */
    static String stemDictionaryInput(Optional<StemDictionary> dictionary) {
        if (dictionary.isEmpty()) {
            return "";
        }
        if (dictionary.get().entries().isPresent()) {
            Map<String, String> entries = dictionary.get().entries().get();
            if (entries.isEmpty()) {
                return "-";
            }
            return new TreeMap<>(entries)
                    .entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(Collectors.joining(", "));
        }
        return dictionary.get().file().map(file -> "@" + file).orElse("");
    }

    /**
     * Stem overrides from their text (see {@link #stemDictionaryInput}): pairs are separated by commas
     * or white space.
     *
     * @throws IllegalArgumentException with the message to show
     */
    static Optional<StemDictionary> parseStemDictionary(String typed) {
        String text = typed.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.equals("-")) {
            return Optional.of(new StemDictionary(Optional.of(new TreeMap<>()), Optional.empty()));
        }
        if (text.startsWith("@")) {
            String path = text.substring(1).strip();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("Write the dictionary file's path after @.");
            }
            return Optional.of(new StemDictionary(Optional.empty(), Optional.of(path)));
        }
        TreeMap<String, String> entries = new TreeMap<>();
        for (String pair : items(text)) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals == pair.length() - 1) {
                throw new IllegalArgumentException("Write each override as word=stem.");
            }
            entries.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return Optional.of(new StemDictionary(Optional.of(entries), Optional.empty()));
    }

    /**
     * A whole number from {@code min} to {@code max}; empty for a blank text.
     *
     * @throws IllegalArgumentException with the message to show
     */
    static OptionalInt parseNumber(String text, int min, int max) {
        if (text.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int number = Integer.parseInt(text);
            if (number >= min && number <= max) {
                return OptionalInt.of(number);
            }
        } catch (NumberFormatException e) {
            // Below.
        }
        throw new IllegalArgumentException("Write a whole number from " + min + " to " + max + ".");
    }

    /**
     * The shingle size as {@code min-max}, or one number if they are the same; empty for a blank
     * text. Returns the two sizes.
     *
     * @throws IllegalArgumentException with the message to show
     */
    static Optional<int[]> parseShingleSize(String text) {
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String error = "Write the size as min-max, such as 2-3, both at least 2.";
        int dash = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '-' || text.charAt(i) == '–') {
                dash = i;
                break;
            }
        }
        String minText = dash < 0 ? text : text.substring(0, dash).strip();
        String maxText = dash < 0 ? text : text.substring(dash + 1).strip();
        OptionalInt min;
        OptionalInt max;
        try {
            min = parseNumber(minText, 2, Integer.MAX_VALUE);
            max = parseNumber(maxText, 2, Integer.MAX_VALUE);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(error, e);
        }
        if (min.isPresent() && max.isPresent() && min.getAsInt() <= max.getAsInt()) {
            return Optional.of(new int[] {min.getAsInt(), max.getAsInt()});
        }
        throw new IllegalArgumentException(error);
    }

    /** {@code 2}, or {@code 2–3} for a range. */
    static String shingleSizeText(Shingles shingles) {
        return shingles.minShingleSize() == shingles.maxShingleSize()
                ? Integer.toString(shingles.minShingleSize())
                : shingles.minShingleSize() + "–" + shingles.maxShingleSize();
    }

    /** When the shingles keep the tokens themselves. */
    static String unigramsText(Shingles shingles) {
        if (shingles.outputUnigrams()) {
            return "yes";
        }
        return shingles.outputUnigramsIfNoShingles() ? "only if there are no shingles" : "no";
    }

    /** A text as typed, in quotes, so that blanks show. */
    static String quoted(String text) {
        return "\"" + TextFormat.escapeControl(text) + "\"";
    }

    static String japaneseModeName(JapaneseMode mode) {
        return switch (mode) {
            case NORMAL -> "normal";
            case SEARCH -> "search";
            case EXTENDED -> "extended";
        };
    }

    static String decompoundModeName(KoreanDecompoundMode mode) {
        return switch (mode) {
            case NONE -> "none";
            case DISCARD -> "discard";
            case MIXED -> "mixed";
        };
    }

    static String unknownWordsText(boolean split) {
        return split ? "split into unigrams" : "kept whole";
    }

    /** The class name without its package. */
    static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** The package of a class name; empty for none. */
    static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    static AnalysisComponent component(String name) {
        return new AnalysisComponent(name, new TreeMap<>());
    }

    /** {@code name}, or {@code name(key=value, …)} with its parameters. */
    static String componentText(AnalysisComponent component) {
        String name = TextFormat.escapeControl(component.name());
        if (component.params().isEmpty()) {
            return name;
        }
        return name
                + new TreeMap<>(component.params())
                        .entrySet().stream()
                                .map(entry -> TextFormat.escapeControl(entry.getKey()) + "="
                                        + TextFormat.escapeControl(entry.getValue()))
                                .collect(Collectors.joining(", ", "(", ")"));
    }

    /** The value after {@code current} in {@code values}, or the one before it, round and round. */
    static <T> T cycled(T current, boolean forward, List<T> values) {
        int index = Math.max(values.indexOf(current), 0);
        int len = values.size();
        return values.get(forward ? (index + 1) % len : (index + len - 1) % len);
    }
}
