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
import io.github.mfuerstenau.leia.server.index.Routing;
import io.github.mfuerstenau.leia.server.runtime.RuntimeAnalysis;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import org.apache.lucene.analysis.Analyzer;

/**
 * {@code analyze}: builds the analyzer and shows what it makes of the text, step by step (ADR
 * 0014), for a field if one is given (ADR 0039). The text is short, so this is no job. With {@link
 * Routing#lucene10Runtime()}, the first runtime with an analysis tab does it (ADR 0067).
 */
public final class AnalyzeHandler implements MethodHandler<AnalyzeParams, AnalyzeResult> {

    private final Runtimes runtimes;
    private final Routing routing;

    @Inject
    AnalyzeHandler(Runtimes runtimes, Routing routing) {
        this.runtimes = runtimes;
        this.routing = routing;
    }

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
        Optional<RuntimeAnalysis> analysis = routing.lucene10Runtime() ? runtimes.analysis() : Optional.empty();
        if (analysis.isPresent()) {
            try {
                return analysis.get().analyze(params);
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                throw cannotAnalyze(e);
            }
        }
        try (Analyzer analyzer = AnalyzerSpecs.build(params.analyzer())) {
            return toResult(
                    Analyzers.analyze(analyzer, params.field().orElse(""), params.text(), AnalyzeResult.MAX_TOKENS));
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw cannotAnalyze(e);
        }
    }

    /** A component that fails on this text, such as one that breaks the token stream contract. */
    private static RpcException cannotAnalyze(Exception e) {
        return new RpcException(ErrorCode.INVALID_ANALYZER, "Cannot analyze the text: " + e.getMessage(), e);
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
