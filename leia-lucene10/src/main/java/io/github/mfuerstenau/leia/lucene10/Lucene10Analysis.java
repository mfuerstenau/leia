package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.AnalysisComponents;
import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.Search;
import java.io.IOException;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;

/** The analysis tab through leia-core's {@link Analyzers} (ADR 0067), the extension jars included. */
final class Lucene10Analysis implements Analysis {

    @Override
    public Components components() {
        AnalysisComponents components = Analyzers.components();
        return new Components(
                components.analyzers().stream()
                        .map(analyzer -> new AvailableAnalyzer(
                                analyzer.name(),
                                analyzer.className(),
                                WordSetSupport.valueOf(analyzer.stopwords().name()),
                                WordSetSupport.valueOf(analyzer.stemExclusion().name()),
                                analyzer.options().stream()
                                        .map(option -> PresetOption.valueOf(option.name()))
                                        .toList()))
                        .toList(),
                available(components.charFilters()),
                available(components.tokenizers()),
                available(components.tokenFilters()));
    }

    @Override
    public TextAnalysis analyze(Search.AnalyzerSpec spec, String field, String text, int maxTokens)
            throws Search.InvalidAnalyzerException, IOException {
        try (Analyzer analyzer = Lucene10Analyzers.analyzer(spec)) {
            io.github.mfuerstenau.leia.core.TextAnalysis analysis = Analyzers.analyze(analyzer, field, text, maxTokens);
            return new TextAnalysis(
                    analysis.charFilters().stream()
                            .map(step -> new CharFilterStep(step.name(), step.text()))
                            .toList(),
                    analysis.stages().stream()
                            .map(stage -> new TokenStage(
                                    stage.name(),
                                    stage.tokens().stream()
                                            .map(Lucene10Analysis::token)
                                            .toList(),
                                    stage.truncated()))
                            .toList());
        }
    }

    private static Token token(io.github.mfuerstenau.leia.core.TextAnalysis.Token token) {
        return new Token(
                token.term(),
                token.position(),
                token.positionIncrement(),
                token.positionLength(),
                token.startOffset(),
                token.endOffset(),
                token.type(),
                token.attributes().stream()
                        .map(attribute -> new TokenAttribute(attribute.attribute(), attribute.key(), attribute.value()))
                        .toList());
    }

    private static List<Available> available(List<AnalysisComponents.Available> components) {
        return components.stream()
                .map(component -> new Available(component.name(), component.className()))
                .toList();
    }
}
