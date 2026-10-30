package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * {@code explain}: why a document scores as it does for a query, or doesn't match (ADR 0109). It
 * takes the query arguments of {@code search}, with BM25 as {@code search} does, so that the score
 * is the hit's. Lucene's explanation comes back as text, one line per part, indented by depth.
 */
final class ExplainTool implements McpTool<ExplainTool.Arguments, ExplainTool.Result> {

    static final int DEFAULT_MAX_LINES = 200;

    static final int MAX_LINES = 10_000;

    private static final String INDENT = "  ";

    record Arguments(
            @Description("A query in Lucene's syntax, such as title:lamp AND body:qu*. Ranges on point fields work,"
                    + " such as price:[10 TO 20]; dates on point fields are epoch milliseconds, and dates indexed as"
                    + " text are written as they are indexed, such as published:[20260131 TO 20260228]. Give exactly"
                    + " one of query, term, docValues and knn")
            Optional<String> query,

            @Description("A query for exactly one term, neither analyzed nor split: for keyword fields such as ids."
                    + " Give exactly one of query, term, docValues and knn")
            Optional<Queries.Term> term,

            @Description("A query on the doc values of a field, for fields that have only those. Give exactly one of"
                    + " query, term, docValues and knn")
            Optional<Queries.DocValues> docValues,

            @Description("The nearest documents to a vector or to a document's vector, by KNN vectors; the score is"
                    + " the similarity. Give exactly one of query, term, docValues and knn")
            Optional<Queries.Knn> knn,

            @Description("The type of point fields in query, when the guess is wrong: a point field of 4 bytes is"
                    + " taken as INT and one of 8 bytes as LONG, so FLOAT and DOUBLE fields need it (index_info shows"
                    + " pointBytes). Goes with query")
            Optional<List<Queries.PointFieldType>> pointTypes,

            @Description("The field of the query's terms that name none; needed with query")
            Optional<String> defaultField,

            @Description("The analyzer of query, by simple or class name, such as EnglishAnalyzer or KeywordAnalyzer;"
                    + " StandardAnalyzer by default. Fields indexed with another need theirs")
            Optional<String> analyzer,

            @Description("Fields with an analyzer of their own, such as KeywordAnalyzer for ids")
            Optional<List<Queries.FieldAnalyzer>> fieldAnalyzers,

            @Description("How clauses of the query without an operator combine; OR by default")
            Optional<QueryOperator> defaultOperator,

            @Description("The document to explain: the docId of a hit of search")
            int docId,

            @Description("The most lines of the explanation, from 1 to " + MAX_LINES + "; " + DEFAULT_MAX_LINES
                    + " by default. It is cut at a whole line")
            OptionalInt maxLines)
            implements Queries.Arguments {}

    record Result(
            @Description("Whether the document matches the query")
            boolean matches,

            @Description("The document's score; absent if it doesn't match")
            Optional<Float> score,

            @Description("Lucene's explanation: one line per part, as value = description, with the parts of a line"
                    + " indented below it. For a document that doesn't match, why not")
            String explanation,

            @Description("Present, and true, only if lines were left out; call again with a higher maxLines")
            Optional<Boolean> cut) {}

    @Override
    public String name() {
        return "explain";
    }

    @Override
    public String description() {
        return "Explain how a document scores for a query, or why it doesn't match: the arguments of search plus a"
                + " hit's docId. Lucene's explanation, one line per part, indented.";
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
        int maxLines = Slices.bounded("maxLines", arguments.maxLines(), DEFAULT_MAX_LINES, 1, MAX_LINES);
        Queries.Stats stats = new Queries.Stats(call);
        StartExplainParams params = new StartExplainParams(
                Queries.query(arguments, call, stats, KnnQuery.MAX_K), Queries.BM25, arguments.docId());
        Explanation explanation = call.job(
                Methods.START_EXPLAIN,
                params,
                Methods.GET_EXPLAIN,
                GetExplainResult::explanation,
                poll -> new Jobs.Progress(0, OptionalLong.empty()));
        List<String> lines = new ArrayList<>();
        boolean cut = render(explanation, 0, maxLines, lines);
        Optional<Float> score =
                explanation.match() ? Optional.of(Float.parseFloat(explanation.value())) : Optional.empty();
        return new Result(explanation.match(), score, String.join("\n", lines), Absent.flag(cut));
    }

    /** Appends the lines of {@code explanation} depth first; true if it stopped at {@code maxLines}. */
    private static boolean render(Explanation explanation, int depth, int maxLines, List<String> lines) {
        if (lines.size() == maxLines) {
            return true;
        }
        lines.add(INDENT.repeat(depth) + explanation.value() + " = " + explanation.description());
        for (Explanation detail : explanation.details()) {
            if (render(detail, depth + 1, maxLines, lines)) {
                return true;
            }
        }
        return false;
    }
}
