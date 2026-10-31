package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.lang.ref.SoftReference;

/**
 * {@code getStoredValue}: a slice of one stored value of a document of the {@link CurrentIndex}, or all
 * of it. No Lucene reads part of a stored string, so the handler keeps the last value it read for the
 * slices after the first (ADR 0117).
 */
public final class GetStoredValueHandler implements MethodHandler<GetStoredValueParams, GetStoredValueResult> {

    private record Last(OpenRuntime open, int docId, String field, int index, StoredValue value) {}

    private final CurrentIndex currentIndex;

    /** Soft, so that the GC may drop a large value; calls run one at a time on the backend thread. */
    private SoftReference<Last> last = new SoftReference<>(null);

    @Inject
    GetStoredValueHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetStoredValueParams, GetStoredValueResult> method() {
        return Methods.GET_STORED_VALUE;
    }

    @Override
    public GetStoredValueResult handle(GetStoredValueParams params) throws RpcException {
        int offset = params.offset().orElse(0);
        if (offset < 0) {
            throw invalid("offset must not be negative, not " + offset);
        }
        if (params.length().isPresent() && params.length().getAsInt() < 0) {
            throw invalid("length must not be negative, not " + params.length().getAsInt());
        }
        OpenRuntime open = currentIndex.runtime();
        StoredValue value = cached(open, params);
        String text = value.value();
        int total = text.length();
        if (offset > total) {
            throw invalid("offset " + offset + " is past the end of the value, " + total + " characters long");
        }
        if (value.binary() && offset % 2 != 0) {
            throw invalid("offset must be even in a value in hex, which has two characters per byte");
        }
        int end = params.length().isPresent()
                ? ValueSlices.end(text, value.binary(), offset, params.length().getAsInt())
                : total;
        if (offset == 0 && end == total) {
            return new GetStoredValueResult(value, 0, total);
        }
        return new GetStoredValueResult(
                new StoredValue(value.type(), text.substring(offset, end), value.binary()), offset, total);
    }

    private StoredValue cached(OpenRuntime open, GetStoredValueParams params) throws RpcException {
        Last hit = last.get();
        if (hit != null
                && hit.open == open
                && hit.docId == params.docId()
                && hit.index == params.index()
                && hit.field.equals(params.field())) {
            return hit.value;
        }
        StoredValue value = RuntimeResults.storedValue(open, params);
        last = new SoftReference<>(new Last(open, params.docId(), params.field(), params.index(), value));
        return value;
    }

    private static RpcException invalid(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, message);
    }
}
