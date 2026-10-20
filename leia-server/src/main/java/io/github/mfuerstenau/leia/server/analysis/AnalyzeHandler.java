package io.github.mfuerstenau.leia.server.analysis;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.RuntimeAnalysis;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import jakarta.inject.Inject;
import java.io.IOException;

/**
 * {@code analyze}: builds the analyzer and shows what it makes of the text, step by step (ADR
 * 0014), for a field if one is given (ADR 0039). The text is short, so this is no job. The first
 * runtime with an analysis tab does it (ADR 0067); without one, it is {@code NOT_SUPPORTED} (ADR 0071).
 */
public final class AnalyzeHandler implements MethodHandler<AnalyzeParams, AnalyzeResult> {

    private final Runtimes runtimes;

    @Inject
    AnalyzeHandler(Runtimes runtimes) {
        this.runtimes = runtimes;
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
        RuntimeAnalysis analysis = runtimes.analysis().orElseThrow(RuntimeAnalysis::notSupported);
        try {
            return analysis.analyze(params);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw cannotAnalyze(e);
        }
    }

    /** A component that fails on this text, such as one that breaks the token stream contract. */
    private static RpcException cannotAnalyze(Exception e) {
        return new RpcException(ErrorCode.INVALID_ANALYZER, "Cannot analyze the text: " + e.getMessage(), e);
    }
}
