package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.AnalysisComponents.AvailableAnalyzer;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Collation;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.CollationDecomposition;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.CollationStrength;
import java.nio.file.Path;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ICU's {@code ICUCollationKeyAnalyzer} with ICU's collator (ADR 0038), with the module loaded as an
 * analysis jar, so this runs in the {@code jarsTest} JVM.
 */
@Tag("jars")
class IcuCollationTest {

    private static final Path JARS = Path.of(System.getProperty("leia.test.analysis.jars"));

    private static final String ICU_COLLATION = "org.apache.lucene.analysis.icu.ICUCollationKeyAnalyzer";

    @BeforeAll
    static void loadTheModule() throws Exception {
        TestJars.load(List.of(JARS.resolve("icu")));
    }

    @Test
    void isListedWithACollator() {
        assertThat(Analyzers.components().analyzers())
                .contains(new AvailableAnalyzer(
                        "ICUCollationKeyAnalyzer",
                        ICU_COLLATION,
                        WordSetSupport.NONE,
                        WordSetSupport.NONE,
                        List.of(PresetOption.COLLATION)));
    }

    @Test
    void takesTheStrengthAndTheKeywordsOfTheTag() throws Exception {
        var primary = collation("de", CollationStrength.PRIMARY, null);
        assertThat(bytes(primary, "Äpfel")).isEqualTo(bytes(primary, "apfel"));
        var tertiary = collation("de", CollationStrength.TERTIARY, null);
        assertThat(bytes(tertiary, "Äpfel")).isNotEqualTo(bytes(tertiary, "apfel"));
        // ks-level1 is the primary strength.
        var level1 = collation("de-u-ks-level1", null, null);
        assertThat(bytes(level1, "Äpfel")).isEqualTo(bytes(level1, "apfel"));
        // Only ICU's collator has it.
        var quaternary = collation("de", CollationStrength.QUATERNARY, CollationDecomposition.CANONICAL);
        assertThat(bytes(quaternary, "Äpfel")).isNotEmpty();
        assertThat(bytes(preset(null), "Äpfel")).isNotEmpty();
    }

    @Test
    void hasNoFullDecomposition() {
        assertThatThrownBy(() -> Analyzers.build(collation("de", null, CollationDecomposition.FULL)))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("collation: ICU's collator has no decomposition FULL");
    }

    private static AnalyzerSpec.Preset collation(
            String locale, CollationStrength strength, CollationDecomposition decomposition) {
        return preset(new Collation(locale, strength, decomposition));
    }

    private static AnalyzerSpec.Preset preset(@Nullable Collation collation) {
        return new AnalyzerSpec.Preset(ICU_COLLATION, null, null, null, null, null, null, collation, null);
    }

    /** The bytes of the first token, as the analysis shows them. */
    private static String bytes(AnalyzerSpec spec, String text) throws Exception {
        try (Analyzer analyzer = Analyzers.build(spec)) {
            return Analyzers.analyze(analyzer, text, 100).stages().getFirst().tokens().getFirst().attributes().stream()
                    .filter(attribute -> attribute.key().equals("bytes"))
                    .findFirst()
                    .orElseThrow()
                    .value();
        }
    }
}
