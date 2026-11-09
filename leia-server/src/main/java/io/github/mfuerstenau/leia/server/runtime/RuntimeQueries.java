package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch;
import io.github.mfuerstenau.leia.runtime.MoreLikeThisSearch;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.runtime.StandardSearch;
import io.github.mfuerstenau.leia.runtime.support.Optionals;
import io.github.mfuerstenau.leia.server.ByteStrings;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.search.ParserChecks;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The standard, More-Like-This and doc values queries of {@code startSearch}, {@code startFilter},
 * {@code startExplain} and {@code startHighlight} on a runtime with their groups (ADR 0066). They are
 * checked here, with the errors and messages that Lucene 10 gives, before the
 * runtime gets them.
 */
final class RuntimeQueries {

    private RuntimeQueries() {}

    /**
     * {@code standard} parsed by the runtime.
     *
     * @throws RpcException {@code FIELD_NOT_FOUND} for an unknown default, date resolution or point
     *     field; {@code INVALID_PARAMS} for a date resolution or point field listed twice, a locale or
     *     time zone that doesn't exist, or a point type that doesn't fit its field;
     *     {@code INVALID_QUERY} if the expression doesn't parse; {@code NOT_SUPPORTED} for an analyzer
     *     the runtime doesn't have
     */
    static Search.ParsedQuery standard(OpenRuntime open, StandardSearch group, StandardQuery standard)
            throws RpcException {
        RuntimeResults.checkField(open.index(), standard.defaultField());
        ParserChecks.numbers(standard.options());
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
        Search.Dates dates = dates(open.index(), standard.options());
        Search.AnalyzerSpec analyzer = RuntimeAnalyzers.spec(standard.analyzer());
        try {
            return group.parse(
                    standard.expression(),
                    new StandardSearch.Options(
                            standard.defaultField(),
                            standard.defaultOperator() == QueryOperator.AND,
                            standard.options().allowLeadingWildcard(),
                            standard.options().phraseSlop(),
                            standard.options().fuzzyPrefixLength(),
                            standard.options().enablePositionIncrements(),
                            dates),
                    points,
                    analyzer);
        } catch (Search.InvalidQueryException e) {
            throw new RpcException(ErrorCode.INVALID_QUERY, "Invalid query: " + e.getMessage(), e);
        } catch (Search.UnsupportedAnalyzerException | Search.InvalidAnalyzerException e) {
            throw RuntimeAnalyzers.failed(open.runtime(), e);
        }
    }

    /**
     * The dates of {@code options}, checked as Lucene 10 checks them
     * ({@link ParserChecks}).
     *
     * @throws RpcException {@code FIELD_NOT_FOUND} for an unknown field of a date resolution; {@code
     *     INVALID_PARAMS} for a field listed twice, or a locale or time zone that doesn't exist
     */
    static Search.Dates dates(RuntimeIndex index, ParserOptions options) throws RpcException {
        Map<String, Search.DateResolution> fields = new LinkedHashMap<>();
        ParserChecks.fieldDateResolutions(
                        options.fieldDateResolutions(), field -> RuntimeResults.checkField(index, field))
                .forEach((field, resolution) -> fields.put(field, resolution(resolution)));
        Optional<String> localeName = options.locale();
        Optional<Locale> locale =
                localeName.isPresent() ? Optional.of(ParserChecks.locale(localeName.get())) : Optional.empty();
        Optional<String> zoneName = options.timeZone();
        Optional<ZoneId> timeZone =
                zoneName.isPresent() ? Optional.of(ParserChecks.timeZone(zoneName.get())) : Optional.empty();
        return new Search.Dates(
                options.dateResolution().map(RuntimeQueries::resolution).orElse(null),
                fields,
                locale.orElse(null),
                timeZone.orElse(null));
    }

    private static Search.DateResolution resolution(DateResolution resolution) {
        return Search.DateResolution.valueOf(resolution.name());
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
    static Search.ParsedQuery moreLikeThis(OpenRuntime open, MoreLikeThisSearch group, MoreLikeThisQuery like)
            throws RpcException {
        if (like.docId().isPresent() == like.text().isPresent()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: moreLikeThis needs exactly one of docId and text");
        }
        if (like.docId().isPresent()) {
            RuntimeResults.checkDocId(open.index(), like.docId().getAsInt());
        }
        Optional<String> text = like.text();
        if (text.isPresent()) {
            int length = text.get().getBytes(StandardCharsets.UTF_8).length;
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
                    Optionals.orNull(like.maxDocFreq()),
                    like.maxQueryTerms(),
                    like.boost(),
                    like.minWordLen(),
                    Optionals.orNull(like.maxWordLen()),
                    Set.copyOf(like.stopWords()));
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
        Search.AnalyzerSpec analyzer = RuntimeAnalyzers.spec(like.analyzer());
        MoreLikeThisSearch.Source source = like.docId().isPresent()
                ? new MoreLikeThisSearch.Source.Doc(like.docId().getAsInt())
                : new MoreLikeThisSearch.Source.Text(like.text().orElseThrow());
        try {
            return group.like(source, options, analyzer);
        } catch (Search.UnsupportedAnalyzerException | Search.InvalidAnalyzerException e) {
            throw RuntimeAnalyzers.failed(open.runtime(), e);
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
        Optional<BytesValue> prefix = query.prefix();
        Optional<DocValuesNumber> number = query.number();
        try {
            if (prefix.isPresent()) {
                if (number.isPresent()) {
                    throw new IllegalArgumentException("a prefix matches doc values of bytes, not numbers");
                }
                return group.prefix(query.field(), bytes(prefix.get()));
            }
            if (number.isPresent()) {
                DocValuesSearch.Match<String> match = match(query, value -> {
                    if (value.binary()) {
                        throw new IllegalArgumentException("a number can't be binary: '" + value.value() + "'");
                    }
                    return value.value();
                });
                return group.numbers(
                        query.field(),
                        DocValuesSearch.NumberEncoding.valueOf(number.get().name()),
                        match);
            }
            return group.bytes(query.field(), match(query, RuntimeQueries::bytes));
        } catch (IllegalArgumentException e) {
            throw invalidParams(e);
        }
    }

    /** @throws IllegalArgumentException if {@code value} is binary and not hex */
    private static byte[] bytes(BytesValue value) {
        try {
            return ByteStrings.decode(value.value(), value.binary());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the binary value '" + value.value() + "' is not hex", e);
        }
    }

    /** @throws IllegalArgumentException if {@code convert} does, or there are no values */
    private static <T> DocValuesSearch.Match<T> match(DocValuesQuery query, Function<BytesValue, T> convert) {
        Optional<List<BytesValue>> values = query.values();
        if (values.isPresent()) {
            return new DocValuesSearch.Match.Values<>(
                    values.get().stream().map(convert).toList());
        }
        DocValuesRange range = query.range().orElseThrow();
        return new DocValuesSearch.Match.Range<>(
                range.lower().map(convert).orElse(null),
                range.upper().map(convert).orElse(null),
                range.includeLower(),
                range.includeUpper());
    }

    /** @throws RpcException {@code FIELD_NOT_FOUND} if the index has no field {@code name} */
    private static RuntimeIndex.Field field(RuntimeIndex index, String name) throws RpcException {
        return index.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + name + "' in the index"));
    }

    private static RpcException invalidParams(IllegalArgumentException e) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + e.getMessage(), e);
    }
}
