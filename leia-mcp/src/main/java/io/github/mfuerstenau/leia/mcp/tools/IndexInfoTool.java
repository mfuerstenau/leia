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
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** {@code index_info}: the open index and its fields (ADR 0100). */
final class IndexInfoTool implements McpTool<IndexInfoTool.Arguments, IndexInfoTool.Result> {

    /** The most fields the result lists. */
    static final int MAX_FIELDS = 1000;

    /** The most index directories the result lists, with how many there are beside them. */
    static final int MAX_INDEXES = 100;

    record Arguments() {}

    record Result(
            @Description("The absolute path that was opened")
            String path,

            @Description("The version of the Lucene that reads the index, such as 10.5.2")
            String luceneRuntime,

            @Description("The Elasticsearch or OpenSearch installation whose Lucene reads the index, if one does")
            @Nullable
            String server,

            @Description("Live documents") int numDocs,

            @Description("Documents including deleted ones; document ids go from 0 to maxDoc - 1")
            int maxDoc,

            @Description("Segments in all opened commits") int segmentCount,

            @Description("Index directories below the path that were left out, and why")
            List<Skipped> skipped,

            @Description("How many index directories were opened")
            int indexCount,

            @Description("The opened index directories, at most " + MAX_INDEXES + ", in order. Their paths are the"
                    + " ones check_index takes in only")
            List<Index> indexes,

            @Description("What the runtime that reads the index can do; other tools fail with NOT_SUPPORTED")
            List<Feature> features,

            @Description("How many fields the index has") int fieldCount,

            @Description("The fields, at most " + MAX_FIELDS + ", by field number. The index doesn't record"
                    + " which fields are stored: stored_fields finds them")
            List<Field> fields) {}

    record Skipped(String path, String message) {}

    record Index(
            String path,

            @Description("The segments_N file of the commit that was opened, the latest one: what the other tools"
                    + " read. Absent without a reader")
            @Nullable
            String segmentsFile,

            @Description("The Lucene version that wrote that commit; absent without a reader") @Nullable
            String luceneVersion,

            @Description(
                    "The user data of that commit, sorted by key, such as Elasticsearch's history_uuid, max_seq_no and"
                            + " local_checkpoint; absent if there is none")
            @Nullable
            Map<String, String> userData) {}

    record Field(
            String name,

            @Description("What the postings hold; NONE if the field isn't indexed")
            IndexOptions indexOptions,

            @Description("The kind of doc values; absent without") @Nullable
            DocValuesType docValuesType,

            @Description("Dimensions of the point values; absent without points") @Nullable
            Integer pointDimensionCount,

            @Description("Bytes per dimension of the point values: 4 for an int or float, 8 for a long or double;"
                    + " absent without points")
            @Nullable
            Integer pointBytes,

            @Description("Dimensions of the KNN vectors; absent without vectors") @Nullable
            Integer vectorDimension,

            @Description("How the KNN vectors are encoded, FLOAT32 or BYTE; absent without vectors") @Nullable
            VectorEncoding vectorEncoding,

            @Description("How the KNN vectors are compared; COSINE refuses a vector of zeros; absent without vectors")
            @Nullable
            VectorSimilarity vectorSimilarity,

            @Description("Present, and true, only if the field has term vectors") @Nullable
            Boolean hasTermVectors,

            @Description("Documents with at least one term in the field, deleted ones included; absent without"
                    + " postings")
            @Nullable
            Integer docCount) {}

    @Override
    public String name() {
        return "index_info";
    }

    @Override
    public String description() {
        return "Describe the open index: its path, the Lucene that reads it, its document counts, its index"
                + " directories with their commit's user data, and its fields with how each is indexed. Fails with NO_INDEX_OPEN if no index is open; open_index opens one.";
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
                opened.server().orElse(null),
                stats.numDocs(),
                stats.maxDoc(),
                stats.segmentCount(),
                opened.skipped().stream()
                        .map(skipped -> new Skipped(skipped.path(), skipped.message()))
                        .toList(),
                opened.indexes().size(),
                indexes(opened, stats),
                opened.features().stream().sorted().toList(),
                stats.fields().size(),
                stats.fields().stream()
                        .limit(MAX_FIELDS)
                        .map(IndexInfoTool::field)
                        .toList());
    }

    /**
     * The first {@link #MAX_INDEXES} index directories, each with the user data of the commit its
     * reader was opened on, from {@code getIndexStats}.
     */
    private static List<Index> indexes(OpenIndexResult opened, GetIndexStatsResult stats) {
        Map<String, IndexCommitStats> commits = new HashMap<>();
        for (IndexCommitStats commit : stats.indexes()) {
            commits.put(commit.path(), commit);
        }
        List<Index> indexes = new ArrayList<>();
        for (IndexEntry entry :
                opened.indexes().subList(0, Math.min(opened.indexes().size(), MAX_INDEXES))) {
            Optional<IndexCommitStats> commit =
                    entry.reader().isPresent() ? Optional.ofNullable(commits.get(entry.path())) : Optional.empty();
            indexes.add(new Index(
                    entry.path(),
                    entry.reader().map(ReaderInfo::segmentsFile).orElse(null),
                    commit.map(IndexCommitStats::commitLuceneVersion).orElse(null),
                    commit.map(IndexCommitStats::userData)
                            .filter(userData -> !userData.isEmpty())
                            .map(TreeMap::new)
                            .orElse(null)));
        }
        return indexes;
    }

    private static Field field(IndexField field) {
        return new Field(
                field.name(),
                field.indexOptions(),
                field.docValuesType() == DocValuesType.NONE ? null : field.docValuesType(),
                Absent.nonZero(field.pointDimensionCount()),
                Absent.nonZero(field.pointNumBytes()),
                Absent.nonZero(field.vectorDimension()),
                field.vectorEncoding().orElse(null),
                field.vectorSimilarity().orElse(null),
                Absent.flag(field.hasTermVectors()),
                field.terms().map(TermStats::docCount).orElse(null));
    }
}
