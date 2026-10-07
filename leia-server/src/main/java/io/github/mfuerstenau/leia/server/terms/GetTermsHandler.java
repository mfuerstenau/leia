package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.TermPage;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import org.apache.lucene.util.BytesRef;

/** {@code getTerms}: a page of the terms of a field of the {@link CurrentIndex}. */
public final class GetTermsHandler implements MethodHandler<GetTermsParams, GetTermsResult> {

    private final CurrentIndex currentIndex;

    @Inject
    GetTermsHandler(CurrentIndex currentIndex) {
        this.currentIndex = currentIndex;
    }

    @Override
    public RpcMethod<GetTermsParams, GetTermsResult> method() {
        return Methods.GET_TERMS;
    }

    @Override
    public GetTermsResult handle(GetTermsParams params) throws RpcException {
        TermParams.checkLimit(params.limit(), GetTermsParams.MAX_LIMIT);
        Optional<BytesRef> from = params.from().isPresent()
                ? Optional.of(TermParams.term(params.from().get()))
                : Optional.empty();
        ReadOnlyIndex index = GetDocumentHandler.openIndex(currentIndex);
        String field = params.field();
        TermParams.checkField(index, field);
        try {
            TermPage page = index.terms(field, from, params.limit());
            return new GetTermsResult(
                    page.terms().stream().map(GetTermsHandler::entry).toList(),
                    page.next().map(ByteStrings::bytesValue));
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the terms of field '" + field + "': " + e.getMessage(),
                    e);
        }
    }

    private static TermEntry entry(TermPage.Entry entry) {
        ByteStrings.Encoded term = ByteStrings.encode(entry.term());
        return new TermEntry(term.value(), term.binary(), entry.docFreq(), entry.totalTermFreq());
    }
}
