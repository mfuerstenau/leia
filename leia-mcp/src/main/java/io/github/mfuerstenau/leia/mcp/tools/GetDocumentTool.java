package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** {@code get_document}: what a document holds, field by field, with long values cut (ADR 0100). */
final class GetDocumentTool implements McpTool<GetDocumentTool.Arguments, GetDocumentTool.Result> {

    static final int DEFAULT_VALUE_LENGTH = 2048;

    /** The longest {@code maxValueLength}. */
    static final int MAX_VALUE_LENGTH = 65_536;

    /** The most doc values entries a field shows. */
    static final int MAX_DOC_VALUES = 100;

    record Arguments(
            @Description("The document id, from 0 to maxDoc - 1; a deleted document can be read too")
            int docId,

            @Description("The fields to show, in the index's order; by default every field the document has"
                    + " something in")
            Optional<List<String>> fields,

            @Description("The longest value shown whole, in characters, up to " + MAX_VALUE_LENGTH + "; "
                    + DEFAULT_VALUE_LENGTH + " by default. get_field_value has a longer one whole")
            OptionalInt maxValueLength,

            @Description("Whether to show the components of KNN vectors; otherwise only their dimension; false by"
                    + " default")
            Optional<Boolean> withVectors) {}

    record Result(
            int docId,

            @Description("Whether the document is deleted; its data is there until a merge")
            boolean deleted,

            @Description("The fields, in the index's order") List<Field> fields) {}

    record Field(
            String name,

            @Description("The document's norm; absent without norms")
            OptionalLong norm,

            @Description("The stored values, in the order they were added; absent if none are stored")
            Optional<List<Value>> stored,

            @Description("The document's doc values; absent if it has none in the field")
            Optional<DocValues> docValues,

            @Description("Present, and true, only if the document has a term vector in the field")
            Optional<Boolean> hasTermVector,

            @Description("The document's KNN vector; absent if it has none in the field")
            Optional<Vector> vector) {}

    record Value(
            @Description("STRING if absent") Optional<StoredType> type,

            @Description("The value; numbers in decimal; binary values as text if they are UTF-8, else in hex")
            String value,

            @Description("Present, and true, only if value is in hex")
            Optional<Boolean> binary,

            @Description("Present if value is cut: the length of the whole value in characters; get_field_value"
                    + " has the rest")
            OptionalInt fullLength) {}

    record DocValues(
            DocValuesType type,
            @Description("How many values the document has") int count,

            @Description("The raw numbers of a NUMERIC or SORTED_NUMERIC field, at most " + MAX_DOC_VALUES
                    + "; absent for the other types, and with asStored")
            Optional<List<Long>> numbers,

            @Description("Present if the numbers are the field's stored values in this encoding (a double's bits,"
                    + " say); numbers is then left out, and the stored values are the numbers")
            Optional<DocValuesNumber> asStored,

            @Description("The values of a BINARY, SORTED or SORTED_SET field, at most " + MAX_DOC_VALUES
                    + "; absent for the other types")
            Optional<List<Bytes>> values) {}

    record Bytes(
            @Description("The bytes as text if they are UTF-8, else in hex")
            String value,

            @Description("Present, and true, only if value is in hex")
            Optional<Boolean> binary,

            @Description("Present if value is cut: the length of the whole value in characters")
            OptionalInt fullLength) {}

    record Vector(
            VectorEncoding encoding,
            int dimension,

            @Description("The components of a FLOAT32 vector, with withVectors")
            Optional<List<Float>> floats,

            @Description("The components of a BYTE vector, signed, with withVectors")
            Optional<List<Byte>> bytes) {}

    @Override
    public String name() {
        return "get_document";
    }

    @Override
    public String description() {
        return "Show a document of the open index by its id: per field its stored values, doc values, norm, KNN vector"
                + " and whether it has a term vector. Long values are cut, with their full length; get_field_value"
                + " reads one whole. Fields that are only indexed show no value: reconstruct_fields rebuilds them.";
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
        int maxLength =
                Slices.bounded("maxValueLength", arguments.maxValueLength(), DEFAULT_VALUE_LENGTH, 1, MAX_VALUE_LENGTH);
        boolean withVectors = arguments.withVectors().orElse(false);
        GetDocumentResult document = call.call(Methods.GET_DOCUMENT, new GetDocumentParams(arguments.docId()));
        List<DocumentField> fields = document.fields();
        if (arguments.fields().isPresent()) {
            Set<String> known = fields.stream().map(DocumentField::name).collect(Collectors.toSet());
            List<String> unknown = arguments.fields().get().stream()
                    .filter(name -> !known.contains(name))
                    .toList();
            if (!unknown.isEmpty()) {
                throw ToolError.invalid("The index has no field " + String.join(", ", unknown));
            }
            Set<String> asked = Set.copyOf(arguments.fields().get());
            fields = fields.stream()
                    .filter(field -> asked.contains(field.name()))
                    .toList();
        } else {
            fields = fields.stream().filter(GetDocumentTool::hasSomething).toList();
        }
        List<Field> shown = new ArrayList<>();
        for (DocumentField field : fields) {
            List<Value> stored = new ArrayList<>();
            for (int index = 0; index < field.stored().size(); index++) {
                StoredValue value = field.stored().get(index);
                if (value.fullLength().isPresent()) {
                    // Cut by the backend: the whole value gives the length in characters.
                    value = call.call(
                                    Methods.GET_STORED_VALUE,
                                    new GetStoredValueParams(document.docId(), field.name(), index))
                            .value();
                }
                stored.add(value(value, maxLength));
            }
            shown.add(new Field(
                    field.name(),
                    field.norm(),
                    Absent.nonEmpty(stored),
                    field.docValues().map(docValues -> docValues(docValues, field.stored(), maxLength)),
                    Absent.flag(field.hasTermVector()),
                    field.vector().map(vector -> vector(vector, withVectors))));
        }
        return new Result(document.docId(), document.deleted(), shown);
    }

    private static boolean hasSomething(DocumentField field) {
        return !field.stored().isEmpty()
                || field.docValues().isPresent()
                || field.vector().isPresent()
                || field.hasTermVector()
                || field.norm().isPresent();
    }

    private static Value value(StoredValue value, int maxLength) {
        String text = value.value();
        if (text.length() <= maxLength) {
            return new Value(Absent.type(value.type()), text, Absent.flag(value.binary()), OptionalInt.empty());
        }
        return new Value(
                Absent.type(value.type()),
                Slices.head(text, value.binary(), maxLength),
                Absent.flag(value.binary()),
                OptionalInt.of(text.length()));
    }

    private static DocValues docValues(
            io.github.mfuerstenau.leia.protocol.document.DocValues docValues, List<StoredValue> stored, int maxLength) {
        Function<BytesValue, Bytes> bytes = value -> value.value().length() <= maxLength
                ? new Bytes(value.value(), Absent.flag(value.binary()), OptionalInt.empty())
                : new Bytes(
                        Slices.head(value.value(), value.binary(), maxLength),
                        Absent.flag(value.binary()),
                        OptionalInt.of(value.value().length()));
        Optional<DocValuesNumber> asStored = StoredNumbers.encoding(docValues.type(), docValues.numbers(), stored);
        return new DocValues(
                docValues.type(),
                docValues.numbers().size() + docValues.values().size(),
                asStored.isPresent()
                        ? Optional.empty()
                        : Absent.nonEmpty(docValues.numbers().stream()
                                .limit(MAX_DOC_VALUES)
                                .toList()),
                asStored,
                Absent.nonEmpty(docValues.values().stream()
                        .limit(MAX_DOC_VALUES)
                        .map(bytes)
                        .toList()));
    }

    private static Vector vector(KnnVector vector, boolean withVectors) {
        boolean floats = vector.encoding() == VectorEncoding.FLOAT32;
        return new Vector(
                vector.encoding(),
                vector.dimension(),
                withVectors && floats ? Optional.of(vector.floats()) : Optional.empty(),
                withVectors && !floats ? Optional.of(vector.bytes()) : Optional.empty());
    }
}
