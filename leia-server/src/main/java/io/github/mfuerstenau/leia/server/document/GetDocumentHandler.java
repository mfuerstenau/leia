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
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.GetIndexStatsHandler;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.apache.lucene.util.BytesRef;

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
        if (currentIndex.runtime().isPresent()) {
            return RuntimeResults.document(currentIndex.runtime().get(), params);
        }
        ReadOnlyIndex index = currentIndex.readable();
        int docId = params.docId();
        checkDocId(index, docId);
        try {
            return toResult(index.document(docId));
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read document " + docId + ": " + e.getMessage(), e);
        }
    }

    public static void checkDocId(ReadOnlyIndex index, int docId) throws RpcException {
        if (!index.hasDocId(docId)) {
            throw new RpcException(
                    ErrorCode.DOC_NOT_FOUND, "No document " + docId + " in the index: maxDoc is " + index.maxDoc());
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
                field.stored().stream()
                        .map(value -> storedValue(value, GetDocumentResult.MAX_VALUE_LENGTH))
                        .toList(),
                field.docValues().map(GetDocumentHandler::docValues),
                field.hasTermVector(),
                field.vector().map(GetDocumentHandler::vector));
    }

    /** A vector as the protocol carries it (ADR 0040). */
    public static KnnVector vector(io.github.mfuerstenau.leia.core.KnnVector vector) {
        return switch (vector) {
            case io.github.mfuerstenau.leia.core.KnnVector.Floats floats -> {
                List<Float> values = new ArrayList<>(floats.values().length);
                for (float value : floats.values()) {
                    values.add(value);
                }
                yield new KnnVector(VectorEncoding.FLOAT32, List.copyOf(values), List.of());
            }
            case io.github.mfuerstenau.leia.core.KnnVector.Bytes bytes -> {
                List<Byte> values = new ArrayList<>(bytes.values().length);
                for (byte value : bytes.values()) {
                    values.add(value);
                }
                yield new KnnVector(VectorEncoding.BYTE, List.of(), List.copyOf(values));
            }
        };
    }

    /** The whole {@code value}, as {@code getStoredValue} sends it. */
    static StoredValue storedValue(IndexDocument.StoredValue value) {
        return storedValue(value, Integer.MAX_VALUE);
    }

    /**
     * {@code value} as it goes into the result, with text and bytes cut to at most {@code maxLength} bytes
     * (ADR 0008). Text is cut between two characters, so the cut text is still valid UTF-8.
     */
    public static StoredValue storedValue(IndexDocument.StoredValue value, int maxLength) {
        return switch (value) {
            case IndexDocument.StoredValue.Text(String text) -> text(StoredType.STRING, text, maxLength);
            case IndexDocument.StoredValue.Binary(BytesRef bytes) -> {
                ByteStrings.Encoded encoded = ByteStrings.encode(bytes);
                if (!encoded.binary()) {
                    yield text(StoredType.BINARY, encoded.value(), maxLength);
                }
                // Cutting the bytes first could leave valid UTF-8, which would then go as text.
                yield bytes.length <= maxLength
                        ? new StoredValue(StoredType.BINARY, encoded.value(), true)
                        : new StoredValue(
                                StoredType.BINARY,
                                ByteStrings.hex(new BytesRef(bytes.bytes, bytes.offset, maxLength)),
                                true,
                                OptionalLong.of(bytes.length));
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

    /** {@code text}, cut to its first {@code maxLength} bytes of UTF-8 or fewer if it is longer. */
    private static StoredValue text(StoredType type, String text, int maxLength) {
        // A UTF-16 char takes at most 3 bytes of UTF-8; a surrogate pair takes 4 for its 2 chars.
        if ((long) text.length() * 3 <= maxLength) {
            return new StoredValue(type, text, false);
        }
        long length = 0;
        int end = -1;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int bytes = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (end < 0 && length + bytes > maxLength) {
                end = i;
            }
            length += bytes;
            i += Character.charCount(codePoint);
        }
        return end < 0
                ? new StoredValue(type, text, false)
                : new StoredValue(type, text.substring(0, end), false, OptionalLong.of(length));
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
