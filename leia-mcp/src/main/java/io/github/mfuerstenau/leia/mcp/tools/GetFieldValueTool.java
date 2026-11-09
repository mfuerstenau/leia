package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/** {@code get_field_value}: a stored value whole, a slice at a time (ADR 0100). */
final class GetFieldValueTool implements McpTool<GetFieldValueTool.Arguments, GetFieldValueTool.Result> {

    static final int DEFAULT_LENGTH = 16_384;

    /** The longest slice. */
    static final int MAX_LENGTH = 65_536;

    record Arguments(
            int docId,
            String field,

            @Description("Which of the document's stored values in the field, from 0, as get_document lists them; 0"
                    + " by default")
            @Nullable
            Integer index,

            @Description("Where the slice starts, in characters: the nextOffset of the last slice; 0 by default")
            @Nullable
            Integer offset,

            @Description(
                    "The most characters of the slice, up to " + MAX_LENGTH + "; " + DEFAULT_LENGTH + " by default")
            @Nullable
            Integer length) {}

    record Result(
            String field,
            int index,
            StoredType type,

            @Description("Whether value is in hex: a binary value that isn't UTF-8")
            boolean binary,

            @Description("The slice of the value") String value,
            int offset,

            @Description("The length of the whole value in characters")
            int totalLength,

            @Description("Where the next slice starts; absent at the end of the value") @Nullable
            Integer nextOffset) {}

    @Override
    public String name() {
        return "get_field_value";
    }

    @Override
    public String description() {
        return "Read one stored value of a document whole, in slices of characters: for a value that get_document or"
                + " search cut. Call it again with nextOffset until it is absent.";
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
        int index = Slices.bounded("index", arguments.index(), 0, 0, Integer.MAX_VALUE);
        int offset = Slices.bounded("offset", arguments.offset(), 0, 0, Integer.MAX_VALUE);
        int length = Slices.bounded("length", arguments.length(), DEFAULT_LENGTH, 1, MAX_LENGTH);
        GetStoredValueResult slice = call.call(
                Methods.GET_STORED_VALUE,
                new GetStoredValueParams(
                        arguments.docId(), arguments.field(), index, OptionalInt.of(offset), OptionalInt.of(length)));
        StoredValue stored = slice.value();
        int end = slice.offset() + stored.value().length();
        return new Result(
                arguments.field(),
                index,
                stored.type(),
                stored.binary(),
                stored.value(),
                slice.offset(),
                slice.totalLength(),
                end < slice.totalLength() ? end : null);
    }
}
