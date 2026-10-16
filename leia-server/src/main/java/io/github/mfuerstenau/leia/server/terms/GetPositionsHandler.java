package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.TermPosition;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.document.GetTermVectorHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import org.apache.lucene.util.BytesRef;

/** {@code getPositions}: where a term occurs in a document of the {@link CurrentIndex}. */
public final class GetPositionsHandler implements MethodHandler<GetPositionsParams, GetPositionsResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetPositionsHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetPositionsParams, GetPositionsResult> method() {
        return Methods.GET_POSITIONS;
    }

    @Override
    public GetPositionsResult handle(GetPositionsParams params) throws RpcException {
        if (currentIndex.runtime().isPresent()) {
            return RuntimeResults.positions(currentIndex.runtime().get(), params);
        }
        BytesRef term = TermParams.term(params.term());
        ReadOnlyIndex index = currentIndex.readable();
        int docId = params.docId();
        String field = params.field();
        GetDocumentHandler.checkDocId(index, docId);
        TermParams.checkField(index, field);
        String termText = TermParams.text(params.term());
        try {
            List<TermPosition> positions = index.positions(docId, field, term)
                    .orElseThrow(() -> new RpcException(
                            ErrorCode.TERM_NOT_FOUND,
                            "Document " + docId + " does not contain '" + termText + "' in field '" + field + "'"));
            return new GetPositionsResult(
                    positions.stream().map(GetTermVectorHandler::position).toList());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the positions of '" + termText + "' in document " + docId + ", field '" + field + "': "
                            + e.getMessage(),
                    e);
        }
    }
}
