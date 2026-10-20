package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.AnalysisComponents.AvailableAnalyzer;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Japanese;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.JapaneseCompletion;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.JapaneseCompletionMode;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.JapaneseMode;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Korean;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.KoreanDecompoundMode;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.WordSet;
import io.github.mfuerstenau.leia.core.TextAnalysis.Token;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.lucene.analysis.Analyzer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The options of Kuromoji's {@code JapaneseAnalyzer} and Nori's {@code KoreanAnalyzer} (ADR 0023)
 * and of Kuromoji's {@code JapaneseCompletionAnalyzer} (ADR 0038),
 * with the modules loaded as analysis jars, so this runs in the {@code jarsTest} JVM.
 */
@Tag("jars")
class KuromojiNoriTest {

    private static final Path JARS = Path.of(System.getProperty("leia.test.analysis.jars"));

    private static final String JAPANESE = "org.apache.lucene.analysis.ja.JapaneseAnalyzer";
    private static final String KOREAN = "org.apache.lucene.analysis.ko.KoreanAnalyzer";
    private static final String JAPANESE_COMPLETION = "org.apache.lucene.analysis.ja.JapaneseCompletionAnalyzer";

    @TempDir
    Path tmp;

    @BeforeAll
    static void loadTheModules() throws Exception {
        TestJars.load(List.of(JARS.resolve("kuromoji"), JARS.resolve("nori")));
    }

    @Test
    void listsTheirOptions() {
        assertThat(Analyzers.components().analyzers())
                .contains(
                        // Stop words only through the constructor with the options.
                        new AvailableAnalyzer(
                                "JapaneseAnalyzer",
                                JAPANESE,
                                WordSetSupport.OPTIONAL,
                                WordSetSupport.NONE,
                                List.of(PresetOption.JAPANESE)),
                        new AvailableAnalyzer(
                                "KoreanAnalyzer",
                                KOREAN,
                                WordSetSupport.NONE,
                                WordSetSupport.NONE,
                                List.of(PresetOption.KOREAN)),
                        new AvailableAnalyzer(
                                "JapaneseCompletionAnalyzer",
                                JAPANESE_COMPLETION,
                                WordSetSupport.NONE,
                                WordSetSupport.NONE,
                                List.of(PresetOption.JAPANESE_COMPLETION)));
    }

    @Test
    void japaneseCompletionTakesTheModeAndAUserDictionary() throws Exception {
        String text = "東京t";
        // INDEX: each token and its romanization.
        assertThat(terms(japaneseCompletion(Optional.empty()), text)).containsExactly("東京", "toukyou", "t");
        assertThat(terms(
                        japaneseCompletion(Optional.of(
                                new JapaneseCompletion(Optional.empty(), Optional.of(JapaneseCompletionMode.QUERY)))),
                        text))
                .containsExactly("東京t", "toukyout");

        Path dictionary = Files.writeString(tmp.resolve("userdict_ja.txt"), "関西国際空港,関西 国際 空港,カンサイ コクサイ クウコウ,カスタム名詞\n");
        var withDictionary = new JapaneseCompletion(Optional.of(dictionary), Optional.empty());
        // The dictionary splits the compound, each part with its readings.
        assertThat(terms(japaneseCompletion(Optional.of(withDictionary)), "関西国際空港"))
                .containsExactly("関西", "kansai", "kannsai", "国際", "kokusai", "空港", "kuukou");
        assertThat(terms(japaneseCompletion(Optional.empty()), "関西国際空港")).doesNotContain("関西");
    }

    @Test
    void japaneseTakesTheModeAndStopTags() throws Exception {
        String text = "関西国際空港に行った";
        // SEARCH splits the compound (and drops it); the particle and the auxiliary are stop tags.
        assertThat(terms(japanese(Optional.empty(), Optional.empty()), text)).containsExactly("関西", "国際", "空港", "行く");
        assertThat(terms(japanese(Optional.empty(), Optional.of(japaneseOptions(JapaneseMode.NORMAL, null))), text))
                .containsExactly("関西国際空港", "行く");
        // No stop words and no stop tags.
        assertThat(terms(
                        japanese(Optional.of(words()), Optional.of(japaneseOptions(JapaneseMode.NORMAL, words()))),
                        text))
                .containsExactly("関西国際空港", "に", "行く", "た");
        // Stop words alone call the constructor with the options too.
        assertThat(terms(japanese(Optional.of(words("行く")), Optional.empty()), text))
                .containsExactly("関西", "国際", "空港");
    }

    @Test
    void japaneseReadsAUserDictionary() throws Exception {
        Path dictionary = Files.writeString(
                tmp.resolve("userdict_ja.txt"), "# Kuromoji's format\n関西国際空港,関西 国際 空港,カンサイ コクサイ クウコウ,カスタム名詞\n");
        var options = new Japanese(Optional.of(dictionary), Optional.of(JapaneseMode.NORMAL), Optional.empty());

        assertThat(terms(japanese(Optional.empty(), Optional.of(options)), "関西国際空港"))
                .containsExactly("関西", "国際", "空港");

        Path broken = Files.writeString(tmp.resolve("broken.txt"), "関西国際空港,関西 国際,カンサイ コクサイ クウコウ,カスタム名詞\n");
        var brokenOptions = new Japanese(Optional.of(broken), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> Analyzers.build(japanese(Optional.empty(), Optional.of(brokenOptions))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessageStartingWith("user dictionary: " + broken + ": ");
        Path missing = tmp.resolve("missing.txt");
        var missingOptions = new Japanese(Optional.of(missing), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> Analyzers.build(japanese(Optional.empty(), Optional.of(missingOptions))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("user dictionary: no file " + missing);
    }

    @Test
    void koreanTakesTheDecompoundModeAndStopTags() throws Exception {
        assertThat(terms(korean(koreanOptions(KoreanDecompoundMode.NONE, null)), "백두산"))
                .containsExactly("백두산");
        assertThat(terms(korean(koreanOptions(KoreanDecompoundMode.DISCARD, null)), "백두산"))
                .containsExactly("백두", "산");
        assertThat(terms(korean(koreanOptions(KoreanDecompoundMode.MIXED, null)), "백두산"))
                .containsExactly("백두산", "백두", "산");
        // The particle is a default stop tag (JX).
        assertThat(terms(korean(koreanOptions(null, null)), "한국은")).containsExactly("한국");
        assertThat(terms(korean(koreanOptions(null, words())), "한국은")).containsExactly("한국", "은");
        assertThat(terms(korean(koreanOptions(null, words("NNP"))), "한국은")).containsExactly("은");

        assertThatThrownBy(() -> Analyzers.build(korean(koreanOptions(null, words("JX", "NOPE")))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("stop tags: no part of speech 'NOPE'");
    }

    @Test
    void koreanReadsAUserDictionaryAndSplitsUnknownWords() throws Exception {
        Path dictionary = Files.writeString(tmp.resolve("userdict_ko.txt"), "# Nori's format\n세종시 세종 시\n");
        var withDictionary = new Korean(Optional.of(dictionary), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(terms(korean(withDictionary), "세종시")).containsExactly("세종", "시");

        var unigrams = new Korean(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(true));
        assertThat(terms(korean(unigrams), "𐌰𐌱")).containsExactly("𐌰", "𐌱");
    }

    @Test
    void theirOptionsAreOnlyForThem() {
        var english = new AnalyzerSpec.Preset(
                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.of(japaneseOptions(null, null)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        assertThatThrownBy(() -> Analyzers.build(english))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("EnglishAnalyzer takes no Japanese options");
        var japaneseWithKorean = new AnalyzerSpec.Preset(
                JAPANESE,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(koreanOptions(null, null)),
                Optional.empty(),
                Optional.empty());
        assertThatThrownBy(() -> Analyzers.build(japaneseWithKorean))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("JapaneseAnalyzer takes no Korean options");
        var japaneseWithCompletion = new AnalyzerSpec.Preset(
                JAPANESE,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new JapaneseCompletion(Optional.empty(), Optional.empty())));
        assertThatThrownBy(() -> Analyzers.build(japaneseWithCompletion))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("JapaneseAnalyzer takes no Japanese completion options");
        assertThatThrownBy(
                        () -> Analyzers.build(new AnalyzerSpec.Preset(KOREAN, Optional.of(words()), Optional.empty())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("KoreanAnalyzer takes no stop words");
    }

    private static AnalyzerSpec.Preset japanese(Optional<WordSet> stopwords, Optional<Japanese> options) {
        return new AnalyzerSpec.Preset(
                JAPANESE,
                stopwords,
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                options,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AnalyzerSpec.Preset japaneseCompletion(Optional<JapaneseCompletion> options) {
        return new AnalyzerSpec.Preset(
                JAPANESE_COMPLETION,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                options);
    }

    private static Japanese japaneseOptions(JapaneseMode mode, WordSet stopTags) {
        return new Japanese(Optional.empty(), Optional.ofNullable(mode), Optional.ofNullable(stopTags));
    }

    private static AnalyzerSpec.Preset korean(Korean options) {
        return new AnalyzerSpec.Preset(
                KOREAN,
                Optional.empty(),
                Optional.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(options),
                Optional.empty(),
                Optional.empty());
    }

    private static Korean koreanOptions(KoreanDecompoundMode mode, WordSet stopTags) {
        return new Korean(Optional.empty(), Optional.ofNullable(mode), Optional.ofNullable(stopTags), Optional.empty());
    }

    private static WordSet words(String... words) {
        return new WordSet.Words(List.of(words));
    }

    private static List<String> terms(AnalyzerSpec spec, String text) throws Exception {
        try (Analyzer analyzer = Analyzers.build(spec)) {
            return Analyzers.analyze(analyzer, text, 100).stages().getFirst().tokens().stream()
                    .map(Token::term)
                    .toList();
        }
    }
}
