package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch;
import io.github.mfuerstenau.leia.runtime.MoreLikeThisSearch;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.StandardSearch;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.search.SearchQueries;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.lucene.util.BytesRef;

/**
 * The standard, More-Like-This and doc values queries of {@code startSearch}, {@code startFilter},
 * {@code startExplain} and {@code startHighlight} on a runtime with their groups (ADR 0066). They are
 * checked here, with the errors and messages that Lucene 10 gives ({@link SearchQueries}), before the
 * runtime gets them. As for the classic parser, the standard parser's dates are left out.
 */
final class RuntimeQueries {

    private RuntimeQueries() {}

    /**
     * {@code standard} parsed by the runtime.
     *
     * @throws RpcException {@code FIELD_NOT_FOUND} for an unknown default or point field; {@code
     *     INVALID_PARAMS} for a point type that doesn't fit its field, or a point field listed twice;
     *     {@code INVALID_QUERY} if the expression doesn't parse; {@code NOT_SUPPORTED} for an analyzer
     *     the runtime doesn't have
     */
    static Search.ParsedQuery standard(OpenRuntime open, Search search, StandardSearch group, StandardQuery standard)
            throws RpcException {
        RuntimeResults.checkField(open.index(), standard.defaultField());
        Map<String, StandardSearch.PointType> points = new LinkedHashMap<>();
        for (PointField point : standard.points()) {
            RuntimeIndex.Field field = field(open.index(), point.field());
            StandardSearch.PointType type =
                    StandardSearch.PointType.valueOf(point.type().name());
            if (field.pointDimensionCount() != 1 || field.pointNumBytes() != bytes(type)) {
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
        Search.AnalyzerSpec analyzer = RuntimeAnalyzers.spec(standard.analyzer(), open.runtime(), search);
        try {
            return group.parse(
                    standard.expression(),
                    new StandardSearch.Options(
                            standard.defaultField(),
                            standard.defaultOperator() == QueryOperator.AND,
                            standard.options().allowLeadingWildcard(),
                            standard.options().phraseSlop(),
                            standard.options().fuzzyPrefixLength(),
                            standard.options().enablePositionIncrements()),
                    points,
                    analyzer);
        } catch (Search.InvalidQueryException e) {
            throw new RpcException(ErrorCode.INVALID_QUERY, "Invalid query: " + e.getMessage(), e);
        } catch (Search.UnsupportedAnalyzerException e) {
            throw unsupported(open, e);
        }
    }

    /** How many bytes a point of {@code type} has in one dimension. */
    private static int bytes(StandardSearch.PointType type) {
        return switch (type) {
            case INT, FLOAT -> Integer.BYTES;
            case LONG, DOUBLE -> Long.BYTES;
        };
    }

    /**
     * {@code like} as the runtime builds it in the search (ADR 0013).
     *
     * @throws RpcException {@code INVALID_PARAMS} unless exactly one of {@code docId} and {@code text}
     *     is present, for a text that is too long, a field that isn't indexed, or a setting out of
     *     range; {@code DOC_NOT_FOUND} for a document outside 0 to maxDoc - 1; {@code FIELD_NOT_FOUND}
     *     for an unknown field; {@code NOT_SUPPORTED} for an analyzer the runtime doesn't have
     */
    static Search.ParsedQuery moreLikeThis(
            OpenRuntime open, Search search, MoreLikeThisSearch group, MoreLikeThisQuery like) throws RpcException {
        if (like.docId().isPresent() == like.text().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: moreLikeThis needs exactly one of docId and text");
        }
        if (like.docId().isPresent()) {
            RuntimeResults.checkDocId(open.index(), like.docId().getAsInt());
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
            if (field(open.index(), name).indexOptions() == RuntimeIndex.IndexOptions.NONE) {
                throw new RpcException(
                        ErrorCode.INVALID_PARAMS,
                        "Invalid params: the field '" + name + "' is not indexed, so it has no terms to search for");
            }
        }
        MoreLikeThisSearch.Options options;
        try {
            options = new MoreLikeThisSearch.Options(
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
        Search.AnalyzerSpec analyzer = RuntimeAnalyzers.spec(like.analyzer(), open.runtime(), search);
        MoreLikeThisSearch.Source source = like.docId().isPresent()
                ? new MoreLikeThisSearch.Source.Doc(like.docId().getAsInt())
                : new MoreLikeThisSearch.Source.Text(like.text().orElseThrow());
        try {
            return group.like(source, options, analyzer);
        } catch (Search.UnsupportedAnalyzerException e) {
            throw unsupported(open, e);
        }
    }

    /**
     * {@code query} as the runtime builds it.
     *
     * @throws RpcException {@code FIELD_NOT_FOUND} for an unknown field; {@code INVALID_PARAMS} unless
     *     exactly one of values, range and prefix is present, for a binary value that is not hex, or
     *     doc values that don't fit the field or its number encoding
     */
    static Search.ParsedQuery docValues(OpenRuntime open, DocValuesSearch group, DocValuesQuery query)
            throws RpcException {
        field(open.index(), query.field());
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
                return group.prefix(query.field(), bytes(query.prefix().get()));
            }
            if (query.number().isPresent()) {
                DocValuesSearch.Match<String> match = match(query, value -> {
                    if (value.binary()) {
                        throw new IllegalArgumentException("a number can't be binary: '" + value.value() + "'");
                    }
                    return value.value();
                });
                return group.numbers(
                        query.field(),
                        DocValuesSearch.NumberEncoding.valueOf(
                                query.number().get().name()),
                        match);
            }
            return group.bytes(query.field(), match(query, RuntimeQueries::bytes));
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
    }

    /** @throws IllegalArgumentException if {@code value} is binary and not hex */
    private static byte[] bytes(BytesValue value) {
        BytesRef bytes;
        try {
            bytes = ByteStrings.decode(value.value(), value.binary());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the binary value '" + value.value() + "' is not hex", e);
        }
        return Arrays.copyOfRange(bytes.bytes, bytes.offset, bytes.offset + bytes.length);
    }

    /** @throws IllegalArgumentException if {@code convert} does, or there are no values */
    private static <T> DocValuesSearch.Match<T> match(DocValuesQuery query, Function<BytesValue, T> convert) {
        if (query.values().isPresent()) {
            return new DocValuesSearch.Match.Values<>(
                    query.values().get().stream().map(convert).toList());
        }
        DocValuesRange range = query.range().orElseThrow();
        return new DocValuesSearch.Match.Range<>(
                range.lower().map(convert), range.upper().map(convert), range.includeLower(), range.includeUpper());
    }

    /** @throws RpcException {@code FIELD_NOT_FOUND} if the index has no field {@code name} */
    private static RuntimeIndex.Field field(RuntimeIndex index, String name) throws RpcException {
        return index.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + name + "' in the index"));
    }

    private static RpcException unsupported(OpenRuntime open, Search.UnsupportedAnalyzerException e) {
        return new RpcException(
                ErrorCode.NOT_SUPPORTED,
                "Not supported with Lucene " + open.runtime().luceneVersion() + ": " + e.getMessage(),
                e);
    }

    private static RpcException invalidParams(IllegalArgumentException e) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + e.getMessage(), e);
    }
}
