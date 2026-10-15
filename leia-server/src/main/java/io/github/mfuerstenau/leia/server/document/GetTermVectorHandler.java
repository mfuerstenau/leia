package io.github.mfuerstenau.leia.server.document;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorResult;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.legacy.Lucene3Results;
import jakarta.inject.Inject;
import java.io.IOException;

/** {@code getTermVector}: the term vector of a document of the {@link CurrentIndex} for a field. */
public final class GetTermVectorHandler implements MethodHandler<GetTermVectorParams, GetTermVectorResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetTermVectorHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetTermVectorParams, GetTermVectorResult> method() {
        return Methods.GET_TERM_VECTOR;
    }

    @Override
    public GetTermVectorResult handle(GetTermVectorParams params) throws RpcException {
        if (currentIndex.legacy().isPresent()) {
            return Lucene3Results.termVector(currentIndex.legacy().get(), params);
        }
        ReadOnlyIndex index = currentIndex.readable();
        int docId = params.docId();
        String field = params.field();
        GetDocumentHandler.checkDocId(index, docId);
        if (!index.hasField(field)) {
            throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index");
        }
        try {
            return new GetTermVectorResult(index.termVector(docId, field).stream()
                    .map(GetTermVectorHandler::term)
                    .toList());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the term vector of document " + docId + ", field '" + field + "': " + e.getMessage(),
                    e);
        }
    }

    static TermVectorTerm term(io.github.mfuerstenau.leia.core.TermVectorTerm term) {
        ByteStrings.Encoded text = ByteStrings.encode(term.term());
        return new TermVectorTerm(
                text.value(),
                text.binary(),
                term.freq(),
                term.positions().stream().map(GetTermVectorHandler::position).toList());
    }

    public static TermPosition position(io.github.mfuerstenau.leia.core.TermPosition position) {
        return new TermPosition(
                position.position(),
                position.startOffset(),
                position.endOffset(),
                position.payload().map(ByteStrings::hex));
    }
}
