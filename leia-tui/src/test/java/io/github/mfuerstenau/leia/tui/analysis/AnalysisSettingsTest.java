package io.github.mfuerstenau.leia.tui.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings.Kind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The analysis with field analyzers and auto stop words, as the requests send it (ADR 0039). */
class AnalysisSettingsTest {

    @Test
    void theDefaultIsTheStandardAnalyzerForEveryField() {
        AnalysisSettings settings = new AnalysisSettings();

        assertThat(settings.isStandard()).isTrue();
        assertThat(settings.text()).isEqualTo("standard analyzer");
        assertThat(settings.analyzer()).isEqualTo(AnalyzerSettings.standardAnalyzer());
        assertThat(settings.analyzer(Optional.of("body"))).isSameAs(settings.defaultAnalyzer());
    }

    @Test
    void mapsTheFieldsAndTheStopWords() {
        AnalyzerSettings english = new AnalyzerSettings();
        english.kind = Kind.PRESET;
        AnalyzerSettings custom = new AnalyzerSettings();
        custom.kind = Kind.CUSTOM;
        var found = new AutoStopWords(
                5,
                List.of(
                        new FieldStopWords("body", List.of("a", "the")),
                        new FieldStopWords("id", List.of()),
                        new FieldStopWords("title", List.of("lamp"))));

        AnalysisSettings settings = new AnalysisSettings()
                .withAnalyzer(Optional.empty(), english)
                .withAnalyzer(Optional.of("title"), custom)
                .withAnalyzer(Optional.of("raw"), new AnalyzerSettings())
                .withStopWords(Optional.of(found));

        Analyzer analyzer = settings.analyzer();
        assertThat(analyzer.preset()).isEqualTo(english.analyzer().preset());
        assertThat(analyzer.fields())
                .containsExactly(
                        new FieldAnalyzer("raw", AnalyzerSettings.standardAnalyzer()),
                        new FieldAnalyzer("title", custom.analyzer()));
        // A field without words sends none.
        assertThat(analyzer.autoStopWords())
                .containsExactly(
                        new FieldStopWords("body", List.of("a", "the")), new FieldStopWords("title", List.of("lamp")));
        assertThat(settings.text()).isEqualTo("EnglishAnalyzer · 2 fields own · 3 auto stop words in 2 fields");
        assertThat(settings.isStandard()).isFalse();
        assertThat(settings.stopWords("title")).containsExactly("lamp");

        AnalysisSettings dropped =
                settings.withoutAnalyzer("title").withoutAnalyzer("raw").withStopWords(Optional.empty());
        assertThat(dropped.analyzer().fields()).isEmpty();
        assertThat(dropped.analyzer().autoStopWords()).isEmpty();
        assertThat(dropped.text()).isEqualTo("EnglishAnalyzer");
    }

    @Test
    void stopWordsBelongToTheirIndex() {
        var found = new AutoStopWords(1, List.of(new FieldStopWords("body", List.of("the"))));
        AnalysisSettings settings = new AnalysisSettings().withStopWords(Optional.of(found));

        AnalysisSettings reopened = settings.forIndex(true);
        assertThat(reopened.stopWords()).hasValue(found);
        assertThat(reopened.text())
                .isEqualTo("standard analyzer · 1 auto stop word in 1 field (from before the reopen)");
        assertThat(settings.forIndex(false).stopWords()).isEmpty();
        assertThat(settings.forIndex(false).isStandard()).isTrue();
    }
}
