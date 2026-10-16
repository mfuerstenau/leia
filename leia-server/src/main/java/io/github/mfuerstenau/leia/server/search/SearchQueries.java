package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.ClassicOptions;
import io.github.mfuerstenau.leia.core.DocValuesMatch;
import io.github.mfuerstenau.leia.core.DocValuesNumber;
import io.github.mfuerstenau.leia.core.DocValuesQueries;
import io.github.mfuerstenau.leia.core.InvalidQueryException;
import io.github.mfuerstenau.leia.core.KnnQueries;
import io.github.mfuerstenau.leia.core.KnnVector;
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
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.FieldDateResolution;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.BytesRef;

/**
 * Turns the query, the similarity and the sort of {@code startSearch} and {@code startExplain} into
 * their Lucene counterparts (ADR 0010 to ADR 0014, ADR 0025, ADR 0033, ADR 0040). {@code startFilter} takes the query
 * too (ADR 0031).
 */
public final class SearchQueries {

    private SearchQueries() {}

    /**
     * A Lucene query and the analyzer that it still needs while it runs: More-Like-This analyzes in
     * the search. The job closes it once the query has run.
     */
    public record AnalyzedQuery(Query query, Optional<Analyzer> analyzer) implements AutoCloseable {

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
     *     parser option or More-Like-This setting out of range, a field whose date resolution is
     *     listed twice, a point type that doesn't fit its
     *     field, a More-Like-This field that isn't indexed, a binary term that is not hex, or doc
     *     values that don't fit the field or its number encoding, or a KNN target or k that doesn't
     *     fit (ADR 0040); {@code FIELD_NOT_FOUND} for an unknown default, date resolution, term, point,
     *     More-Like-This, doc values or KNN field; {@code DOC_NOT_FOUND} for a More-Like-This or KNN
     *     document outside 0 to maxDoc - 1; {@code INVALID_QUERY} if the expression or the KNN filter
     *     doesn't parse; {@code INDEX_NOT_READABLE} if a KNN document's vector can't be read; {@code INVALID_ANALYZER} if the query's analyzer can't be
     *     built
     */
    public static AnalyzedQuery query(ReadOnlyIndex index, SearchQuery query) throws RpcException {
        long present = Stream.of(
                        query.classic(),
                        query.standard(),
                        query.term(),
                        query.moreLikeThis(),
                        query.docValues(),
                        query.knn())
                .filter(alternative -> alternative.isPresent())
                .count();
        if (present != 1) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: the query needs exactly one of classic, standard, term, moreLikeThis,"
                            + " docValues and knn");
        }
        if (query.docValues().isPresent()) {
            return new AnalyzedQuery(docValues(index, query.docValues().get()), Optional.empty());
        }
        if (query.knn().isPresent()) {
            return new AnalyzedQuery(knn(index, query.knn().get()), Optional.empty());
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
        ParserOptions options = options(index, classic.defaultOperator(), classic.options());
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
        ParserOptions options = options(index, standard.defaultOperator(), standard.options());
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

    private static Query docValues(ReadOnlyIndex index, DocValuesQuery query) throws RpcException {
        FieldInfo field = index.fieldInfo(query.field())
                .orElseThrow(() ->
                        new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + query.field() + "' in the index"));
        int matches = (query.values().isPresent() ? 1 : 0)
                + (query.range().isPresent() ? 1 : 0)
                + (query.prefix().isPresent() ? 1 : 0);
        if (matches != 1) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: docValues needs exactly one of values, range and prefix");
        }
        try {
            if (query.prefix().isPresent()) {
                if (query.number().isPresent()) {
                    throw new IllegalArgumentException("a prefix matches doc values of bytes, not numbers");
                }
                return DocValuesQueries.prefix(field, bytes(query.prefix().get()));
            }
            if (query.number().isPresent()) {
                DocValuesMatch<String> match = docValuesMatch(query, value -> {
                    if (value.binary()) {
                        throw new IllegalArgumentException("a number can't be binary: '" + value.value() + "'");
                    }
                    return value.value();
                });
                return DocValuesQueries.numbers(
                        field, DocValuesNumber.valueOf(query.number().get().name()), match);
            }
            DocValuesMatch<BytesRef> match = docValuesMatch(query, SearchQueries::bytes);
            return DocValuesQueries.bytes(field, match);
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
    }

    private static Query knn(ReadOnlyIndex index, KnnQuery knn) throws RpcException {
        FieldInfo field = index.fieldInfo(knn.field())
                .orElseThrow(() ->
                        new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + knn.field() + "' in the index"));
        KnnChecks.check(knn, field.getVectorDimension());
        KnnVector target;
        try {
            target = knn.vector().isPresent() ? vector(knn.vector().get()) : documentVector(index, knn);
            KnnQueries.check(field, target);
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
        Optional<Query> filter;
        try {
            filter = knn.filter().isPresent()
                    ? Optional.of(classic(index, knn.filter().get()))
                    : Optional.empty();
        } catch (InvalidQueryException e) {
            throw invalidQuery(e);
        }
        return knn.exact()
                ? KnnQueries.exact(field, target, filter)
                : KnnQueries.approximate(field, target, knn.k().getAsInt(), filter);
    }

    /** @throws IllegalArgumentException if the document has no vector in the field */
    private static KnnVector documentVector(ReadOnlyIndex index, KnnQuery knn) throws RpcException {
        int docId = knn.docId().getAsInt();
        GetDocumentHandler.checkDocId(index, docId);
        try {
            return index.vector(docId, knn.field())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "document " + docId + " has no vector in the field '" + knn.field() + "'"));
        } catch (IOException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE, "Cannot read document " + docId + ": " + e.getMessage(), e);
        }
    }

    /** The vector as core takes it. */
    private static KnnVector vector(io.github.mfuerstenau.leia.protocol.document.KnnVector vector) {
        return switch (vector.encoding()) {
            case FLOAT32 -> {
                float[] values = new float[vector.floats().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = vector.floats().get(i);
                }
                yield new KnnVector.Floats(values);
            }
            case BYTE -> {
                byte[] values = new byte[vector.bytes().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = vector.bytes().get(i);
                }
                yield new KnnVector.Bytes(values);
            }
        };
    }

    /** @throws IllegalArgumentException if {@code value} is binary and not hex */
    private static BytesRef bytes(BytesValue value) {
        try {
            return ByteStrings.decode(value.value(), value.binary());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the binary value '" + value.value() + "' is not hex", e);
        }
    }

    /** @throws IllegalArgumentException if {@code convert} does, or there are no values */
    private static <T> DocValuesMatch<T> docValuesMatch(DocValuesQuery query, Function<BytesValue, T> convert) {
        if (query.values().isPresent()) {
            return new DocValuesMatch.Values<>(
                    query.values().get().stream().map(convert).toList());
        }
        DocValuesRange range = query.range().orElseThrow();
        return new DocValuesMatch.Range<>(
                range.lower().map(convert), range.upper().map(convert), range.includeLower(), range.includeUpper());
    }

    private static AnalyzedQuery moreLikeThis(ReadOnlyIndex index, MoreLikeThisQuery like) throws RpcException {
        if (like.docId().isPresent() == like.text().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: moreLikeThis needs exactly one of docId and text");
        }
        if (like.docId().isPresent()) {
            GetDocumentHandler.checkDocId(index, like.docId().getAsInt());
        }
        if (like.text().isPresent()) {
            int length = like.text().get().getBytes(StandardCharsets.UTF_8).length;
            if (length > MoreLikeThisQuery.MAX_TEXT_LENGTH) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: the text has " + length + " bytes, more than "
                                + MoreLikeThisQuery.MAX_TEXT_LENGTH);
            }
        }
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
                    like.boost(),
                    like.minWordLen(),
                    like.maxWordLen(),
                    Set.copyOf(like.stopWords()));
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
        Analyzer analyzer = AnalyzerSpecs.build(like.analyzer());
        Query query = like.docId().isPresent()
                ? Queries.moreLikeThis(like.docId().getAsInt(), options, analyzer)
                : Queries.moreLikeText(like.text().orElseThrow(), options, analyzer);
        return new AnalyzedQuery(query, Optional.of(analyzer));
    }

    private static ParserOptions options(
            ReadOnlyIndex index,
            QueryOperator operator,
            io.github.mfuerstenau.leia.protocol.search.ParserOptions options)
            throws RpcException {
        Map<String, DateTools.Resolution> fieldDateResolutions = new LinkedHashMap<>();
        for (FieldDateResolution field : options.fieldDateResolutions()) {
            checkField(index, field.field());
            if (fieldDateResolutions.put(
                            field.field(),
                            DateTools.Resolution.valueOf(field.resolution().name()))
                    != null) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: the field '" + field.field() + "' has its date resolution listed twice");
            }
        }
        try {
            return new ParserOptions(
                    switch (operator) {
                        case OR -> Queries.Operator.OR;
                        case AND -> Queries.Operator.AND;
                    },
                    options.allowLeadingWildcard(),
                    options.phraseSlop(),
                    options.fuzzyPrefixLength(),
                    options.enablePositionIncrements(),
                    options.dateResolution().map(resolution -> DateTools.Resolution.valueOf(resolution.name())),
                    fieldDateResolutions,
                    options.locale().map(SearchQueries::locale),
                    options.timeZone().map(SearchQueries::timeZone));
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
    }

    /** @throws IllegalArgumentException if {@code tag} is not a BCP 47 language tag */
    private static Locale locale(String tag) {
        try {
            if (tag.isBlank()) {
                throw new IllformedLocaleException("blank");
            }
            return new Locale.Builder().setLanguageTag(tag).build();
        } catch (IllformedLocaleException e) {
            throw new IllegalArgumentException("'" + tag + "' is not a BCP 47 language tag, such as en-US", e);
        }
    }

    /**
     * Not {@code TimeZone.getTimeZone(String)}, which takes an unknown id for GMT.
     *
     * @throws IllegalArgumentException if {@code id} is not a time zone
     */
    private static ZoneId timeZone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "'" + id + "' is not a time zone, such as UTC, Europe/Berlin or +02:00", e);
        }
    }

    public static void checkField(ReadOnlyIndex index, String field) throws RpcException {
        if (!index.hasField(field)) {
            throw new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index");
        }
    }

    public static RpcException invalidQuery(InvalidQueryException e) {
        return new RpcException(ErrorCode.INVALID_QUERY, "Invalid query: " + e.getMessage(), e);
    }

    private static RpcException invalidParams(IllegalArgumentException e) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + e.getMessage(), e);
    }
}
