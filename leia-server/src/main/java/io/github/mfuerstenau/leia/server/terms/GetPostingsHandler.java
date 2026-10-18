package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.core.PostingsPage;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import org.apache.lucene.util.BytesRef;

/**
 * {@code getPostings}: a page of the postings of a term of the {@link CurrentIndex}, through the
 * kept filter if asked (ADR 0032), on Lucene 10 or a runtime (ADR 0056).
 */
public final class GetPostingsHandler implements MethodHandler<GetPostingsParams, GetPostingsResult> {

    private final CurrentIndex currentIndex;
    private final Filters filters;

    @Inject
    GetPostingsHandler(CurrentIndex currentIndex, Filters filters) {
        this.currentIndex = currentIndex;
        this.filters = filters;
    }

    @Override
    public RpcMethod<GetPostingsParams, GetPostingsResult> method() {
        return Methods.GET_POSTINGS;
    }

    @Override
    public GetPostingsResult handle(GetPostingsParams params) throws RpcException {
        Optional<PostingsPage.Filter> filter = Optional.empty();
        if (params.filter().isPresent()) {
            filter = Optional.of(new PostingsPage.Filter(
                    filters.get(params.filter().get().filterId()),
                    params.filter().get().onlyMatches()));
        }
        if (currentIndex.runtime().isPresent()) {
            return RuntimeResults.postings(currentIndex.runtime().get(), params, filter);
        }
        TermParams.checkLimit(params.limit(), GetPostingsParams.MAX_LIMIT);
        if (params.fromDoc() < 0) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: fromDoc must not be negative, not " + params.fromDoc());
        }
        BytesRef term = TermParams.term(params.term());
        ReadOnlyIndex index = currentIndex.readable();
        String field = params.field();
        TermParams.checkField(index, field);
        try {
            PostingsPage page = index.postings(field, term, params.fromDoc(), params.limit(), filter)
                    .orElseThrow(() -> new RpcException(
                            ErrorCode.TERM_NOT_FOUND,
                            "No term '" + TermParams.text(params.term()) + "' in field '" + field + "'"));
            return new GetPostingsResult(
                    page.docFreq(),
                    page.totalTermFreq(),
                    page.postings().stream()
                            .map(posting ->
                                    new Posting(posting.docId(), posting.deleted(), posting.freq(), posting.inFilter()))
                            .toList(),
                    page.nextDoc());
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the postings of '" + TermParams.text(params.term()) + "' in field '" + field + "': "
                            + e.getMessage(),
                    e);
        }
    }
}
