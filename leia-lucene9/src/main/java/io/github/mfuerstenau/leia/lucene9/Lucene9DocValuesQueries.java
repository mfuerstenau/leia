package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.DocValuesSearch.Match;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch.NumberEncoding;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.jspecify.annotations.Nullable;

/**
 * Queries over the doc values of one field on Lucene 9.12, as leia-core's {@code DocValuesQueries}
 * and {@code DocValuesNumber} build them on Lucene 10 (ADR 0033, ADR 0036, ADR 0066): a copy, with the
 * same messages, as the facades are (ADR 0050 §1). A prefix of bytes is the range from the prefix up
 * to its successor.
 */
final class Lucene9DocValuesQueries {

    private Lucene9DocValuesQueries() {}

    /**
     * Documents by the numbers of a {@code NUMERIC} or {@code SORTED_NUMERIC} field.
     *
     * @throws IllegalArgumentException if the field has no number doc values, or a value is not a
     *     number of {@code number} or is NaN
     */
    static Query numbers(FieldInfo field, NumberEncoding number, Match<String> match) {
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
            case Match.Values<String> values -> {
                long[] encoded = values.values().stream()
                        .mapToLong(text -> number == NumberEncoding.LONG
                                ? parseLong(text)
                                : encode(number, parseFloating(number, text)))
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
            case Match.Range<String> range ->
                number == NumberEncoding.LONG
                        ? longRange(field, sorted, range)
                        : floatingRange(field, sorted, number, range);
        };
    }

    /**
     * Documents by the bytes of a {@code SORTED}, {@code SORTED_SET} or {@code BINARY} field.
     *
     * @throws IllegalArgumentException if the field has no doc values of bytes
     */
    static Query bytes(FieldInfo field, Match<byte[]> match) {
        return bytesRefs(field, bytesRefs(match));
    }

    /**
     * Documents by the bytes of a {@code SORTED}, {@code SORTED_SET} or {@code BINARY} field that
     * start with {@code prefix}: the range from {@code prefix} up to, without, its {@link #successor}.
     * Empty bytes match any value: the range {@code [* TO *]}.
     *
     * @throws IllegalArgumentException if the field has no doc values of bytes
     */
    static Query prefix(FieldInfo field, byte[] prefix) {
        BytesRef bytes = new BytesRef(prefix);
        if (bytes.length == 0) {
            return bytesRefs(field, new Match.Range<>(null, null, true, true));
        }
        return bytesRefs(field, new Match.Range<>(bytes, successor(bytes).orElse(null), true, false));
    }

    private static Match<BytesRef> bytesRefs(Match<byte[]> match) {
        return switch (match) {
            case Match.Values<byte[]> values ->
                new Match.Values<>(values.values().stream().map(BytesRef::new).toList());
            case Match.Range<byte[]> range ->
                new Match.Range<>(
                        Optional.ofNullable(range.lower()).map(BytesRef::new).orElse(null),
                        Optional.ofNullable(range.upper()).map(BytesRef::new).orElse(null),
                        range.includeLower(),
                        range.includeUpper());
        };
    }

    private static Query bytesRefs(FieldInfo field, Match<BytesRef> match) {
        DocValuesType type = field.getDocValuesType();
        return switch (type) {
            case SORTED, SORTED_SET -> {
                boolean set = type == DocValuesType.SORTED_SET;
                yield switch (match) {
                    case Match.Values<BytesRef> values
                    when values.values().size() == 1 ->
                        set
                                ? SortedSetDocValuesField.newSlowExactQuery(
                                        field.name, values.values().getFirst())
                                : SortedDocValuesField.newSlowExactQuery(
                                        field.name, values.values().getFirst());
                    case Match.Values<BytesRef> values ->
                        set
                                ? SortedSetDocValuesField.newSlowSetQuery(field.name, values.values())
                                : SortedDocValuesField.newSlowSetQuery(field.name, values.values());
                    case Match.Range<BytesRef> range -> {
                        BytesRef lower = range.lower();
                        BytesRef upper = range.upper();
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
     * The least bytes above every bytes that start with {@code prefix}: the prefix without its
     * trailing {@code 0xff} bytes, with its last byte one up. Empty if no byte is left.
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

    private static Query longRange(FieldInfo field, boolean sorted, Match.Range<String> range) {
        long lower = Long.MIN_VALUE;
        long upper = Long.MAX_VALUE;
        Optional<String> from = Optional.ofNullable(range.lower());
        if (from.isPresent()) {
            lower = parseLong(from.get());
            if (!range.includeLower()) {
                if (lower == Long.MAX_VALUE) {
                    return none(field);
                }
                lower++;
            }
        }
        Optional<String> to = Optional.ofNullable(range.upper());
        if (to.isPresent()) {
            upper = parseLong(to.get());
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
     * range of longs. Raw bits order negative numbers backwards, so the range is split at zero, as
     * leia-core splits it. An open bound takes in NaN too, whose bits lie beyond the infinities.
     */
    private static Query floatingRange(
            FieldInfo field, boolean sorted, NumberEncoding number, Match.Range<String> range) {
        @Nullable Double lower = range.lower() == null ? null : parseFloating(number, range.lower());
        @Nullable Double upper = range.upper() == null ? null : parseFloating(number, range.upper());
        if (lower != null && !range.includeLower()) {
            if (lower == Double.POSITIVE_INFINITY) {
                return none(field);
            }
            lower = nextUp(number, lower);
        }
        if (upper != null && !range.includeUpper()) {
            if (upper == Double.NEGATIVE_INFINITY) {
                return none(field);
            }
            upper = nextDown(number, upper);
        }
        if (lower != null && upper != null && Double.compare(lower, upper) > 0) {
            return none(field);
        }
        if (!raw(number)) {
            return longRange(
                    field,
                    sorted,
                    lower == null ? Long.MIN_VALUE : encode(number, lower),
                    upper == null ? Long.MAX_VALUE : encode(number, upper));
        }
        return rawRange(field, sorted, number, lower, upper);
    }

    /**
     * A range of raw float bits: the positive and the negative values each are a range of longs, the
     * negative ones reversed.
     */
    private static Query rawRange(
            FieldInfo field, boolean sorted, NumberEncoding number, @Nullable Double lower, @Nullable Double upper) {
        BooleanQuery.Builder parts = new BooleanQuery.Builder();
        if (upper == null || Double.compare(upper, 0.0) >= 0) {
            double from = lower != null && Double.compare(lower, 0.0) > 0 ? lower : 0.0;
            parts.add(
                    longRange(
                            field,
                            sorted,
                            encode(number, from),
                            upper == null ? Long.MAX_VALUE : encode(number, upper)),
                    Occur.SHOULD);
        }
        if (lower == null || Double.compare(lower, -0.0) <= 0) {
            double from = upper != null && Double.compare(upper, -0.0) < 0 ? upper : -0.0;
            parts.add(
                    longRange(field, sorted, encode(number, from), lower == null ? -1L : encode(number, lower)),
                    Occur.SHOULD);
        }
        BooleanQuery query = parts.build();
        return query.clauses().size() == 1 ? query.clauses().getFirst().getQuery() : query;
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

    /** Whether the doc values hold the raw bits, which don't order negative numbers as numbers. */
    private static boolean raw(NumberEncoding number) {
        return number == NumberEncoding.DOUBLE || number == NumberEncoding.FLOAT;
    }

    private static boolean doubles(NumberEncoding number) {
        return number == NumberEncoding.DOUBLE || number == NumberEncoding.SORTABLE_DOUBLE;
    }

    /**
     * The number of {@code text}: a double or a float (widened).
     *
     * @throws IllegalArgumentException if {@code text} is not such a number, or is NaN
     */
    private static double parseFloating(NumberEncoding number, String text) {
        double value;
        try {
            value = doubles(number) ? Double.parseDouble(text) : Float.parseFloat(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a " + number.name(), e);
        }
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("NaN can't be searched for");
        }
        return value;
    }

    /** @throws IllegalArgumentException if {@code text} is not a long */
    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a LONG", e);
        }
    }

    /** The doc value that encodes {@code value}, which a float format holds as a float. */
    private static long encode(NumberEncoding number, double value) {
        return switch (number) {
            case LONG -> throw new IllegalStateException("a LONG is no floating point number");
            case DOUBLE -> Double.doubleToRawLongBits(value);
            case SORTABLE_DOUBLE -> NumericUtils.doubleToSortableLong(value);
            case FLOAT -> Float.floatToRawIntBits((float) value);
            case SORTABLE_FLOAT -> NumericUtils.floatToSortableInt((float) value);
        };
    }

    /** The next number up in this format, keeping {@code -0.0} and {@code 0.0} apart. */
    private static double nextUp(NumberEncoding number, double value) {
        return doubles(number) ? DoublePoint.nextUp(value) : FloatPoint.nextUp((float) value);
    }

    /** The next number down in this format, keeping {@code -0.0} and {@code 0.0} apart. */
    private static double nextDown(NumberEncoding number, double value) {
        return doubles(number) ? DoublePoint.nextDown(value) : FloatPoint.nextDown((float) value);
    }

    /**
     * Documents whose {@code BINARY} doc value is one of several values, or lies in a range: a copy
     * of leia-core's {@code BinaryDocValuesQuery}. Lucene has no such query: it reads the value of
     * every document that has one.
     */
    private static final class BinaryDocValuesQuery extends Query {

        private final String field;
        private final @Nullable Set<BytesRef> values;
        private final @Nullable BytesRef lower;
        private final @Nullable BytesRef upper;
        private final boolean includeLower;
        private final boolean includeUpper;

        BinaryDocValuesQuery(String field, Match<BytesRef> match) {
            this.field = field;
            switch (match) {
                case Match.Values<BytesRef> exact -> {
                    values = exact.values().stream().map(BytesRef::deepCopyOf).collect(Collectors.toUnmodifiableSet());
                    lower = null;
                    upper = null;
                    includeLower = false;
                    includeUpper = false;
                }
                case Match.Range<BytesRef> range -> {
                    values = null;
                    lower = range.lower() == null ? null : BytesRef.deepCopyOf(range.lower());
                    upper = range.upper() == null ? null : BytesRef.deepCopyOf(range.upper());
                    includeLower = range.includeLower();
                    includeUpper = range.includeUpper();
                }
            }
        }

        private boolean matches(BytesRef value) {
            if (values != null) {
                return values.contains(value);
            }
            if (lower != null) {
                int order = value.compareTo(lower);
                if (order < 0 || (order == 0 && !includeLower)) {
                    return false;
                }
            }
            if (upper != null) {
                int order = value.compareTo(upper);
                return order < 0 || (order == 0 && includeUpper);
            }
            return true;
        }

        /** {@inheritDoc} On 9.12, a {@link ConstantScoreWeight} gives its scorer itself. */
        @Override
        public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
            return new ConstantScoreWeight(this, boost) {
                @Override
                public @Nullable Scorer scorer(LeafReaderContext context) throws IOException {
                    BinaryDocValues docValues = context.reader().getBinaryDocValues(field);
                    if (docValues == null) {
                        return null;
                    }
                    TwoPhaseIterator iterator = new TwoPhaseIterator(docValues) {
                        @Override
                        public boolean matches() throws IOException {
                            return BinaryDocValuesQuery.this.matches(docValues.binaryValue());
                        }

                        @Override
                        public float matchCost() {
                            // Reading the value, then comparing it.
                            return 10;
                        }
                    };
                    return new ConstantScoreScorer(this, score(), scoreMode, iterator);
                }

                @Override
                public boolean isCacheable(LeafReaderContext context) {
                    return DocValues.isCacheable(context, field);
                }
            };
        }

        @Override
        public void visit(QueryVisitor visitor) {
            if (visitor.acceptField(field)) {
                visitor.visitLeaf(this);
            }
        }

        @Override
        public String toString(String defaultField) {
            StringBuilder text = new StringBuilder();
            if (!field.equals(defaultField)) {
                text.append(field).append(':');
            }
            if (values != null) {
                List<String> sorted =
                        values.stream().sorted().map(BytesRef::toString).toList();
                return text.append(sorted.size() == 1 ? sorted.getFirst() : "(" + String.join(" ", sorted) + ")")
                        .toString();
            }
            return text.append(includeLower ? '[' : '{')
                    .append(lower == null ? "*" : lower.toString())
                    .append(" TO ")
                    .append(upper == null ? "*" : upper.toString())
                    .append(includeUpper ? ']' : '}')
                    .toString();
        }

        @Override
        public boolean equals(Object other) {
            return sameClassAs(other)
                    && other instanceof BinaryDocValuesQuery query
                    && field.equals(query.field)
                    && Objects.equals(values, query.values)
                    && Objects.equals(lower, query.lower)
                    && Objects.equals(upper, query.upper)
                    && includeLower == query.includeLower
                    && includeUpper == query.includeUpper;
        }

        @Override
        public int hashCode() {
            return Objects.hash(classHash(), field, values, lower, upper, includeLower, includeUpper);
        }
    }
}
