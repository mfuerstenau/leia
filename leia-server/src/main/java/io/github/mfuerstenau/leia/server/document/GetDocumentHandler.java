package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.util.OptionalLong;

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
        return RuntimeResults.document(currentIndex.runtime(), params);
    }

    /** The whole {@code value}, as {@code getStoredValue} sends it. */
    static StoredValue storedValue(RuntimeIndex.StoredValue value) {
        return storedValue(value, Integer.MAX_VALUE);
    }

    /**
     * {@code value} as it goes into the result, with text and bytes cut to at most {@code maxLength} bytes
     * (ADR 0008). Text is cut between two characters, so the cut text is still valid UTF-8.
     */
    public static StoredValue storedValue(RuntimeIndex.StoredValue value, int maxLength) {
        return switch (value) {
            case RuntimeIndex.StoredValue.Text(String text) -> text(StoredType.STRING, text, maxLength);
            case RuntimeIndex.StoredValue.Binary(byte[] bytes) -> {
                ByteStrings.Encoded encoded = ByteStrings.encode(bytes);
                if (!encoded.binary()) {
                    yield text(StoredType.BINARY, encoded.value(), maxLength);
                }
                // Cutting the bytes first could leave valid UTF-8, which would then go as text.
                yield bytes.length <= maxLength
                        ? new StoredValue(StoredType.BINARY, encoded.value(), true)
                        : new StoredValue(
                                StoredType.BINARY,
                                ByteStrings.hex(bytes, maxLength),
                                true,
                                OptionalLong.of(bytes.length));
            }
            case RuntimeIndex.StoredValue.Numeric(Integer number) ->
                new StoredValue(StoredType.INT, Integer.toString(number), false);
            case RuntimeIndex.StoredValue.Numeric(Long number) ->
                new StoredValue(StoredType.LONG, Long.toString(number), false);
            case RuntimeIndex.StoredValue.Numeric(Float number) ->
                new StoredValue(StoredType.FLOAT, Float.toString(number), false);
            // A double, or a number of a type that a runtime's Lucene has and Lucene 10 hasn't.
            case RuntimeIndex.StoredValue.Numeric(Number number) ->
                new StoredValue(StoredType.DOUBLE, Double.toString(number.doubleValue()), false);
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
}
