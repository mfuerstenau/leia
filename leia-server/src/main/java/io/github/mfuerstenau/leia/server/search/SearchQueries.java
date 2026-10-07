package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.InvalidQueryException;
import io.github.mfuerstenau.leia.core.Queries;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import org.apache.lucene.search.Query;

/** Turns the query of {@code startSearch} and {@code startExplain} into a Lucene query (ADR 0010). */
final class SearchQueries {

    private SearchQueries() {}

    /**
     * The Lucene query for {@code query}, parsed right away so that a syntax error fails the request.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present or for a
     *     binary term that is not hex, {@code FIELD_NOT_FOUND} for an unknown default or term field,
     *     {@code INVALID_QUERY} if the expression doesn't parse
     */
    static Query query(ReadOnlyIndex index, SearchQuery query) throws RpcException {
        if (query.classic().isPresent() == query.term().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: the query needs exactly one of classic and term");
        }
        if (query.classic().isPresent()) {
            ClassicQuery classic = query.classic().get();
            checkField(index, classic.defaultField());
            try {
                return Queries.classic(
                        classic.expression(),
                        classic.defaultField(),
                        switch (classic.defaultOperator()) {
                            case OR -> Queries.Operator.OR;
                            case AND -> Queries.Operator.AND;
                        });
            } catch (InvalidQueryException e) {
                throw invalidQuery(e);
            }
        }
        TermQuery term = query.term().orElseThrow();
        checkField(index, term.field());
        try {
            return Queries.term(
                    term.field(),
                    ByteStrings.decode(term.term().value(), term.term().binary()));
        } catch (IllegalArgumentException e) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the binary term '" + term.term().value() + "' is not hex",
                    e);
        }
    }

    static void checkField(ReadOnlyIndex index, String field) throws RpcException {
        if (!index.hasField(field)) {
            throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index");
        }
    }

    static RpcException invalidQuery(InvalidQueryException e) {
        return new RpcException(ErrorCode.INVALID_QUERY, "Invalid query: " + e.getMessage(), e);
    }
}
