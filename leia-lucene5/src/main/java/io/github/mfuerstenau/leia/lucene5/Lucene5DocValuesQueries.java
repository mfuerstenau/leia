package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.DocValuesSearch.Match;
import io.github.mfuerstenau.leia.runtime.DocValuesSearch.NumberEncoding;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.DocValuesNumbersQuery;
import org.apache.lucene.search.DocValuesRangeQuery;
import org.apache.lucene.search.DocValuesTermsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;

/**
 * Queries over the doc values of one field on Lucene 5.5, as leia-core's {@code DocValuesQueries}
 * and {@code DocValuesNumber} build them on Lucene 10 (ADR 0033, ADR 0036, ADR 0066, ADR 0074): a copy
 * of 9.12's, with the same messages, as the facades are (ADR 0050 §1). 5.5 has no slow queries on
 * its fields: they are the sandbox's {@link DocValuesRangeQuery}, {@link DocValuesTermsQuery} and
 * {@link DocValuesNumbersQuery}, which read a {@code NUMERIC} field as a {@code SORTED_NUMERIC} one and
 * a {@code SORTED} field as a {@code SORTED_SET} one. A prefix of bytes is the range from the prefix up
 * to its successor.
 */
final class Lucene5DocValuesQueries {

    private Lucene5DocValuesQueries() {}

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
        return switch (match) {
            case Match.Values<String> values -> {
                long[] encoded = values.values().stream()
                        .mapToLong(text -> number == NumberEncoding.LONG
                                ? parseLong(text)
                                : encode(number, parseFloating(number, text)))
                        .distinct()
                        .toArray();
                if (encoded.length == 1) {
                    yield longRange(field, encoded[0], encoded[0]);
                }
                yield new DocValuesNumbersQuery(
                        field.name, LongStream.of(encoded).boxed().collect(Collectors.toSet()));
            }
            case Match.Range<String> range ->
                number == NumberEncoding.LONG ? longRange(field, range) : floatingRange(field, number, range);
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
            case SORTED, SORTED_SET ->
                switch (match) {
                    case Match.Values<BytesRef> values
                    when values.values().size() == 1 -> {
                        BytesRef value = values.values().getFirst();
                        yield DocValuesRangeQuery.newBytesRefRange(field.name, value, value, true, true);
                    }
                    case Match.Values<BytesRef> values -> new DocValuesTermsQuery(field.name, values.values());
                    case Match.Range<BytesRef> range ->
                        DocValuesRangeQuery.newBytesRefRange(
                                field.name, range.lower(), range.upper(), range.includeLower(), range.includeUpper());
                };
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

    private static Query longRange(FieldInfo field, Match.Range<String> range) {
        long lower = Long.MIN_VALUE;
        long upper = Long.MAX_VALUE;
        Optional<String> from = Optional.ofNullable(range.lower());
        if (from.isPresent()) {
            lower = parseLong(from.get());
            if (!range.includeLower()) {
                if (lower == Long.MAX_VALUE) {
                    return none();
                }
                lower++;
            }
        }
        Optional<String> to = Optional.ofNullable(range.upper());
        if (to.isPresent()) {
            upper = parseLong(to.get());
            if (!range.includeUpper()) {
                if (upper == Long.MIN_VALUE) {
                    return none();
                }
                upper--;
            }
        }
        return longRange(field, lower, upper);
    }

    /**
     * A range of floating point numbers. Sortable bits order as the numbers do, so the range is one
     * range of longs. Raw bits order negative numbers backwards, so the range is split at zero, as
     * leia-core splits it. An open bound takes in NaN too, whose bits lie beyond the infinities.
     */
    private static Query floatingRange(FieldInfo field, NumberEncoding number, Match.Range<String> range) {
        Optional<Double> lower = Optional.ofNullable(range.lower()).map(text -> parseFloating(number, text));
        Optional<Double> upper = Optional.ofNullable(range.upper()).map(text -> parseFloating(number, text));
        if (lower.isPresent() && !range.includeLower()) {
            if (lower.get() == Double.POSITIVE_INFINITY) {
                return none();
            }
            lower = Optional.of(nextUp(number, lower.get()));
        }
        if (upper.isPresent() && !range.includeUpper()) {
            if (upper.get() == Double.NEGATIVE_INFINITY) {
                return none();
            }
            upper = Optional.of(nextDown(number, upper.get()));
        }
        if (lower.isPresent() && upper.isPresent() && Double.compare(lower.get(), upper.get()) > 0) {
            return none();
        }
        if (!raw(number)) {
            return longRange(
                    field,
                    lower.map(value -> encode(number, value)).orElse(Long.MIN_VALUE),
                    upper.map(value -> encode(number, value)).orElse(Long.MAX_VALUE));
        }
        return rawRange(field, number, lower, upper);
    }

    /**
     * A range of raw float bits: the positive and the negative values each are a range of longs, the
     * negative ones reversed.
     */
    private static Query rawRange(
            FieldInfo field, NumberEncoding number, Optional<Double> lower, Optional<Double> upper) {
        BooleanQuery.Builder parts = new BooleanQuery.Builder();
        if (upper.isEmpty() || Double.compare(upper.get(), 0.0) >= 0) {
            double from = lower.filter(value -> Double.compare(value, 0.0) > 0).orElse(0.0);
            parts.add(
                    longRange(
                            field,
                            encode(number, from),
                            upper.map(value -> encode(number, value)).orElse(Long.MAX_VALUE)),
                    Occur.SHOULD);
        }
        if (lower.isEmpty() || Double.compare(lower.get(), -0.0) <= 0) {
            double from = upper.filter(value -> Double.compare(value, -0.0) < 0).orElse(-0.0);
            parts.add(
                    longRange(
                            field,
                            encode(number, from),
                            lower.map(value -> encode(number, value)).orElse(-1L)),
                    Occur.SHOULD);
        }
        BooleanQuery query = parts.build();
        return query.clauses().size() == 1 ? query.clauses().getFirst().getQuery() : query;
    }

    private static Query longRange(FieldInfo field, long lower, long upper) {
        return DocValuesRangeQuery.newLongRange(field.name, lower, upper, true, true);
    }

    /** No document: 5.5's {@link MatchNoDocsQuery} takes no reason. */
    private static Query none() {
        return new MatchNoDocsQuery();
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

    /**
     * The next number up in this format, keeping {@code -0.0} and {@code 0.0} apart: a copy of the
     * {@code nextUp} of Lucene's {@code DoublePoint} and {@code FloatPoint}, which came with 6.0.
     */
    private static double nextUp(NumberEncoding number, double value) {
        if (Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0)) {
            return 0.0;
        }
        return doubles(number) ? Math.nextUp(value) : Math.nextUp((float) value);
    }

    /** The next number down in this format, keeping {@code -0.0} and {@code 0.0} apart, as {@link #nextUp}. */
    private static double nextDown(NumberEncoding number, double value) {
        if (Double.doubleToRawLongBits(value) == 0L) {
            return -0.0;
        }
        return doubles(number) ? Math.nextDown(value) : Math.nextDown((float) value);
    }

    /**
     * Documents whose {@code BINARY} doc value is one of several values, or lies in a range: a copy
     * of leia-core's {@code BinaryDocValuesQuery}, on 5.5's {@link Weight}, whose doc values are read
     * by document. Lucene has no such query: it reads the value of every document that has one.
     */
    private static final class BinaryDocValuesQuery extends Query {

        private final String field;
        private final Set<BytesRef> values;
        private final Optional<BytesRef> lower;
        private final Optional<BytesRef> upper;
        private final boolean includeLower;
        private final boolean includeUpper;

        BinaryDocValuesQuery(String field, Match<BytesRef> match) {
            this.field = field;
            switch (match) {
                case Match.Values<BytesRef> exact -> {
                    values = exact.values().stream().map(BytesRef::deepCopyOf).collect(Collectors.toUnmodifiableSet());
                    lower = Optional.empty();
                    upper = Optional.empty();
                    includeLower = false;
                    includeUpper = false;
                }
                case Match.Range<BytesRef> range -> {
                    values = null;
                    lower = Optional.ofNullable(range.lower()).map(BytesRef::deepCopyOf);
                    upper = Optional.ofNullable(range.upper()).map(BytesRef::deepCopyOf);
                    includeLower = range.includeLower();
                    includeUpper = range.includeUpper();
                }
            }
        }

        private boolean matches(BytesRef value) {
            if (values != null) {
                return values.contains(value);
            }
            if (lower.isPresent()) {
                int order = value.compareTo(lower.get());
                if (order < 0 || (order == 0 && !includeLower)) {
                    return false;
                }
            }
            if (upper.isPresent()) {
                int order = value.compareTo(upper.get());
                return order < 0 || (order == 0 && includeUpper);
            }
            return true;
        }

        /**
         * {@inheritDoc} On 5.5, a {@link ConstantScoreWeight} gives its scorer itself; every document is
         * a candidate, and those with a value are read.
         */
        @Override
        public Weight createWeight(IndexSearcher searcher, boolean needsScores) {
            return new ConstantScoreWeight(this) {
                @Override
                public Scorer scorer(LeafReaderContext context) throws IOException {
                    LeafReader leaf = context.reader();
                    BinaryDocValues docValues = leaf.getBinaryDocValues(field);
                    if (docValues == null) {
                        return null;
                    }
                    Bits withValue = DocValues.getDocsWithField(leaf, field);
                    TwoPhaseIterator iterator = new TwoPhaseIterator(DocIdSetIterator.all(leaf.maxDoc())) {
                        @Override
                        public boolean matches() throws IOException {
                            int doc = approximation.docID();
                            return withValue.get(doc) && BinaryDocValuesQuery.this.matches(docValues.get(doc));
                        }

                        @Override
                        public float matchCost() {
                            // Reading the value, then comparing it.
                            return 10;
                        }
                    };
                    return new ConstantScoreScorer(this, score(), iterator);
                }
            };
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
                    .append(lower.map(BytesRef::toString).orElse("*"))
                    .append(" TO ")
                    .append(upper.map(BytesRef::toString).orElse("*"))
                    .append(includeUpper ? ']' : '}')
                    .toString();
        }

        @Override
        public boolean equals(Object other) {
            // 5.5's: the class and the boost.
            return super.equals(other)
                    && other instanceof BinaryDocValuesQuery query
                    && field.equals(query.field)
                    && Objects.equals(values, query.values)
                    && lower.equals(query.lower)
                    && upper.equals(query.upper)
                    && includeLower == query.includeLower
                    && includeUpper == query.includeUpper;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), field, values, lower, upper, includeLower, includeUpper);
        }
    }
}
