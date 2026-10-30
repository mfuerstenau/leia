package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Token;
import io.github.mfuerstenau.leia.protocol.analysis.TokenStage;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code analyze}: the tokens an analyzer makes of a text, to see why a query matches or not (ADR
 * 0105). The analyzer is named as in {@code search} (ADR 0104).
 */
final class AnalyzeTool implements McpTool<AnalyzeTool.Arguments, AnalyzeTool.Result> {

    static final int DEFAULT_TOKENS = 200;

    record Arguments(
            @Description("The text to analyze, at most " + AnalyzeParams.MAX_TEXT_LENGTH + " characters")
            String text,

            @Description("By simple or class name, as in search; StandardAnalyzer by default")
            Optional<String> analyzer,

            @Description("The most tokens shown, from 1 to " + AnalyzeResult.MAX_TOKENS + "; " + DEFAULT_TOKENS
                    + " by default")
            OptionalInt maxTokens) {}

    record Result(
            @Description("The analyzer's class name") String analyzer,

            @Description("The tokens in order") List<AnalyzedToken> tokens,

            @Description("The tokens the text makes, counted up to " + AnalyzeResult.MAX_TOKENS)
            int tokenCount,

            @Description("Present, and true, only if tokens are left out")
            Optional<Boolean> cut) {}

    record AnalyzedToken(
            String term,
            int position,

            @Description("Where the token starts in the text")
            int startOffset,

            @Description("Where the token ends in the text") int endOffset,

            String type,

            @Description("How many positions the token spans; absent if 1")
            OptionalInt positionLength) {}

    @Override
    public String name() {
        return "analyze";
    }

    @Override
    public String description() {
        return "What an analyzer makes of a text: its tokens with positions, offsets and types, to see why a query"
                + " matches or not and which analyzer a field needs. Needs no open index.";
    }

    @Override
    public Class<Arguments> arguments() {
        return Arguments.class;
    }

    @Override
    public Class<Result> result() {
        return Result.class;
    }

    @Override
    public Result call(Arguments arguments, Call call) throws ToolException, Jobs.Cancelled {
        int maxTokens = Slices.bounded("maxTokens", arguments.maxTokens(), DEFAULT_TOKENS, 1, AnalyzeResult.MAX_TOKENS);
        Analyzer analyzer = arguments.analyzer().isPresent()
                ? Analyzers.named(arguments.analyzer().get(), new Analyzers.Names(call))
                : Analyzers.STANDARD;
        AnalyzeResult result =
                call.call(Methods.ANALYZE, new AnalyzeParams(analyzer, arguments.text(), Optional.empty()));
        TokenStage stage = result.stages().getLast();
        List<AnalyzedToken> tokens =
                stage.tokens().stream().limit(maxTokens).map(AnalyzeTool::token).toList();
        return new Result(
                stage.name(),
                tokens,
                stage.tokens().size(),
                Absent.flag(tokens.size() < stage.tokens().size() || stage.truncated()));
    }

    private static AnalyzedToken token(Token token) {
        return new AnalyzedToken(
                token.term(),
                token.position(),
                token.startOffset(),
                token.endOffset(),
                token.type(),
                token.positionLength() == 1 ? OptionalInt.empty() : OptionalInt.of(token.positionLength()));
    }
}
