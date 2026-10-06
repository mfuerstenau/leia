package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.core.IndexDocument;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;

/** {@code getStoredValue}: one stored value of a document of the {@link CurrentIndex}, whole. */
public final class GetStoredValueHandler implements MethodHandler<GetStoredValueParams, GetStoredValueResult> {

    private final CurrentIndex currentIndex;

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
        ReadOnlyIndex index = GetDocumentHandler.openIndex(currentIndex);
        int docId = params.docId();
        String field = params.field();
        GetDocumentHandler.checkDocId(index, docId);
        if (!index.hasField(field)) {
            throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index");
        }
        List<IndexDocument.StoredValue> values;
        try {
            values = index.storedValues(docId, field);
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read document " + docId + ", field '" + field + "': " + e.getMessage(),
                    e);
        }
        int i = params.index();
        if (i < 0 || i >= values.size()) {
            throw new RpcException(
                    ErrorCode.VALUE_NOT_FOUND,
                    "No stored value " + i + " in field '" + field + "' of document " + docId + ": it has "
                            + values.size());
        }
        return new GetStoredValueResult(GetDocumentHandler.storedValue(values.get(i)));
    }
}
