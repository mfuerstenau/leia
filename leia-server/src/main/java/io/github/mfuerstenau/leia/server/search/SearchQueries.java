package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.ClassicOptions;
import io.github.mfuerstenau.leia.core.InvalidQueryException;
import io.github.mfuerstenau.leia.core.MoreLikeThisOptions;
import io.github.mfuerstenau.leia.core.ParserOptions;
import io.github.mfuerstenau.leia.core.PointType;
import io.github.mfuerstenau.leia.core.Queries;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.Similarities;
import io.github.mfuerstenau.leia.core.SortMissing;
import io.github.mfuerstenau.leia.core.SortSelector;
import io.github.mfuerstenau.leia.core.SortType;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.analysis.AnalyzerSpecs;
import io.github.mfuerstenau.leia.server.document.GetDocumentHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;

/**
 * Turns the query, the similarity and the sort of {@code startSearch} and {@code startExplain} into
 * their Lucene counterparts (ADR 0010 to ADR 0014, ADR 0025).
 */
final class SearchQueries {

    private SearchQueries() {}

    /**
     * A Lucene query and the analyzer that it still needs while it runs: More-Like-This analyzes in
     * the search. The job closes it once the query has run.
     */
    record AnalyzedQuery(Query query, Optional<Analyzer> analyzer) implements AutoCloseable {

        @Override
        public void close() {
            analyzer.ifPresent(Analyzer::close);
        }
    }

    /**
     * The Lucene query for {@code query}, parsed right away so that a syntax error fails the request.
     * A More-Like-This query is only checked here: the search builds it (ADR 0013), with the analyzer
     * that comes along, which the caller closes.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present, for a
     *     parser option or More-Like-This setting out of range, a point type that doesn't fit its
     *     field, a More-Like-This field that isn't indexed, or a binary term that is not hex; {@code
     *     FIELD_NOT_FOUND} for an unknown default, term, point or More-Like-This field; {@code
     *     DOC_NOT_FOUND} for a More-Like-This document outside 0 to maxDoc - 1; {@code INVALID_QUERY}
     *     if the expression doesn't parse; {@code INVALID_ANALYZER} if the query's analyzer can't be
     *     built
     */
    static AnalyzedQuery query(ReadOnlyIndex index, SearchQuery query) throws RpcException {
        long present = Stream.of(query.classic(), query.standard(), query.term(), query.moreLikeThis())
                .filter(alternative -> alternative.isPresent())
                .count();
        if (present != 1) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the query needs exactly one of classic, standard, term and moreLikeThis");
        }
        if (query.moreLikeThis().isPresent()) {
            return moreLikeThis(index, query.moreLikeThis().get());
        }
        try {
            if (query.classic().isPresent()) {
                return new AnalyzedQuery(classic(index, query.classic().get()), Optional.empty());
            }
            if (query.standard().isPresent()) {
                return new AnalyzedQuery(standard(index, query.standard().get()), Optional.empty());
            }
        } catch (InvalidQueryException e) {
            throw invalidQuery(e);
        }
        return new AnalyzedQuery(term(index, query.term().orElseThrow()), Optional.empty());
    }

    /**
     * The Lucene similarity for {@code similarity}.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one alternative is present, or for
     *     BM25 parameters out of range
     */
    static org.apache.lucene.search.similarities.Similarity similarity(Similarity similarity) throws RpcException {
        long present = Stream.of(similarity.bm25(), similarity.classic(), similarity.booleanSimilarity())
                .filter(alternative -> alternative.isPresent())
                .count();
        if (present != 1) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the similarity needs exactly one of bm25, classic and boolean");
        }
        if (similarity.classic().isPresent()) {
            return Similarities.classic();
        }
        if (similarity.booleanSimilarity().isPresent()) {
            return Similarities.bool();
        }
        var bm25 = similarity.bm25().orElseThrow();
        try {
            return Similarities.bm25(bm25.k1(), bm25.b());
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
    }

    /**
     * The Lucene sort for {@code keys}; empty for no keys, which sorts by score.
     *
     * @throws RpcException {@code INVALID_PARAMS} unless each key has exactly one alternative, or for
     *     a field without doc values that sort as the key's type or with the key's selector; {@code
     *     FIELD_NOT_FOUND} for an unknown field
     */
    static Optional<Sort> sort(ReadOnlyIndex index, List<SortKey> keys) throws RpcException {
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        List<SortField> fields = new ArrayList<>(keys.size());
        for (SortKey key : keys) {
            long present = Stream.of(key.field(), key.score(), key.doc())
                    .filter(alternative -> alternative.isPresent())
                    .count();
            if (present != 1) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: a sort key needs exactly one of field, score and doc");
            }
            if (key.score().isPresent()) {
                fields.add(SortType.score(key.score().get().reverse()));
            } else if (key.doc().isPresent()) {
                fields.add(SortType.doc(key.doc().get().reverse()));
            } else {
                fields.add(fieldSort(index, key.field().orElseThrow()));
            }
        }
        return Optional.of(new Sort(fields.toArray(SortField[]::new)));
    }

    private static SortField fieldSort(ReadOnlyIndex index, FieldSort sort) throws RpcException {
        FieldInfo field = index.fieldInfo(sort.field())
                .orElseThrow(() ->
                        new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + sort.field() + "' in the index"));
        try {
            return SortType.valueOf(sort.type().name())
                    .sortField(
                            field,
                            sort.reverse(),
                            SortSelector.valueOf(sort.selector().name()),
                            SortMissing.valueOf(sort.missing().name()));
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
    }

    private static Query classic(ReadOnlyIndex index, ClassicQuery classic) throws RpcException, InvalidQueryException {
        checkField(index, classic.defaultField());
        ParserOptions options = options(classic.defaultOperator(), classic.options());
        ClassicOptions classicOptions;
        try {
            classicOptions = new ClassicOptions(
                    classic.fuzzyMaxEdits(), classic.splitOnWhitespace(), classic.autoGeneratePhraseQueries());
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
        try (Analyzer analyzer = AnalyzerSpecs.build(classic.analyzer())) {
            return Queries.classic(classic.expression(), classic.defaultField(), options, classicOptions, analyzer);
        }
    }

    private static Query standard(ReadOnlyIndex index, StandardQuery standard)
            throws RpcException, InvalidQueryException {
        checkField(index, standard.defaultField());
        ParserOptions options = options(standard.defaultOperator(), standard.options());
        Map<String, PointType> points = new LinkedHashMap<>();
        for (PointField point : standard.points()) {
            FieldInfo field = index.fieldInfo(point.field())
                    .orElseThrow(() -> new RpcException(
                            ErrorCode.FIELD_NOT_FOUND, "No field '" + point.field() + "' in the index"));
            PointType type = PointType.valueOf(point.type().name());
            if (!type.fits(field)) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: the field '" + point.field() + "' has no one-dimensional points of "
                                + point.type() + " values");
            }
            if (points.put(point.field(), type) != null) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: the point field '" + point.field() + "' is listed twice");
            }
        }
        try (Analyzer analyzer = AnalyzerSpecs.build(standard.analyzer())) {
            return Queries.standard(standard.expression(), standard.defaultField(), options, points, analyzer);
        }
    }

    private static Query term(ReadOnlyIndex index, TermQuery term) throws RpcException {
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

    private static AnalyzedQuery moreLikeThis(ReadOnlyIndex index, MoreLikeThisQuery like) throws RpcException {
        GetDocumentHandler.checkDocId(index, like.docId());
        for (String name : like.fields()) {
            FieldInfo field = index.fieldInfo(name)
                    .orElseThrow(
                            () -> new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + name + "' in the index"));
            if (field.getIndexOptions() == IndexOptions.NONE) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: the field '" + name + "' is not indexed, so it has no terms to search for");
            }
        }
        MoreLikeThisOptions options;
        try {
            options = new MoreLikeThisOptions(
                    like.fields(),
                    like.minTermFreq(),
                    like.minDocFreq(),
                    like.maxDocFreq(),
                    like.maxQueryTerms(),
                    like.boost());
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
        Analyzer analyzer = AnalyzerSpecs.build(like.analyzer());
        return new AnalyzedQuery(Queries.moreLikeThis(like.docId(), options, analyzer), Optional.of(analyzer));
    }

    private static ParserOptions options(
            QueryOperator operator, io.github.mfuerstenau.leia.protocol.search.ParserOptions options)
            throws RpcException {
        try {
            return new ParserOptions(
                    switch (operator) {
                        case OR -> Queries.Operator.OR;
                        case AND -> Queries.Operator.AND;
                    },
                    options.allowLeadingWildcard(),
                    options.phraseSlop(),
                    options.fuzzyPrefixLength(),
                    options.enablePositionIncrements());
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
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

    private static RpcException invalidParams(IllegalArgumentException e) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + e.getMessage(), e);
    }
}
