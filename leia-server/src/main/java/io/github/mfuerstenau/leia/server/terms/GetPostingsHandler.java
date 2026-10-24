package io.github.mfuerstenau.leia.server.terms;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.filter.Filters;
import io.github.mfuerstenau.leia.server.filter.PostingsFilter;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.util.Optional;

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
        Optional<PostingsFilter> filter = Optional.empty();
        Optional<io.github.mfuerstenau.leia.protocol.terms.PostingsFilter> asked = params.filter();
        if (asked.isPresent()) {
            filter = Optional.of(new PostingsFilter(
                    filters.get(asked.get().filterId()), asked.get().onlyMatches()));
        }
        return RuntimeResults.postings(currentIndex.runtime(), params, filter);
    }
}
