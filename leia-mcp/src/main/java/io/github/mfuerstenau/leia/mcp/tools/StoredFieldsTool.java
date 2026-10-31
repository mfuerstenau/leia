package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.GetStoredFieldsScanResult;
import io.github.mfuerstenau.leia.protocol.document.StartStoredFieldsScanParams;
import io.github.mfuerstenau.leia.protocol.document.StoredFields;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@code stored_fields}: the fields the documents of the open index store values in, which the index
 * doesn't record, through the backend's cache of each segment's stored fields (ADR 0113).
 */
final class StoredFieldsTool implements McpTool<StoredFieldsTool.Arguments, StoredFieldsTool.Result> {

    /** The most fields the result lists. */
    static final int MAX_FIELDS = 1000;

    record Arguments(
            @Description("Whether to read every segment again instead of taking what leia remembers of it; false by"
                    + " default. Not needed for correctness: a segment never changes once written")
            Optional<Boolean> ignoreCache) {}

    record Result(
            @Description("How many fields have a stored value in at least one document, deleted ones included")
            int fieldCount,

            @Description("The fields, at most " + MAX_FIELDS + ", by name")
            List<Field> fields,

            @Description("The segments of the index") int segments,

            @Description("Those of them whose stored fields leia remembered, from this session or an earlier one,"
                    + " instead of reading them")
            int cachedSegments,

            long tookMillis) {}

    record Field(
            String name,

            @Description("Live documents with at least one stored value in the field")
            int liveDocs,

            @Description("Documents with at least one stored value in the field, deleted ones included")
            int allDocs) {}

    @Override
    public String name() {
        return "stored_fields";
    }

    @Override
    public String description() {
        return "List the fields whose values the documents store, with how many documents store each: the fields"
                + " get_document, get_field_value and scan_stored can show. The index doesn't record this, so the"
                + " first call reads every document's stored fields, without their values; leia remembers each"
                + " segment, so later calls, also after a restart, only read new segments.";
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
        OptionalLong total = call.session().maxDoc();
        StoredFields stored = call.job(
                Methods.START_STORED_FIELDS_SCAN,
                new StartStoredFieldsScanParams(arguments.ignoreCache().orElse(false)),
                Methods.GET_STORED_FIELDS_SCAN,
                GetStoredFieldsScanResult::fields,
                poll -> new Jobs.Progress(poll.docsScanned(), total));
        return new Result(
                stored.fields().size(),
                stored.fields().stream()
                        .limit(MAX_FIELDS)
                        .map(field -> new Field(field.name(), field.liveDocs(), field.allDocs()))
                        .toList(),
                stored.segments(),
                stored.cachedSegments(),
                stored.tookMillis());
    }
}
