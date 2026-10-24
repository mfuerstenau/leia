package io.github.mfuerstenau.leia.core;

import java.util.Optional;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;

/**
 * Builds queries over the doc values of one field (ADR 0033). They read every value of the field
 * that the doc values skipper doesn't rule out, so they are slow, but they find what was never
 * indexed as terms or points. A field with several values per document matches if any of them does.
 * A prefix of bytes is the range from the prefix up to its successor (ADR 0036).
 */
public final class DocValuesQueries {

    private DocValuesQueries() {}

    /**
     * Documents by the numbers of a {@code NUMERIC} or {@code SORTED_NUMERIC} field.
     *
     * @param number how the field encodes its numbers
     * @param match the numbers as decimal text, as {@code number} parses it
     * @throws IllegalArgumentException if the field has no number doc values, or a value is not a
     *     number of {@code number} or is NaN
     */
    public static Query numbers(FieldInfo field, DocValuesNumber number, DocValuesMatch<String> match) {
        DocValuesType type = field.getDocValuesType();
        if (type != DocValuesType.NUMERIC && type != DocValuesType.SORTED_NUMERIC) {
            throw new IllegalArgumentException(
                    type == DocValuesType.NONE
                            ? noDocValues(field)
                            : "the field '" + field.name + "' has " + type
                                    + " doc values, which hold bytes, not numbers");
        }
        boolean sorted = type == DocValuesType.SORTED_NUMERIC;
        return switch (match) {
            case DocValuesMatch.Values<String> values -> {
                long[] encoded = values.values().stream()
                        .mapToLong(text -> number == DocValuesNumber.LONG
                                ? DocValuesNumber.parseLong(text)
                                : number.encode(number.parseFloating(text)))
                        .distinct()
                        .toArray();
                if (encoded.length == 1) {
                    yield sorted
                            ? SortedNumericDocValuesField.newSlowExactQuery(field.name, encoded[0])
                            : NumericDocValuesField.newSlowExactQuery(field.name, encoded[0]);
                }
                yield sorted
                        ? SortedNumericDocValuesField.newSlowSetQuery(field.name, encoded)
                        : NumericDocValuesField.newSlowSetQuery(field.name, encoded);
            }
            case DocValuesMatch.Range<String> range ->
                number == DocValuesNumber.LONG
                        ? longRange(field, sorted, range)
                        : floatingRange(field, sorted, number, range);
        };
    }

    /**
     * Documents by the bytes of a {@code SORTED}, {@code SORTED_SET} or {@code BINARY} field. Bytes
     * compare unsigned, byte by byte.
     *
     * @throws IllegalArgumentException if the field has no doc values of bytes
     */
    public static Query bytes(FieldInfo field, DocValuesMatch<BytesRef> match) {
        DocValuesType type = field.getDocValuesType();
        return switch (type) {
            case SORTED, SORTED_SET -> {
                boolean set = type == DocValuesType.SORTED_SET;
                yield switch (match) {
                    case DocValuesMatch.Values<BytesRef> values
                    when values.values().size() == 1 ->
                        set
                                ? SortedSetDocValuesField.newSlowExactQuery(
                                        field.name, values.values().getFirst())
                                : SortedDocValuesField.newSlowExactQuery(
                                        field.name, values.values().getFirst());
                    case DocValuesMatch.Values<BytesRef> values ->
                        set
                                ? SortedSetDocValuesField.newSlowSetQuery(field.name, values.values())
                                : SortedDocValuesField.newSlowSetQuery(field.name, values.values());
                    case DocValuesMatch.Range<BytesRef> range -> {
                        BytesRef lower = range.lower().orElse(null);
                        BytesRef upper = range.upper().orElse(null);
                        yield set
                                ? SortedSetDocValuesField.newSlowRangeQuery(
                                        field.name, lower, upper, range.includeLower(), range.includeUpper())
                                : SortedDocValuesField.newSlowRangeQuery(
                                        field.name, lower, upper, range.includeLower(), range.includeUpper());
                    }
                };
            }
            case BINARY -> new BinaryDocValuesQuery(field.name, match);
            case NONE -> throw new IllegalArgumentException(noDocValues(field));
            case NUMERIC, SORTED_NUMERIC ->
                throw new IllegalArgumentException("the field '" + field.name + "' has " + type
                        + " doc values, which hold numbers: say how they are encoded");
        };
    }

    /**
     * Documents by the bytes of a {@code SORTED}, {@code SORTED_SET} or {@code BINARY} field that
     * start with {@code prefix} (ADR 0036): the range from {@code prefix} up to, without, its {@link
     * #successor}. Empty bytes match any value: the range {@code [* TO *]}.
     *
     * @throws IllegalArgumentException if the field has no doc values of bytes
     */
    public static Query prefix(FieldInfo field, BytesRef prefix) {
        if (prefix.length == 0) {
            return bytes(field, new DocValuesMatch.Range<>(Optional.empty(), Optional.empty(), true, true));
        }
        return bytes(field, new DocValuesMatch.Range<>(Optional.of(prefix), successor(prefix), true, false));
    }

    /**
     * The least bytes above every bytes that start with {@code prefix}: the prefix without its
     * trailing {@code 0xff} bytes, with its last byte one up. Empty if no byte is left, as for an
     * empty prefix, since then no bytes lie above.
     */
    static Optional<BytesRef> successor(BytesRef prefix) {
        int length = prefix.length;
        while (length > 0 && prefix.bytes[prefix.offset + length - 1] == (byte) 0xff) {
            length--;
        }
        if (length == 0) {
            return Optional.empty();
        }
        byte[] next = new byte[length];
        System.arraycopy(prefix.bytes, prefix.offset, next, 0, length);
        next[length - 1]++;
        return Optional.of(new BytesRef(next));
    }

    private static Query longRange(FieldInfo field, boolean sorted, DocValuesMatch.Range<String> range) {
        long lower = Long.MIN_VALUE;
        long upper = Long.MAX_VALUE;
        Optional<String> from = range.lower();
        if (from.isPresent()) {
            lower = DocValuesNumber.parseLong(from.get());
            if (!range.includeLower()) {
                if (lower == Long.MAX_VALUE) {
                    return none(field);
                }
                lower++;
            }
        }
        Optional<String> to = range.upper();
        if (to.isPresent()) {
            upper = DocValuesNumber.parseLong(to.get());
            if (!range.includeUpper()) {
                if (upper == Long.MIN_VALUE) {
                    return none(field);
                }
                upper--;
            }
        }
        return longRange(field, sorted, lower, upper);
    }

    /**
     * A range of floating point numbers. Sortable bits order as the numbers do, so the range is one
     * range of longs. Raw bits order negative numbers backwards, so the range is split at zero: the
     * non-negative numbers run from the bits of {@code 0.0} up, and the negative ones from the bits
     * of {@code -0.0} ({@code Long.MIN_VALUE}, or {@code Integer.MIN_VALUE} for a float) up as they
     * get smaller. An open bound takes in NaN too, whose bits lie beyond the infinities.
     */
    private static Query floatingRange(
            FieldInfo field, boolean sorted, DocValuesNumber number, DocValuesMatch.Range<String> range) {
        Optional<Double> lower = range.lower().map(number::parseFloating);
        Optional<Double> upper = range.upper().map(number::parseFloating);
        if (lower.isPresent() && !range.includeLower()) {
            if (lower.get() == Double.POSITIVE_INFINITY) {
                return none(field);
            }
            lower = Optional.of(number.nextUp(lower.get()));
        }
        if (upper.isPresent() && !range.includeUpper()) {
            if (upper.get() == Double.NEGATIVE_INFINITY) {
                return none(field);
            }
            upper = Optional.of(number.nextDown(upper.get()));
        }
        if (lower.isPresent() && upper.isPresent() && Double.compare(lower.get(), upper.get()) > 0) {
            return none(field);
        }
        if (!number.raw()) {
            return longRange(
                    field,
                    sorted,
                    lower.map(number::encode).orElse(Long.MIN_VALUE),
                    upper.map(number::encode).orElse(Long.MAX_VALUE));
        }
        return rawRange(field, sorted, number, lower, upper);
    }

    /**
     * A range of raw float bits: the positive and the negative values each are a range of longs, the
     * negative ones reversed.
     */
    private static Query rawRange(
            FieldInfo field, boolean sorted, DocValuesNumber number, Optional<Double> lower, Optional<Double> upper) {
        BooleanQuery.Builder parts = new BooleanQuery.Builder();
        if (upper.isEmpty() || Double.compare(upper.get(), 0.0) >= 0) {
            double from = lower.filter(value -> Double.compare(value, 0.0) > 0).orElse(0.0);
            parts.add(
                    longRange(
                            field,
                            sorted,
                            number.encode(from),
                            upper.map(number::encode).orElse(Long.MAX_VALUE)),
                    Occur.SHOULD);
        }
        if (lower.isEmpty() || Double.compare(lower.get(), -0.0) <= 0) {
            double from = upper.filter(value -> Double.compare(value, -0.0) < 0).orElse(-0.0);
            parts.add(
                    longRange(
                            field,
                            sorted,
                            number.encode(from),
                            lower.map(number::encode).orElse(-1L)),
                    Occur.SHOULD);
        }
        BooleanQuery query = parts.build();
        return query.clauses().size() == 1 ? query.clauses().getFirst().query() : query;
    }

    private static Query longRange(FieldInfo field, boolean sorted, long lower, long upper) {
        return sorted
                ? SortedNumericDocValuesField.newSlowRangeQuery(field.name, lower, upper)
                : NumericDocValuesField.newSlowRangeQuery(field.name, lower, upper);
    }

    private static Query none(FieldInfo field) {
        return new MatchNoDocsQuery("no value of '" + field.name + "' lies in the range");
    }

    private static String noDocValues(FieldInfo field) {
        return "the field '" + field.name + "' has no doc values";
    }
}
