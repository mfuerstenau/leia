package io.github.mfuerstenau.leia.lucene5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The preset analyzers of Lucene 5.5 beyond what {@link Lucene5IndexTest} parses with: the options
 * a preset takes or refuses, the limits of the longest token, the options that only Lucene 10 has, and
 * the collator of {@code CollationKeyAnalyzer}.
 */
class Lucene5PresetsTest {

    private static final Search.ParserOptions BODY = new Search.ParserOptions("body", false, false, 0, 0, true, false);

    private static final AnalyzerSpec.WordSet WORDS = new AnalyzerSpec.WordSet.Words(List.of("running"));

    private static RuntimeIndex open() throws IOException {
        Path lucene5 = Path.of(System.getProperty("leia.test.index.lucene5")).toAbsolutePath();
        return new Lucene5Runtime().open(List.of(lucene5), DirectoryKind.FS);
    }

    private static AnalyzerSpec.Preset preset(
            String name,
            AnalyzerSpec.WordSet stopwords,
            AnalyzerSpec.WordSet stemExclusion,
            Integer maxTokenLength,
            AnalyzerSpec.StemDictionary stemOverride,
            AnalyzerSpec.Collation collation) {
        return new AnalyzerSpec.Preset(
                name, stopwords, stemExclusion, maxTokenLength, stemOverride, null, null, collation, null);
    }

    private static String parsed(AnalyzerSpec spec) throws Exception {
        try (RuntimeIndex index = open()) {
            return index.search()
                    .orElseThrow()
                    .parse("running Cats", BODY, spec)
                    .toString();
        }
    }

    @Test
    void refusesAnOptionThatThePresetDoesNotTake() throws Exception {
        try (RuntimeIndex index = open()) {
            Search search = index.search().orElseThrow();
            // KeywordAnalyzer has no settings at all.
            assertThatThrownBy(() -> search.parse("a", BODY, preset("KeywordAnalyzer", WORDS, null, null, null, null)))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("KeywordAnalyzer takes no stop words");
            assertThatThrownBy(() -> search.parse("a", BODY, preset("KeywordAnalyzer", null, WORDS, null, null, null)))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("KeywordAnalyzer takes no stem exclusions");
            assertThatThrownBy(() -> search.parse("a", BODY, preset("KeywordAnalyzer", null, null, 5, null, null)))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("KeywordAnalyzer takes no max token length");
            assertThatThrownBy(() -> search.parse(
                            "a",
                            BODY,
                            preset(
                                    "KeywordAnalyzer",
                                    null,
                                    null,
                                    null,
                                    new AnalyzerSpec.StemDictionary.Entries(Map.of("a", "b")),
                                    null)))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("KeywordAnalyzer takes no stem overrides");
            assertThatThrownBy(() -> search.parse(
                            "a",
                            BODY,
                            preset(
                                    "KeywordAnalyzer",
                                    null,
                                    null,
                                    null,
                                    null,
                                    new AnalyzerSpec.Collation("", null, null))))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("KeywordAnalyzer takes no collation");
        }
    }

    @Test
    void keepsTheLongestTokenInRange() throws Exception {
        try (RuntimeIndex index = open()) {
            Search search = index.search().orElseThrow();
            assertThatThrownBy(() -> search.parse("a", BODY, new AnalyzerSpec.Preset("StandardAnalyzer", null, 0)))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("max token length: must be from 1 to 1048576, not 0");
            assertThatThrownBy(() ->
                            search.parse("a", BODY, new AnalyzerSpec.Preset("StandardAnalyzer", null, 1024 * 1024 + 1)))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("max token length: must be from 1 to 1048576, not 1048577");
            assertThat(search.parse("abcdef", BODY, new AnalyzerSpec.Preset("StandardAnalyzer", null, 3)))
                    .hasToString("body:abc body:def");
        }
    }

    @Test
    void refusesTheOptionsOnlyLucene10Has() throws Exception {
        try (RuntimeIndex index = open()) {
            Search search = index.search().orElseThrow();
            AnalyzerSpec.Preset japanese = new AnalyzerSpec.Preset(
                    "StandardAnalyzer",
                    null,
                    null,
                    null,
                    null,
                    new AnalyzerSpec.Japanese(null, null, null),
                    null,
                    null,
                    null);
            AnalyzerSpec.Preset korean = new AnalyzerSpec.Preset(
                    "StandardAnalyzer",
                    null,
                    null,
                    null,
                    null,
                    null,
                    new AnalyzerSpec.Korean(null, null, null, null),
                    null,
                    null);
            AnalyzerSpec.Preset completion = new AnalyzerSpec.Preset(
                    "StandardAnalyzer",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new AnalyzerSpec.JapaneseCompletion(null, null));
            assertThatThrownBy(() -> search.parse("a", BODY, japanese))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("Japanese options");
            assertThatThrownBy(() -> search.parse("a", BODY, korean))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("Korean options");
            assertThatThrownBy(() -> search.parse("a", BODY, completion))
                    .isInstanceOf(Search.UnsupportedAnalyzerException.class)
                    .hasMessageContaining("Japanese completion options");
        }
    }

    @Test
    void stemsWithTheExclusionsOfThePreset() throws Exception {
        // Porter stems running to run and Cats to cat.
        assertThat(parsed(new AnalyzerSpec.Preset("EnglishAnalyzer"))).isEqualTo("body:run body:cat");
        assertThat(parsed(preset("EnglishAnalyzer", null, WORDS, null, null, null)))
                .isEqualTo("body:running body:cat");
        // Stop words and exclusions together.
        assertThat(parsed(preset(
                        "EnglishAnalyzer", new AnalyzerSpec.WordSet.Words(List.of("cats")), WORDS, null, null, null)))
                .isEqualTo("body:running");
    }

    /** Only the Dutch analyzer takes a dictionary that overrides the stemmer. */
    @Test
    void stemsWithTheOverridesOfThePreset() throws Exception {
        AnalyzerSpec.StemDictionary jog = new AnalyzerSpec.StemDictionary.Entries(Map.of("running", "jog"));
        assertThat(parsed(preset("DutchAnalyzer", null, null, null, jog, null))).startsWith("body:jog");
        assertThat(parsed(preset(
                        "DutchAnalyzer", new AnalyzerSpec.WordSet.Words(List.of("cats")), null, null, jog, null)))
                .isEqualTo("body:jog");
        assertThat(parsed(preset(
                        "DutchAnalyzer", null, new AnalyzerSpec.WordSet.Words(List.of("cats")), null, jog, null)))
                .startsWith("body:jog body:cats");
    }

    /** The collation keys are binary: what shows is whether the options are taken or refused. */
    @ParameterizedTest
    @EnumSource(
            value = AnalyzerSpec.CollationDecomposition.class,
            names = {"NO", "CANONICAL", "FULL"})
    void collatesWithEveryDecomposition(AnalyzerSpec.CollationDecomposition decomposition) throws Exception {
        assertThat(parsed(preset(
                        "CollationKeyAnalyzer",
                        null,
                        null,
                        null,
                        null,
                        new AnalyzerSpec.Collation("de-DE", AnalyzerSpec.CollationStrength.SECONDARY, decomposition))))
                .startsWith("body:");
    }

    @ParameterizedTest
    @EnumSource(
            value = AnalyzerSpec.CollationStrength.class,
            names = {"PRIMARY", "TERTIARY", "IDENTICAL"})
    void collatesWithEveryStrength(AnalyzerSpec.CollationStrength strength) throws Exception {
        assertThat(parsed(preset(
                        "CollationKeyAnalyzer",
                        null,
                        null,
                        null,
                        null,
                        new AnalyzerSpec.Collation("", strength, null))))
                .startsWith("body:");
    }

    @Test
    void refusesACollationThatTheJdkCantMake() throws Exception {
        try (RuntimeIndex index = open()) {
            Search search = index.search().orElseThrow();
            assertThatThrownBy(() -> search.parse(
                            "a",
                            BODY,
                            preset(
                                    "CollationKeyAnalyzer",
                                    null,
                                    null,
                                    null,
                                    null,
                                    new AnalyzerSpec.Collation("not a tag", null, null))))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessageStartingWith("collation: 'not a tag' is not a language tag: ");
            assertThatThrownBy(() -> search.parse(
                            "a",
                            BODY,
                            preset(
                                    "CollationKeyAnalyzer",
                                    null,
                                    null,
                                    null,
                                    null,
                                    new AnalyzerSpec.Collation("", AnalyzerSpec.CollationStrength.QUATERNARY, null))))
                    .isInstanceOf(Search.InvalidAnalyzerException.class)
                    .hasMessage("collation: the JDK's collator has no strength QUATERNARY");
        }
    }
}
