package io.github.mfuerstenau.leia.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableComponent;
import io.github.mfuerstenau.leia.protocol.analysis.CharFilterStep;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CollationDecomposition;
import io.github.mfuerstenau.leia.protocol.analysis.CollationStrength;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseCompletionMode;
import io.github.mfuerstenau.leia.protocol.analysis.JapaneseCompletionOptions;
import io.github.mfuerstenau.leia.protocol.analysis.LimitTokenCount;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.PresetOption;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StemDictionary;
import io.github.mfuerstenau.leia.protocol.analysis.Token;
import io.github.mfuerstenau.leia.protocol.analysis.TokenAttribute;
import io.github.mfuerstenau.leia.protocol.analysis.TokenStage;
import io.github.mfuerstenau.leia.protocol.analysis.WordSet;
import io.github.mfuerstenau.leia.protocol.analysis.WordSetSupport;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getAnalysisComponents} and {@code analyze}. Neither needs an open index. */
class AnalysisHandlersTest {

    private static final Analyzer STANDARD = new Analyzer(
            Optional.of(new StandardAnalyzer()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            List.of());

    @TempDir
    Path tmp;

    private final GetAnalysisComponentsHandler getComponents = new GetAnalysisComponentsHandler(new Runtimes());
    private final AnalyzeHandler analyze = new AnalyzeHandler(new Runtimes());

    @Test
    void listsTheComponents() throws Exception {
        GetAnalysisComponentsResult result = getComponents.handle(new GetAnalysisComponentsParams());

        assertThat(result.charFilters()).extracting(AvailableComponent::name).contains("htmlStrip");
        assertThat(result.tokenizers())
                .contains(new AvailableComponent(
                        "standard", "org.apache.lucene.analysis.standard.StandardTokenizerFactory"));
        assertThat(result.tokenFilters()).extracting(AvailableComponent::name).contains("lowercase", "stop");
        assertThat(result.analyzers())
                .contains(
                        new AvailableAnalyzer(
                                "EnglishAnalyzer",
                                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                                WordSetSupport.OPTIONAL,
                                WordSetSupport.OPTIONAL,
                                List.of()),
                        new AvailableAnalyzer(
                                "StopAnalyzer",
                                "org.apache.lucene.analysis.core.StopAnalyzer",
                                WordSetSupport.REQUIRED,
                                WordSetSupport.NONE,
                                List.of()));
    }

    @Test
    void analyzesWithAPreset() throws Exception {
        AnalyzeResult result = analyze.handle(new AnalyzeParams(
                preset("org.apache.lucene.analysis.en.EnglishAnalyzer"), "The running foxes", Optional.empty()));

        assertThat(result.charFilters()).isEmpty();
        assertThat(result.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.name()).isEqualTo("EnglishAnalyzer");
            assertThat(stage.tokens()).extracting(Token::term).containsExactly("run", "fox");
        });
        assertFails(
                () -> analyze.handle(new AnalyzeParams(preset("java.lang.Thread"), "x", Optional.empty())),
                ErrorCode.INVALID_ANALYZER);
        var both = new Analyzer(
                STANDARD.standard(),
                preset("java.lang.Thread").preset(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
        assertFails(() -> analyze.handle(new AnalyzeParams(both, "x", Optional.empty())), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void analyzesWithAPresetsWordSets() throws Exception {
        Files.writeString(tmp.resolve("stop.txt"), "# stop words\nfoxes\n");
        var english = preset(
                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                Optional.of(file(tmp.resolve("stop.txt").toString())),
                Optional.of(words("running")));

        AnalyzeResult result = analyze.handle(new AnalyzeParams(english, "The running foxes", Optional.empty()));

        assertThat(result.stages())
                .singleElement()
                .satisfies(stage ->
                        assertThat(stage.tokens()).extracting(Token::term).containsExactly("the", "running"));
    }

    @Test
    void analyzesWithWrappersAndPresetOptions() throws Exception {
        var standard = new PresetAnalyzer(
                "org.apache.lucene.analysis.standard.StandardAnalyzer",
                Optional.empty(),
                Optional.empty(),
                OptionalInt.of(3),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        var analyzer = new Analyzer(
                Optional.empty(),
                Optional.of(standard),
                Optional.empty(),
                Optional.of(new Shingles(2, 2, "+", false, false, "_")),
                Optional.of(new LimitTokenCount(1, false)),
                List.of(),
                List.of());

        AnalyzeResult result = analyze.handle(new AnalyzeParams(analyzer, "abcdef", Optional.empty()));

        assertThat(result.stages())
                .extracting(TokenStage::name)
                .containsExactly("StandardAnalyzer", "ShingleAnalyzerWrapper", "LimitTokenCountAnalyzer");
        assertThat(result.stages().get(0).tokens()).extracting(Token::term).containsExactly("abc", "def");
        assertThat(result.stages().get(2).tokens()).extracting(Token::term).containsExactly("abc+def");

        var badShingles = new Analyzer(
                STANDARD.standard(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new Shingles(1, 2, " ", true, false, "_")),
                Optional.empty(),
                List.of(),
                List.of());
        assertFails(
                () -> analyze.handle(new AnalyzeParams(badShingles, "x", Optional.empty())),
                ErrorCode.INVALID_ANALYZER);
    }

    @Test
    void checksAStemDictionary() throws Exception {
        String dutch = "org.apache.lucene.analysis.nl.DutchAnalyzer";
        var entries =
                stemOverride(dutch, new StemDictionary(Optional.of(Map.of("fietsen", "rijwiel")), Optional.empty()));
        assertThat(analyze.handle(new AnalyzeParams(entries, "de fietsen", Optional.empty()))
                        .stages()
                        .getFirst()
                        .tokens())
                .extracting(Token::term)
                .containsExactly("rijwiel");

        var both = stemOverride(dutch, new StemDictionary(Optional.of(Map.of()), Optional.of("stemdict.txt")));
        assertFails(() -> analyze.handle(new AnalyzeParams(both, "x", Optional.empty())), ErrorCode.INVALID_PARAMS);
        var neither = stemOverride(dutch, new StemDictionary(Optional.empty(), Optional.empty()));
        assertFails(() -> analyze.handle(new AnalyzeParams(neither, "x", Optional.empty())), ErrorCode.INVALID_PARAMS);
        var english = stemOverride(
                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                new StemDictionary(Optional.of(Map.of()), Optional.empty()));
        assertThatThrownBy(() -> analyze.handle(new AnalyzeParams(english, "x", Optional.empty())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ANALYZER))
                .hasMessage("Invalid analyzer: EnglishAnalyzer takes no stem overrides");
    }

    @Test
    void checksAPresetsWordSets() throws Exception {
        String english = "org.apache.lucene.analysis.en.EnglishAnalyzer";
        var both = new WordSet(Optional.of(List.of("a")), Optional.of("stop.txt"));
        assertFails(
                () -> analyze.handle(
                        new AnalyzeParams(preset(english, Optional.of(both), Optional.empty()), "x", Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        var neither = new WordSet(Optional.empty(), Optional.empty());
        assertFails(
                () -> analyze.handle(new AnalyzeParams(
                        preset(english, Optional.empty(), Optional.of(neither)), "x", Optional.empty())),
                ErrorCode.INVALID_PARAMS);

        var keyword =
                preset("org.apache.lucene.analysis.core.KeywordAnalyzer", Optional.of(words("a")), Optional.empty());
        assertThatThrownBy(() -> analyze.handle(new AnalyzeParams(keyword, "x", Optional.empty())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ANALYZER))
                .hasMessage("Invalid analyzer: KeywordAnalyzer takes no stop words");
        var missing = preset(english, Optional.of(file(tmp.resolve("none.txt").toString())), Optional.empty());
        assertFails(
                () -> analyze.handle(new AnalyzeParams(missing, "x", Optional.empty())), ErrorCode.INVALID_ANALYZER);
        var badPath = preset(english, Optional.of(file("bad\0path")), Optional.empty());
        assertFails(
                () -> analyze.handle(new AnalyzeParams(badPath, "x", Optional.empty())), ErrorCode.INVALID_ANALYZER);
    }

    @Test
    void analyzesWithTheStandardAnalyzer() throws Exception {
        AnalyzeResult result = analyze.handle(new AnalyzeParams(STANDARD, "Quick Fox", Optional.empty()));

        assertThat(result.charFilters()).isEmpty();
        assertThat(result.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.name()).isEqualTo("StandardAnalyzer");
            assertThat(stage.truncated()).isFalse();
            assertThat(stage.tokens().getFirst())
                    .isEqualTo(new Token(
                            "quick",
                            0,
                            1,
                            1,
                            0,
                            5,
                            "<ALPHANUM>",
                            List.of(
                                    new TokenAttribute("TermToBytesRefAttribute", "bytes", "[71 75 69 63 6b]"),
                                    new TokenAttribute("TermFrequencyAttribute", "termFrequency", "1"))));
        });
    }

    @Test
    void analyzesACustomAnalyzerStepByStep() throws Exception {
        Files.writeString(tmp.resolve("stop.txt"), "fox\n");
        var analyzer = custom(
                List.of(component("htmlStrip")),
                component("whitespace"),
                List.of(component("lowercase"), new AnalysisComponent("stop", Map.of("words", "stop.txt"))),
                Optional.of(tmp.toString()));

        AnalyzeResult result = analyze.handle(new AnalyzeParams(analyzer, "<i>Quick</i> FOX", Optional.empty()));

        assertThat(result.charFilters()).containsExactly(new CharFilterStep("htmlStrip", "Quick FOX"));
        assertThat(result.stages()).extracting(TokenStage::name).containsExactly("whitespace", "lowercase", "stop");
        assertThat(result.stages().get(1).tokens()).extracting(Token::term).containsExactly("quick", "fox");
        assertThat(result.stages().get(2).tokens()).extracting(Token::term).containsExactly("quick");
    }

    @Test
    void checksTheParams() throws Exception {
        assertFails(
                () -> analyze.handle(
                        new AnalyzeParams(STANDARD, "x".repeat(AnalyzeParams.MAX_TEXT_LENGTH + 1), Optional.empty())),
                ErrorCode.INVALID_PARAMS);
        assertThat(analyze.handle(new AnalyzeParams(
                                STANDARD, "x".repeat(AnalyzeParams.MAX_TEXT_LENGTH), Optional.empty()))
                        .stages())
                .isNotEmpty();

        var both = new Analyzer(
                STANDARD.standard(),
                Optional.empty(),
                custom(List.of(), component("whitespace"), List.of(), Optional.empty())
                        .custom(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
        assertFails(() -> analyze.handle(new AnalyzeParams(both, "x", Optional.empty())), ErrorCode.INVALID_PARAMS);
        assertFails(
                () -> analyze.handle(new AnalyzeParams(
                        new Analyzer(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                List.of(),
                                List.of()),
                        "x",
                        Optional.empty())),
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void failsForAnAnalyzerThatCantBeBuilt() throws Exception {
        var unknown = custom(List.of(), component("nope"), List.of(), Optional.empty());
        assertThatThrownBy(() -> analyze.handle(new AnalyzeParams(unknown, "x", Optional.empty())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ANALYZER))
                .hasMessage("Invalid analyzer: no tokenizer 'nope'");

        var noDir = custom(
                List.of(),
                component("whitespace"),
                List.of(),
                Optional.of(tmp.resolve("none").toString()));
        assertFails(() -> analyze.handle(new AnalyzeParams(noDir, "x", Optional.empty())), ErrorCode.INVALID_ANALYZER);

        var badParam = custom(
                List.of(),
                component("whitespace"),
                List.of(new AnalysisComponent("lowercase", Map.of("a", "b"))),
                Optional.empty());
        assertFails(
                () -> analyze.handle(new AnalyzeParams(badParam, "x", Optional.empty())), ErrorCode.INVALID_ANALYZER);
    }

    @Test
    void mapsACollation() throws Exception {
        String collationKey = "org.apache.lucene.collation.CollationKeyAnalyzer";
        assertThat(getComponents.handle(new GetAnalysisComponentsParams()).analyzers())
                .contains(new AvailableAnalyzer(
                        "CollationKeyAnalyzer",
                        collationKey,
                        WordSetSupport.NONE,
                        WordSetSupport.NONE,
                        List.of(PresetOption.COLLATION)));

        var primary = collation(
                collationKey,
                new Collation("de", Optional.of(CollationStrength.PRIMARY), Optional.of(CollationDecomposition.FULL)));
        assertThat(bytes(primary, "Äpfel")).isEqualTo(bytes(primary, "apfel"));

        var quaternary = collation(
                collationKey, new Collation("de", Optional.of(CollationStrength.QUATERNARY), Optional.empty()));
        assertThatThrownBy(() -> analyze.handle(new AnalyzeParams(quaternary, "x", Optional.empty())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ANALYZER))
                .hasMessage("Invalid analyzer: collation: the JDK's collator has no strength QUATERNARY");
        var english = collation(
                "org.apache.lucene.analysis.en.EnglishAnalyzer", new Collation("", Optional.empty(), Optional.empty()));
        assertFails(
                () -> analyze.handle(new AnalyzeParams(english, "x", Optional.empty())), ErrorCode.INVALID_ANALYZER);
        var completion = new Analyzer(
                Optional.empty(),
                Optional.of(new PresetAnalyzer(
                        collationKey,
                        Optional.empty(),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new JapaneseCompletionOptions(
                                Optional.empty(), Optional.of(JapaneseCompletionMode.QUERY))))),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
        assertThatThrownBy(() -> analyze.handle(new AnalyzeParams(completion, "x", Optional.empty())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ANALYZER))
                .hasMessage("Invalid analyzer: CollationKeyAnalyzer takes no Japanese completion options");
    }

    @Test
    void analyzesAFieldWithItsOwnAnalyzerAndStopWords() throws Exception {
        Analyzer whitespace = preset("org.apache.lucene.analysis.core.WhitespaceAnalyzer");
        var analyzer = perField(
                List.of(new FieldAnalyzer("raw", whitespace)),
                List.of(new FieldStopWords("body", List.of("the")), new FieldStopWords("raw", List.of("The"))));

        AnalyzeResult raw = analyze.handle(new AnalyzeParams(analyzer, "The Fox", Optional.of("raw")));
        assertThat(raw.stages()).extracting(TokenStage::name).containsExactly("WhitespaceAnalyzer", "AutoStopWords");
        assertThat(raw.stages().get(1).tokens()).extracting(Token::term).containsExactly("Fox");
        AnalyzeResult body = analyze.handle(new AnalyzeParams(analyzer, "The Fox", Optional.of("body")));
        assertThat(body.stages()).extracting(TokenStage::name).containsExactly("StandardAnalyzer", "AutoStopWords");
        assertThat(body.stages().get(1).tokens()).extracting(Token::term).containsExactly("fox");
        // No field: the default analyzer, without stop words.
        AnalyzeResult none = analyze.handle(new AnalyzeParams(analyzer, "The Fox", Optional.empty()));
        assertThat(none.stages()).extracting(TokenStage::name).containsExactly("StandardAnalyzer");
        assertThat(none.stages().get(0).tokens()).extracting(Token::term).containsExactly("the", "fox");
    }

    @Test
    void refusesFieldsNamedTwiceOrNested() {
        Analyzer whitespace = preset("org.apache.lucene.analysis.core.WhitespaceAnalyzer");
        Analyzer nested = perField(List.of(new FieldAnalyzer("a", whitespace)), List.of());
        Analyzer withStopWords = perField(List.of(), List.of(new FieldStopWords("a", List.of("x"))));
        for (Analyzer invalid : List.of(
                perField(List.of(new FieldAnalyzer("", whitespace)), List.of()),
                perField(List.of(new FieldAnalyzer("a", whitespace), new FieldAnalyzer("a", STANDARD)), List.of()),
                perField(List.of(new FieldAnalyzer("b", nested)), List.of()),
                perField(List.of(new FieldAnalyzer("b", withStopWords)), List.of()),
                perField(
                        List.of(),
                        List.of(new FieldStopWords("a", List.of()), new FieldStopWords("a", List.of("x")))))) {
            assertFails(
                    () -> analyze.handle(new AnalyzeParams(invalid, "x", Optional.empty())), ErrorCode.INVALID_PARAMS);
        }
        var badField = perField(List.of(new FieldAnalyzer("raw", preset("org.example.NoAnalyzer"))), List.of());
        assertThatThrownBy(() -> analyze.handle(new AnalyzeParams(badField, "x", Optional.empty())))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ANALYZER))
                .hasMessage("Invalid analyzer: field 'raw': no preset analyzer 'org.example.NoAnalyzer'");
    }

    /** The standard analyzer as the default, with these fields and stop words. */
    private static Analyzer perField(List<FieldAnalyzer> fields, List<FieldStopWords> stopWords) {
        return new Analyzer(
                STANDARD.standard(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                fields,
                stopWords);
    }

    private static Analyzer collation(String className, Collation collation) {
        return new Analyzer(
                Optional.empty(),
                Optional.of(new PresetAnalyzer(
                        className,
                        Optional.empty(),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(collation),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    /** The collation key bytes of the first token. */
    private String bytes(Analyzer analyzer, String text) throws Exception {
        return analyze
                .handle(new AnalyzeParams(analyzer, text, Optional.empty()))
                .stages()
                .getFirst()
                .tokens()
                .getFirst()
                .attributes()
                .stream()
                .filter(attribute -> attribute.key().equals("bytes"))
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static Analyzer preset(String className) {
        return preset(className, Optional.empty(), Optional.empty());
    }

    private static Analyzer preset(String className, Optional<WordSet> stopwords, Optional<WordSet> stemExclusion) {
        return new Analyzer(
                Optional.empty(),
                Optional.of(new PresetAnalyzer(
                        className,
                        stopwords,
                        stemExclusion,
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    private static Analyzer stemOverride(String className, StemDictionary dictionary) {
        return new Analyzer(
                Optional.empty(),
                Optional.of(new PresetAnalyzer(
                        className,
                        Optional.empty(),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.of(dictionary),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    private static WordSet words(String... words) {
        return new WordSet(Optional.of(List.of(words)), Optional.empty());
    }

    private static WordSet file(String path) {
        return new WordSet(Optional.empty(), Optional.of(path));
    }

    private static AnalysisComponent component(String name) {
        return new AnalysisComponent(name, Map.of());
    }

    private static Analyzer custom(
            List<AnalysisComponent> charFilters,
            AnalysisComponent tokenizer,
            List<AnalysisComponent> tokenFilters,
            Optional<String> configDir) {
        return new Analyzer(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new CustomAnalyzer(charFilters, tokenizer, tokenFilters, configDir)),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
