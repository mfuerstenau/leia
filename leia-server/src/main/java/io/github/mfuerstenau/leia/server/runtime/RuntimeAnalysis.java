package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableComponent;
import io.github.mfuerstenau.leia.protocol.analysis.CharFilterStep;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.analysis.PresetOption;
import io.github.mfuerstenau.leia.protocol.analysis.Token;
import io.github.mfuerstenau.leia.protocol.analysis.TokenAttribute;
import io.github.mfuerstenau.leia.protocol.analysis.TokenStage;
import io.github.mfuerstenau.leia.protocol.analysis.WordSetSupport;
import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.IOException;
import java.util.List;

/**
 * The analysis tab of a runtime (ADR 0067), mapped to the protocol's records as {@code
 * GetAnalysisComponentsHandler} and {@code AnalyzeHandler} map leia-core's.
 *
 * @param runtime the runtime that {@code analysis} belongs to
 */
public record RuntimeAnalysis(LuceneRuntime runtime, Analysis analysis) {

    /** {@code getAnalysisComponents}. */
    public GetAnalysisComponentsResult components() {
        Analysis.Components components = analysis.components();
        return new GetAnalysisComponentsResult(
                components.analyzers().stream()
                        .map(analyzer -> new AvailableAnalyzer(
                                analyzer.name(),
                                analyzer.className(),
                                support(analyzer.stopwords()),
                                support(analyzer.stemExclusion()),
                                analyzer.options().stream()
                                        .map(RuntimeAnalysis::option)
                                        .toList()))
                        .toList(),
                available(components.charFilters()),
                available(components.tokenizers()),
                available(components.tokenFilters()));
    }

    /**
     * {@code analyze}, with the text's length checked by the caller.
     *
     * @throws RpcException {@code INVALID_PARAMS}, or {@code INVALID_ANALYZER} for a path that isn't
     *     one, as {@link RuntimeAnalyzers#spec} throws it; {@code INVALID_ANALYZER} for an analyzer the
     *     runtime finds wrong, {@code NOT_SUPPORTED} for one it lacks
     * @throws IOException if a component fails on the text
     */
    public AnalyzeResult analyze(AnalyzeParams params) throws RpcException, IOException {
        Search.AnalyzerSpec spec = RuntimeAnalyzers.spec(params.analyzer());
        Analysis.TextAnalysis result;
        try {
            result = analysis.analyze(spec, params.field().orElse(""), params.text(), AnalyzeResult.MAX_TOKENS);
        } catch (Search.InvalidAnalyzerException | Search.UnsupportedAnalyzerException e) {
            throw RuntimeAnalyzers.failed(runtime, e);
        }
        return new AnalyzeResult(
                result.charFilters().stream()
                        .map(step -> new CharFilterStep(step.name(), step.text()))
                        .toList(),
                result.stages().stream()
                        .map(stage -> new TokenStage(
                                stage.name(),
                                stage.tokens().stream()
                                        .map(RuntimeAnalysis::token)
                                        .toList(),
                                stage.truncated()))
                        .toList());
    }

    private static Token token(Analysis.Token token) {
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

    private static WordSetSupport support(Analysis.WordSetSupport support) {
        return switch (support) {
            case NONE -> WordSetSupport.NONE;
            case OPTIONAL -> WordSetSupport.OPTIONAL;
            case REQUIRED -> WordSetSupport.REQUIRED;
        };
    }

    private static PresetOption option(Analysis.PresetOption option) {
        return switch (option) {
            case MAX_TOKEN_LENGTH -> PresetOption.MAX_TOKEN_LENGTH;
            case STEM_OVERRIDE -> PresetOption.STEM_OVERRIDE;
            case JAPANESE -> PresetOption.JAPANESE;
            case KOREAN -> PresetOption.KOREAN;
            case COLLATION -> PresetOption.COLLATION;
            case JAPANESE_COMPLETION -> PresetOption.JAPANESE_COMPLETION;
        };
    }

    private static List<AvailableComponent> available(List<Analysis.Available> components) {
        return components.stream()
                .map(component -> new AvailableComponent(component.name(), component.className()))
                .toList();
    }
}
