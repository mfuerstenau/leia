package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.AnalysisComponents.Available;
import io.github.mfuerstenau.leia.core.AnalysisComponents.AvailableAnalyzer;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Collation;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.CollationDecomposition;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.CollationStrength;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Component;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.LimitTokenCount;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Shingles;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.StemDictionary;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.WordSet;
import io.github.mfuerstenau.leia.core.TextAnalysis.CharFilterStep;
import io.github.mfuerstenau.leia.core.TextAnalysis.Token;
import io.github.mfuerstenau.leia.core.TextAnalysis.TokenAttribute;
import io.github.mfuerstenau.leia.core.TextAnalysis.TokenStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzersTest {

    private static final String COLLATION = "org.apache.lucene.collation.CollationKeyAnalyzer";

    @TempDir
    Path tmp;

    @Test
    void listsTheComponentsSortedByNameIgnoringCase() {
        AnalysisComponents components = Analyzers.components();

        assertThat(components.charFilters())
                .contains(
                        new Available("htmlStrip", "org.apache.lucene.analysis.charfilter.HTMLStripCharFilterFactory"));
        assertThat(components.tokenizers())
                .contains(new Available("standard", "org.apache.lucene.analysis.standard.StandardTokenizerFactory"))
                .contains(new Available("whitespace", "org.apache.lucene.analysis.core.WhitespaceTokenizerFactory"));
        assertThat(components.tokenFilters())
                .contains(new Available("lowercase", "org.apache.lucene.analysis.core.LowerCaseFilterFactory"));
        assertThat(components.tokenFilters())
                .extracting(Available::name)
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void theStandardAnalyzerIsOneStage() throws Exception {
        TextAnalysis analysis = analyze(new AnalyzerSpec.Standard(), "The Quick fox", 100);

        assertThat(analysis.charFilters()).isEmpty();
        assertThat(analysis.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.name()).isEqualTo("StandardAnalyzer");
            assertThat(stage.truncated()).isFalse();
            assertThat(stage.tokens()).extracting(Token::term).containsExactly("the", "quick", "fox");
            assertThat(stage.tokens()).extracting(Token::position).containsExactly(0, 1, 2);
            Token quick = stage.tokens().get(1);
            assertThat(quick.startOffset()).isEqualTo(4);
            assertThat(quick.endOffset()).isEqualTo(9);
            assertThat(quick.positionIncrement()).isEqualTo(1);
            assertThat(quick.positionLength()).isEqualTo(1);
            assertThat(quick.type()).isEqualTo("<ALPHANUM>");
        });
    }

    @Test
    void aCustomAnalyzerIsShownStepByStep() throws Exception {
        var spec = custom(
                List.of(new Component("htmlStrip", Map.of())),
                new Component("standard", Map.of()),
                List.of(new Component("lowercase", Map.of()), new Component("stop", Map.of())));

        TextAnalysis analysis = analyze(spec, "<b>The</b> Fox and THE dog", 100);

        assertThat(analysis.charFilters()).containsExactly(new CharFilterStep("htmlStrip", "The Fox and THE dog"));
        assertThat(analysis.stages()).extracting(TokenStage::name).containsExactly("standard", "lowercase", "stop");
        assertThat(analysis.stages().get(0).tokens())
                .extracting(Token::term)
                .containsExactly("The", "Fox", "and", "THE", "dog");
        assertThat(analysis.stages().get(1).tokens())
                .extracting(Token::term)
                .containsExactly("the", "fox", "and", "the", "dog");
        // The stop filter's default words are English: the gaps stay in the positions.
        List<Token> stopped = analysis.stages().get(2).tokens();
        assertThat(stopped).extracting(Token::term).containsExactly("fox", "dog");
        assertThat(stopped).extracting(Token::position).containsExactly(1, 4);
        assertThat(stopped).extracting(Token::positionIncrement).containsExactly(2, 3);
        // Offsets are into the original text, before the char filter took the tags out.
        assertThat(stopped.getFirst().startOffset()).isEqualTo(11);
        assertThat(stopped.getFirst().endOffset()).isEqualTo(14);
    }

    @Test
    void attributesWithoutAFieldOfTheirOwnAreListed() throws Exception {
        var spec = custom(
                List.of(), new Component("whitespace", Map.of()), List.of(new Component("keywordRepeat", Map.of())));

        List<Token> tokens = analyze(spec, "fox", 100).stages().get(1).tokens();

        assertThat(tokens).hasSize(2);
        assertThat(tokens.getFirst().attributes())
                .contains(new TokenAttribute("KeywordAttribute", "keyword", "true"))
                .contains(new TokenAttribute("TermToBytesRefAttribute", "bytes", "[66 6f 78]"))
                .extracting(TokenAttribute::attribute)
                .doesNotContain("CharTermAttribute", "OffsetAttribute", "PositionIncrementAttribute", "TypeAttribute");
        assertThat(tokens.get(1).attributes()).contains(new TokenAttribute("KeywordAttribute", "keyword", "false"));
    }

    @Test
    void aStageStopsAtTheTokenLimit() throws Exception {
        TextAnalysis analysis = analyze(new AnalyzerSpec.Standard(), "a b c", 2);
        assertThat(analysis.stages().getFirst().tokens())
                .extracting(Token::term)
                .containsExactly("a", "b");
        assertThat(analysis.stages().getFirst().truncated()).isTrue();

        TextAnalysis exact = analyze(new AnalyzerSpec.Standard(), "a b", 2);
        assertThat(exact.stages().getFirst().truncated()).isFalse();

        assertThat(analyze(new AnalyzerSpec.Standard(), "", 2)
                        .stages()
                        .getFirst()
                        .tokens())
                .isEmpty();
    }

    @Test
    void readsResourceFilesFromTheConfigDirectory() throws Exception {
        Files.writeString(tmp.resolve("stop.txt"), "# comment\nfox\n");
        var spec = new AnalyzerSpec.Custom(
                List.of(),
                new Component("whitespace", Map.of()),
                List.of(new Component("stop", Map.of("words", "stop.txt"))),
                Optional.of(tmp));

        assertThat(analyze(spec, "the fox", 100).stages().get(1).tokens())
                .extracting(Token::term)
                .containsExactly("the");
    }

    @Test
    void anAnalyzerThatCantBeBuiltSaysWhichComponentFailed() {
        assertThatThrownBy(() -> Analyzers.build(custom(List.of(), new Component("nope", Map.of()), List.of())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("no tokenizer 'nope'");
        assertThatThrownBy(() -> Analyzers.build(custom(
                        List.of(),
                        new Component("standard", Map.of()),
                        List.of(new Component("lowercase", Map.of("foo", "bar"))))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("token filter 'lowercase': Unknown parameters: {foo=bar}");
        assertThatThrownBy(() -> Analyzers.build(
                        custom(List.of(), new Component("standard", Map.of("maxTokenLength", "x")), List.of())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessageStartingWith("tokenizer 'standard': ");
        assertThatThrownBy(() -> Analyzers.build(custom(
                        List.of(),
                        new Component("standard", Map.of()),
                        List.of(new Component("stop", Map.of("words", "missing.txt"))))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("token filter 'stop': Resource not found: missing.txt");
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Custom(
                        List.of(), new Component("standard", Map.of()), List.of(), Optional.of(tmp.resolve("none")))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessageStartingWith("config directory: ");
    }

    @Test
    void listsThePresetAnalyzersSortedBySimpleName() {
        List<AvailableAnalyzer> analyzers = Analyzers.components().analyzers();

        assertThat(analyzers)
                .contains(
                        new AvailableAnalyzer(
                                "EnglishAnalyzer",
                                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                                WordSetSupport.OPTIONAL,
                                WordSetSupport.OPTIONAL,
                                List.of()),
                        new AvailableAnalyzer(
                                "StandardAnalyzer",
                                "org.apache.lucene.analysis.standard.StandardAnalyzer",
                                WordSetSupport.OPTIONAL,
                                WordSetSupport.NONE,
                                List.of(PresetOption.MAX_TOKEN_LENGTH)),
                        new AvailableAnalyzer(
                                "WhitespaceAnalyzer",
                                "org.apache.lucene.analysis.core.WhitespaceAnalyzer",
                                WordSetSupport.NONE,
                                WordSetSupport.NONE,
                                List.of(PresetOption.MAX_TOKEN_LENGTH)),
                        // Only a stop words constructor (ADR 0017).
                        new AvailableAnalyzer(
                                "StopAnalyzer",
                                "org.apache.lucene.analysis.core.StopAnalyzer",
                                WordSetSupport.REQUIRED,
                                WordSetSupport.NONE,
                                List.of()),
                        // Not a StopwordAnalyzerBase: its default stop words are getDefaultStopSet().
                        new AvailableAnalyzer(
                                "DutchAnalyzer",
                                "org.apache.lucene.analysis.nl.DutchAnalyzer",
                                WordSetSupport.OPTIONAL,
                                WordSetSupport.OPTIONAL,
                                List.of(PresetOption.STEM_OVERRIDE)),
                        // Only a collator constructor (ADR 0038).
                        new AvailableAnalyzer(
                                "CollationKeyAnalyzer",
                                COLLATION,
                                WordSetSupport.NONE,
                                WordSetSupport.NONE,
                                List.of(PresetOption.COLLATION)));
        assertThat(analyzers)
                .extracting(AvailableAnalyzer::name)
                // Abstract, without a no-argument or stop words constructor, or built by a builder.
                .doesNotContain(
                        "Analyzer",
                        "StopwordAnalyzerBase",
                        "PerFieldAnalyzerWrapper",
                        "LimitTokenCountAnalyzer",
                        "CustomAnalyzer")
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void aPresetIsOneStageNamedByItsClass() throws Exception {
        TextAnalysis analysis = analyze(
                new AnalyzerSpec.Preset("org.apache.lucene.analysis.en.EnglishAnalyzer"), "The running foxes", 100);

        assertThat(analysis.charFilters()).isEmpty();
        assertThat(analysis.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.name()).isEqualTo("EnglishAnalyzer");
            // Stop words and the Porter stemmer.
            assertThat(stage.tokens()).extracting(Token::term).containsExactly("run", "fox");
            assertThat(stage.tokens()).extracting(Token::position).containsExactly(1, 2);
        });
    }

    @Test
    void onlyAListedClassIsAPreset() {
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Preset("java.lang.Thread")))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("no preset analyzer 'java.lang.Thread'");
        assertThatThrownBy(() -> Analyzers.build(
                        new AnalyzerSpec.Preset("org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper")))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessageStartingWith("no preset analyzer");
    }

    @Test
    void aPresetTakesStopWordsOfItsOwn() throws Exception {
        assertThat(terms(english(Optional.of(words("running")), Optional.empty()), "The running foxes"))
                .containsExactly("the", "fox");
        // No stop words at all.
        assertThat(terms(english(Optional.of(words()), Optional.empty()), "The running foxes"))
                .containsExactly("the", "run", "fox");
    }

    @Test
    void stemExclusionsKeepTheDefaultStopWords() throws Exception {
        assertThat(terms(english(Optional.empty(), Optional.of(words("running"))), "The running foxes"))
                .containsExactly("running", "fox");
        assertThat(terms(english(Optional.of(words()), Optional.of(words("running"))), "The running foxes"))
                .containsExactly("the", "running", "fox");
        // From the static getDefaultStopSet(): "de" is a Dutch stop word.
        var dutch = new AnalyzerSpec.Preset(
                "org.apache.lucene.analysis.nl.DutchAnalyzer", Optional.empty(), Optional.of(words("fietsen")));
        assertThat(terms(dutch, "de fietsen")).containsExactly("fietsen");
    }

    @Test
    void stopAnalyzerNeedsStopWords() throws Exception {
        String stop = "org.apache.lucene.analysis.core.StopAnalyzer";
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Preset(stop)))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("StopAnalyzer needs stop words");

        var spec = new AnalyzerSpec.Preset(stop, Optional.of(words("a")), Optional.empty());
        assertThat(terms(spec, "A Fox")).containsExactly("fox");
    }

    @Test
    void readsAWordListFile() throws Exception {
        Path file = tmp.resolve("stopwords.txt");
        Files.writeString(file, "# Lucene's word set format\n  running \n\nfoxes\n");

        var spec = english(Optional.of(new WordSet.File(file)), Optional.empty());
        assertThat(terms(spec, "The running foxes")).containsExactly("the");
    }

    @Test
    void aWordSetThatCantBeUsedFails() throws Exception {
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Preset(
                        "org.apache.lucene.analysis.core.KeywordAnalyzer", Optional.of(words()), Optional.empty())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("KeywordAnalyzer takes no stop words");
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Preset(
                        "org.apache.lucene.analysis.standard.StandardAnalyzer",
                        Optional.empty(),
                        Optional.of(words()))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("StandardAnalyzer takes no stem exclusions");
        Path missing = tmp.resolve("missing.txt");
        assertThatThrownBy(() -> Analyzers.build(english(Optional.of(new WordSet.File(missing)), Optional.empty())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("stop words: no file " + missing);
        Path latin1 = tmp.resolve("latin1.txt");
        Files.write(latin1, new byte[] {'s', (byte) 0xE9, '\n'});
        assertThatThrownBy(() -> Analyzers.build(english(Optional.empty(), Optional.of(new WordSet.File(latin1)))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("stem exclusions: " + latin1 + " is not UTF-8");
    }

    @Test
    void wrappersAreStagesAfterTheAnalyzer() throws Exception {
        var spec = new AnalyzerSpec.Wrapped(
                new AnalyzerSpec.Standard(), Optional.of(shingles(2, 2)), Optional.of(new LimitTokenCount(3, false)));

        TextAnalysis analysis = analyze(spec, "The quick brown fox", 100);

        assertThat(analysis.stages())
                .extracting(TokenStage::name)
                .containsExactly("StandardAnalyzer", "ShingleAnalyzerWrapper", "LimitTokenCountAnalyzer");
        assertThat(analysis.stages().get(1).tokens())
                .extracting(Token::term)
                .containsExactly("the", "the quick", "quick", "quick brown", "brown", "brown fox", "fox");
        assertThat(analysis.stages().get(2).tokens())
                .extracting(Token::term)
                .containsExactly("the", "the quick", "quick");
    }

    @Test
    void aWrappedCustomAnalyzerIsStillShownStepByStep() throws Exception {
        var spec = new AnalyzerSpec.Wrapped(
                custom(
                        List.of(new Component("htmlStrip", Map.of())),
                        new Component("whitespace", Map.of()),
                        List.of(new Component("lowercase", Map.of()))),
                Optional.of(new Shingles(3, 3, "+", false, false, "_")),
                Optional.empty());

        TextAnalysis analysis = analyze(spec, "<b>A</b> B C", 100);

        assertThat(analysis.charFilters()).extracting(CharFilterStep::name).containsExactly("htmlStrip");
        assertThat(analysis.stages())
                .extracting(TokenStage::name)
                .containsExactly("whitespace", "lowercase", "ShingleAnalyzerWrapper");
        assertThat(analysis.stages().getLast().tokens()).extracting(Token::term).containsExactly("a+b+c");
    }

    @Test
    void aWrappedAnalyzerSearchesLikeTheWrappers() throws Exception {
        var spec = new AnalyzerSpec.Wrapped(
                new AnalyzerSpec.Preset("org.apache.lucene.analysis.core.WhitespaceAnalyzer"),
                Optional.of(shingles(2, 2)),
                Optional.of(new LimitTokenCount(2, true)));

        try (Analyzer analyzer = Analyzers.build(spec);
                TokenStream stream = analyzer.tokenStream("body", "a b c")) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            List<String> terms = new ArrayList<>();
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
            assertThat(terms).containsExactly("a", "a b");
        }
    }

    @Test
    void wrappersMustBeInRange() {
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Wrapped(
                        new AnalyzerSpec.Standard(), Optional.of(shingles(1, 2)), Optional.empty())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("shingles: the minimum size must be at least 2, not 1");
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Wrapped(
                        new AnalyzerSpec.Standard(), Optional.of(shingles(3, 2)), Optional.empty())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("shingles: the maximum size must be at least the minimum size, 3, not 2");
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Wrapped(
                        new AnalyzerSpec.Standard(), Optional.empty(), Optional.of(new LimitTokenCount(0, false)))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("token limit: the maximum token count must be at least 1, not 0");
    }

    @Test
    void aPresetTakesTheLongestToken() throws Exception {
        // A setter, then WhitespaceAnalyzer's constructor; both split a longer token.
        assertThat(terms(maxTokenLength("org.apache.lucene.analysis.standard.StandardAnalyzer", 3), "abcdefg"))
                .containsExactly("abc", "def", "g");
        assertThat(terms(maxTokenLength("org.apache.lucene.analysis.core.WhitespaceAnalyzer", 3), "abcdefg"))
                .containsExactly("abc", "def", "g");
        // With stop words of its own.
        var standard = new AnalyzerSpec.Preset(
                "org.apache.lucene.analysis.standard.StandardAnalyzer",
                Optional.of(words("abc")),
                Optional.empty(),
                OptionalInt.of(3),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        assertThat(terms(standard, "abcdefg")).containsExactly("def", "g");

        assertThatThrownBy(() -> Analyzers.build(maxTokenLength("org.apache.lucene.analysis.en.EnglishAnalyzer", 3)))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("EnglishAnalyzer takes no max token length");
        assertThatThrownBy(() ->
                        Analyzers.build(maxTokenLength("org.apache.lucene.analysis.standard.StandardAnalyzer", 0)))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("max token length: must be from 1 to 1048576, not 0");
    }

    @Test
    void dutchTakesAStemOverrideDictionary() throws Exception {
        assertThat(terms(
                        dutch(Optional.empty(), new StemDictionary.Entries(Map.of("fietsen", "rijwiel"))),
                        "de fietsen"))
                .containsExactly("rijwiel");
        // Stem exclusions win: the keyword marker comes first.
        assertThat(terms(
                        dutch(Optional.of(words("fietsen")), new StemDictionary.Entries(Map.of("fietsen", "rijwiel"))),
                        "de fietsen"))
                .containsExactly("fietsen");

        Path file = tmp.resolve("stemdict.txt");
        Files.writeString(file, "# word<TAB>stem\n\nfietsen\trijwiel\n");
        assertThat(terms(dutch(Optional.empty(), new StemDictionary.File(file)), "de fietsen"))
                .containsExactly("rijwiel");

        Path broken = tmp.resolve("broken.txt");
        Files.writeString(broken, "fietsen rijwiel\n");
        assertThatThrownBy(() -> Analyzers.build(dutch(Optional.empty(), new StemDictionary.File(broken))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("stem overrides: " + broken + ": 'fietsen rijwiel' is not word<TAB>stem");
        assertThatThrownBy(() -> Analyzers.build(dutch(Optional.empty(), new StemDictionary.Entries(Map.of("a", "")))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("stem overrides: an empty word or stem in 'a' → ''");
        var english = new AnalyzerSpec.Preset(
                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.of(new StemDictionary.Entries(Map.of())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        assertThatThrownBy(() -> Analyzers.build(english))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("EnglishAnalyzer takes no stem overrides");
    }

    @Test
    void aCollationAnalyzerIndexesTheCollationKey() throws Exception {
        var german = new Collation("de", Optional.empty(), Optional.empty());
        Token token = analyze(collation(Optional.of(german)), "Äpfel", 100)
                .stages()
                .getFirst()
                .tokens()
                .getFirst();

        // The term is the text; the bytes the key of the JDK's German collator.
        assertThat(token.term()).isEqualTo("Äpfel");
        assertThat(token.attributes())
                .contains(new TokenAttribute("TermToBytesRefAttribute", "bytes", key(Locale.GERMAN, "Äpfel")));
    }

    @Test
    void aCollationAnalyzerTakesTheStrengthAndDecomposition() throws Exception {
        var primary = new Collation("de", Optional.of(CollationStrength.PRIMARY), Optional.empty());
        assertThat(bytes(collation(Optional.of(primary)), "Äpfel"))
                .isEqualTo(bytes(collation(Optional.of(primary)), "apfel"));
        var tertiary = new Collation("de", Optional.of(CollationStrength.TERTIARY), Optional.empty());
        assertThat(bytes(collation(Optional.of(tertiary)), "Äpfel"))
                .isNotEqualTo(bytes(collation(Optional.of(tertiary)), "apfel"));
        var full = new Collation("de", Optional.empty(), Optional.of(CollationDecomposition.FULL));
        Collator fullCollator = Collator.getInstance(Locale.GERMAN);
        fullCollator.setDecomposition(Collator.FULL_DECOMPOSITION);
        // The compatibility form of ﬁ is fi.
        assertThat(bytes(collation(Optional.of(full)), "ﬁ"))
                .isEqualTo(new BytesRef(fullCollator.getCollationKey("fi").toByteArray()).toString());
        // None given: the root locale.
        assertThat(bytes(collation(Optional.empty()), "Äpfel")).isEqualTo(key(Locale.ROOT, "Äpfel"));
    }

    @Test
    void aCollationThatCantBeUsedFails() {
        var quaternary = new Collation("de", Optional.of(CollationStrength.QUATERNARY), Optional.empty());
        assertThatThrownBy(() -> Analyzers.build(collation(Optional.of(quaternary))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("collation: the JDK's collator has no strength QUATERNARY");
        var illFormed = new Collation("not a tag", Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> Analyzers.build(collation(Optional.of(illFormed))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessageStartingWith("collation: 'not a tag' is not a language tag: ");
        var english = new AnalyzerSpec.Preset(
                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new Collation("de", Optional.empty(), Optional.empty())),
                Optional.empty());
        assertThatThrownBy(() -> Analyzers.build(english))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("EnglishAnalyzer takes no collation");
    }

    private static AnalyzerSpec.Preset collation(Optional<Collation> collation) {
        return new AnalyzerSpec.Preset(
                COLLATION,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                collation,
                Optional.empty());
    }

    /** The bytes of the first token, as the analysis shows them. */
    private static String bytes(AnalyzerSpec spec, String text) throws Exception {
        return analyze(spec, text, 100).stages().getFirst().tokens().getFirst().attributes().stream()
                .filter(attribute -> attribute.key().equals("bytes"))
                .findFirst()
                .orElseThrow()
                .value();
    }

    /** The JDK's collation key of {@code text}, as the analysis shows it. */
    private static String key(Locale locale, String text) {
        return new BytesRef(Collator.getInstance(locale).getCollationKey(text).toByteArray()).toString();
    }

    private static Shingles shingles(int min, int max) {
        return new Shingles(min, max, " ", true, false, "_");
    }

    private static AnalyzerSpec.Preset maxTokenLength(String className, int length) {
        return new AnalyzerSpec.Preset(
                className,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.of(length),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AnalyzerSpec.Preset dutch(Optional<WordSet> stemExclusion, StemDictionary stemOverride) {
        return new AnalyzerSpec.Preset(
                "org.apache.lucene.analysis.nl.DutchAnalyzer",
                Optional.empty(),
                stemExclusion,
                OptionalInt.empty(),
                Optional.of(stemOverride),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void componentNamesIgnoreCase() throws Exception {
        var spec =
                custom(List.of(), new Component("Whitespace", Map.of()), List.of(new Component("LowerCase", Map.of())));

        TextAnalysis analysis = analyze(spec, "Fox", 100);

        assertThat(analysis.stages()).extracting(TokenStage::name).containsExactly("whitespace", "lowercase");
    }

    private static AnalyzerSpec.Preset english(Optional<WordSet> stopwords, Optional<WordSet> stemExclusion) {
        return new AnalyzerSpec.Preset("org.apache.lucene.analysis.en.EnglishAnalyzer", stopwords, stemExclusion);
    }

    private static WordSet words(String... words) {
        return new WordSet.Words(List.of(words));
    }

    /** The terms of the single stage. */
    private static List<String> terms(AnalyzerSpec spec, String text) throws Exception {
        return analyze(spec, text, 100).stages().getFirst().tokens().stream()
                .map(Token::term)
                .toList();
    }

    private static AnalyzerSpec.Custom custom(
            List<Component> charFilters, Component tokenizer, List<Component> filters) {
        return new AnalyzerSpec.Custom(charFilters, tokenizer, filters, Optional.empty());
    }

    private static TextAnalysis analyze(AnalyzerSpec spec, String text, int maxTokens) throws Exception {
        try (Analyzer analyzer = Analyzers.build(spec)) {
            return Analyzers.analyze(analyzer, text, maxTokens);
        }
    }
}
