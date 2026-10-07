package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.core.Analyzers;
import io.github.mfuerstenau.leia.core.TextAnalysis;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.CharFilterStep;
import io.github.mfuerstenau.leia.protocol.analysis.Token;
import io.github.mfuerstenau.leia.protocol.analysis.TokenAttribute;
import io.github.mfuerstenau.leia.protocol.analysis.TokenStage;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import jakarta.inject.Inject;
import java.io.IOException;
import org.apache.lucene.analysis.Analyzer;

/**
 * {@code analyze}: builds the analyzer and shows what it makes of the text, step by step (ADR
 * 0014). The text is short, so this is no job.
 */
public final class AnalyzeHandler implements MethodHandler<AnalyzeParams, AnalyzeResult> {

    @Inject
    AnalyzeHandler() {}

    @Override
    public RpcMethod<AnalyzeParams, AnalyzeResult> method() {
        return Methods.ANALYZE;
    }

    @Override
    public AnalyzeResult handle(AnalyzeParams params) throws RpcException {
        int length = params.text().length();
        if (length > AnalyzeParams.MAX_TEXT_LENGTH) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the text must be at most " + AnalyzeParams.MAX_TEXT_LENGTH
                            + " characters long, not " + length);
        }
        try (Analyzer analyzer = AnalyzerSpecs.build(params.analyzer())) {
            return toResult(Analyzers.analyze(analyzer, params.text(), AnalyzeResult.MAX_TOKENS));
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            // A component that fails on this text, such as one that breaks the token stream contract.
            throw new RpcException(ErrorCode.INVALID_ANALYZER, "Cannot analyze the text: " + e.getMessage(), e);
        }
    }

    private static AnalyzeResult toResult(TextAnalysis analysis) {
        return new AnalyzeResult(
                analysis.charFilters().stream()
                        .map(step -> new CharFilterStep(step.name(), step.text()))
                        .toList(),
                analysis.stages().stream()
                        .map(stage -> new TokenStage(
                                stage.name(),
                                stage.tokens().stream()
                                        .map(AnalyzeHandler::toToken)
                                        .toList(),
                                stage.truncated()))
                        .toList());
    }

    private static Token toToken(TextAnalysis.Token token) {
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
}
