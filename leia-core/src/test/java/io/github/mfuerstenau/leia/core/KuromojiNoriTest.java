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
import org.apache.lucene.analysis.Analyzer;
import org.jspecify.annotations.Nullable;
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
        assertThat(terms(japaneseCompletion(null), text)).containsExactly("東京", "toukyou", "t");
        assertThat(terms(japaneseCompletion(new JapaneseCompletion(null, JapaneseCompletionMode.QUERY)), text))
                .containsExactly("東京t", "toukyout");

        Path dictionary = Files.writeString(tmp.resolve("userdict_ja.txt"), "関西国際空港,関西 国際 空港,カンサイ コクサイ クウコウ,カスタム名詞\n");
        var withDictionary = new JapaneseCompletion(dictionary, null);
        // The dictionary splits the compound, each part with its readings.
        assertThat(terms(japaneseCompletion(withDictionary), "関西国際空港"))
                .containsExactly("関西", "kansai", "kannsai", "国際", "kokusai", "空港", "kuukou");
        assertThat(terms(japaneseCompletion(null), "関西国際空港")).doesNotContain("関西");
    }

    @Test
    void japaneseTakesTheModeAndStopTags() throws Exception {
        String text = "関西国際空港に行った";
        // SEARCH splits the compound (and drops it); the particle and the auxiliary are stop tags.
        assertThat(terms(japanese(null, null), text)).containsExactly("関西", "国際", "空港", "行く");
        assertThat(terms(japanese(null, japaneseOptions(JapaneseMode.NORMAL, null)), text))
                .containsExactly("関西国際空港", "行く");
        // No stop words and no stop tags.
        assertThat(terms(japanese(words(), japaneseOptions(JapaneseMode.NORMAL, words())), text))
                .containsExactly("関西国際空港", "に", "行く", "た");
        // Stop words alone call the constructor with the options too.
        assertThat(terms(japanese(words("行く"), null), text)).containsExactly("関西", "国際", "空港");
    }

    @Test
    void japaneseReadsAUserDictionary() throws Exception {
        Path dictionary = Files.writeString(
                tmp.resolve("userdict_ja.txt"), "# Kuromoji's format\n関西国際空港,関西 国際 空港,カンサイ コクサイ クウコウ,カスタム名詞\n");
        var options = new Japanese(dictionary, JapaneseMode.NORMAL, null);

        assertThat(terms(japanese(null, options), "関西国際空港")).containsExactly("関西", "国際", "空港");

        Path broken = Files.writeString(tmp.resolve("broken.txt"), "関西国際空港,関西 国際,カンサイ コクサイ クウコウ,カスタム名詞\n");
        var brokenOptions = new Japanese(broken, null, null);
        assertThatThrownBy(() -> Analyzers.build(japanese(null, brokenOptions)))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessageStartingWith("user dictionary: " + broken + ": ");
        Path missing = tmp.resolve("missing.txt");
        var missingOptions = new Japanese(missing, null, null);
        assertThatThrownBy(() -> Analyzers.build(japanese(null, missingOptions)))
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
        var withDictionary = new Korean(dictionary, null, null, null);

        assertThat(terms(korean(withDictionary), "세종시")).containsExactly("세종", "시");

        var unigrams = new Korean(null, null, null, true);
        assertThat(terms(korean(unigrams), "𐌰𐌱")).containsExactly("𐌰", "𐌱");
    }

    @Test
    void theirOptionsAreOnlyForThem() {
        var english = new AnalyzerSpec.Preset(
                "org.apache.lucene.analysis.en.EnglishAnalyzer",
                null,
                null,
                null,
                null,
                japaneseOptions(null, null),
                null,
                null,
                null);
        assertThatThrownBy(() -> Analyzers.build(english))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("EnglishAnalyzer takes no Japanese options");
        var japaneseWithKorean =
                new AnalyzerSpec.Preset(JAPANESE, null, null, null, null, null, koreanOptions(null, null), null, null);
        assertThatThrownBy(() -> Analyzers.build(japaneseWithKorean))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("JapaneseAnalyzer takes no Korean options");
        var japaneseWithCompletion = new AnalyzerSpec.Preset(
                JAPANESE, null, null, null, null, null, null, null, new JapaneseCompletion(null, null));
        assertThatThrownBy(() -> Analyzers.build(japaneseWithCompletion))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("JapaneseAnalyzer takes no Japanese completion options");
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Preset(KOREAN, words(), null)))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("KoreanAnalyzer takes no stop words");
    }

    private static AnalyzerSpec.Preset japanese(@Nullable WordSet stopwords, @Nullable Japanese options) {
        return new AnalyzerSpec.Preset(JAPANESE, stopwords, null, null, null, options, null, null, null);
    }

    private static AnalyzerSpec.Preset japaneseCompletion(@Nullable JapaneseCompletion options) {
        return new AnalyzerSpec.Preset(JAPANESE_COMPLETION, null, null, null, null, null, null, null, options);
    }

    private static Japanese japaneseOptions(@Nullable JapaneseMode mode, @Nullable WordSet stopTags) {
        return new Japanese(null, mode, stopTags);
    }

    private static AnalyzerSpec.Preset korean(Korean options) {
        return new AnalyzerSpec.Preset(KOREAN, null, null, null, null, null, options, null, null);
    }

    private static Korean koreanOptions(@Nullable KoreanDecompoundMode mode, @Nullable WordSet stopTags) {
        return new Korean(null, mode, stopTags, null);
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
