package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.core.IndexDocument;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocValues;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.GetIndexStatsHandler;
import jakarta.inject.Inject;
import java.io.IOException;

/** {@code getDocument}: what a document of the {@link CurrentIndex} holds, field by field. */
public final class GetDocumentHandler implements MethodHandler<GetDocumentParams, GetDocumentResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetDocumentHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetDocumentParams, GetDocumentResult> method() {
        return Methods.GET_DOCUMENT;
    }

    @Override
    public GetDocumentResult handle(GetDocumentParams params) throws RpcException {
        ReadOnlyIndex index = openIndex(currentIndex);
        int docId = params.docId();
        checkDocId(index, docId);
        try {
            return toResult(index.document(docId));
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read document " + docId + ": " + e.getMessage(), e);
        }
    }

    public static ReadOnlyIndex openIndex(CurrentIndex currentIndex) throws RpcException {
        return currentIndex.get().orElseThrow(() -> new RpcException(ErrorCode.NO_INDEX_OPEN, "No index is open"));
    }

    public static void checkDocId(ReadOnlyIndex index, int docId) throws RpcException {
        if (!index.hasDocId(docId)) {
            throw new RpcException(
                    ErrorCode.DOC_NOT_FOUND,
                    "No document " + docId + " in the index: maxDoc is "
                            + index.summary().maxDoc());
        }
    }

    private static GetDocumentResult toResult(IndexDocument doc) {
        return new GetDocumentResult(
                doc.docId(),
                doc.deleted(),
                doc.fields().stream().map(GetDocumentHandler::toField).toList());
    }

    private static DocumentField toField(IndexDocument.Field field) {
        return new DocumentField(
                field.name(),
                field.norm(),
                field.stored().stream().map(GetDocumentHandler::storedValue).toList(),
                field.docValues().map(GetDocumentHandler::docValues),
                field.hasTermVector());
    }

    static StoredValue storedValue(IndexDocument.StoredValue value) {
        return switch (value) {
            case IndexDocument.StoredValue.Text(String text) -> new StoredValue(StoredType.STRING, text, false);
            case IndexDocument.StoredValue.Binary(var bytes) -> {
                ByteStrings.Encoded encoded = ByteStrings.encode(bytes);
                yield new StoredValue(StoredType.BINARY, encoded.value(), encoded.binary());
            }
            case IndexDocument.StoredValue.IntValue(int number) ->
                new StoredValue(StoredType.INT, Integer.toString(number), false);
            case IndexDocument.StoredValue.LongValue(long number) ->
                new StoredValue(StoredType.LONG, Long.toString(number), false);
            case IndexDocument.StoredValue.FloatValue(float number) ->
                new StoredValue(StoredType.FLOAT, Float.toString(number), false);
            case IndexDocument.StoredValue.DoubleValue(double number) ->
                new StoredValue(StoredType.DOUBLE, Double.toString(number), false);
        };
    }

    private static DocValues docValues(IndexDocument.DocValues values) {
        return new DocValues(
                GetIndexStatsHandler.docValuesType(values.type()),
                values.numbers(),
                values.values().stream()
                        .map(ByteStrings::encode)
                        .map(encoded -> new BytesValue(encoded.value(), encoded.binary()))
                        .toList());
    }
}
