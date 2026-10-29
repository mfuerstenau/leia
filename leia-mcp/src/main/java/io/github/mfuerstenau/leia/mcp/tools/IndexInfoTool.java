package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** {@code index_info}: the open index and its fields (ADR 0100). */
final class IndexInfoTool implements McpTool<IndexInfoTool.Arguments, IndexInfoTool.Result> {

    /** The most fields the result lists. */
    static final int MAX_FIELDS = 1000;

    record Arguments() {}

    record Result(
            @Description("The absolute path that was opened")
            String path,

            @Description("The version of the Lucene that reads the index, such as 10.5.2")
            String luceneRuntime,

            @Description("The Elasticsearch or OpenSearch installation whose Lucene reads the index, if one does")
            Optional<String> server,

            @Description("Live documents") int numDocs,

            @Description("Documents including deleted ones; document ids go from 0 to maxDoc - 1")
            int maxDoc,

            @Description("Segments in all opened commits") int segmentCount,

            @Description("Index directories below the path that were left out, and why")
            List<Skipped> skipped,

            @Description("What the runtime that reads the index can do; other tools fail with NOT_SUPPORTED")
            List<Feature> features,

            @Description("How many fields the index has") int fieldCount,

            @Description("The fields, at most " + MAX_FIELDS + ", by field number. The index doesn't record"
                    + " which fields are stored: get_document shows a document's stored values")
            List<Field> fields) {}

    record Skipped(String path, String message) {}

    record Field(
            String name,

            @Description("What the postings hold; NONE if the field isn't indexed")
            IndexOptions indexOptions,

            DocValuesType docValuesType,

            @Description("Dimensions of the point values; 0 without points")
            int pointDimensionCount,

            @Description("Dimensions of the KNN vectors; 0 without vectors")
            int vectorDimension,

            boolean hasTermVectors,

            @Description("Documents with at least one term in the field, deleted ones included; absent without"
                    + " postings")
            OptionalInt docCount) {}

    @Override
    public String name() {
        return "index_info";
    }

    @Override
    public String description() {
        return "Describe the open index: its path, the Lucene that reads it, its document counts and its fields with"
                + " how each is indexed. Fails with NO_INDEX_OPEN if no index is open; open_index opens one.";
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
        GetIndexStatsResult stats = call.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());
        OpenIndexResult opened = call.session()
                .opened()
                .orElseThrow(() -> new IllegalStateException("An index is open, but not through open_index"));
        return info(opened, stats);
    }

    static Result info(OpenIndexResult opened, GetIndexStatsResult stats) {
        return new Result(
                stats.path(),
                opened.luceneRuntime(),
                opened.server(),
                stats.numDocs(),
                stats.maxDoc(),
                stats.segmentCount(),
                opened.skipped().stream()
                        .map(skipped -> new Skipped(skipped.path(), skipped.message()))
                        .toList(),
                opened.features().stream().sorted().toList(),
                stats.fields().size(),
                stats.fields().stream()
                        .limit(MAX_FIELDS)
                        .map(IndexInfoTool::field)
                        .toList());
    }

    private static Field field(IndexField field) {
        return new Field(
                field.name(),
                field.indexOptions(),
                field.docValuesType(),
                field.pointDimensionCount(),
                field.vectorDimension(),
                field.hasTermVectors(),
                field.terms().map(TermStats::docCount).map(OptionalInt::of).orElseGet(OptionalInt::empty));
    }
}
