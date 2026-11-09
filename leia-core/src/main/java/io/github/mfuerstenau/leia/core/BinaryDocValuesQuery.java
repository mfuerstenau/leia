package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;
import org.jspecify.annotations.Nullable;

/**
 * Documents whose {@code BINARY} doc value is one of several values, or lies in a range (ADR 0033).
 * Lucene has no such query: it reads the value of every document that has one. The reader's doc
 * values iterator checks the search's {@code QueryTimeout} as it goes (ADR 0010 §8), so a
 * cancelled search stops inside the scan.
 */
final class BinaryDocValuesQuery extends Query {

    private final String field;
    private final @Nullable Set<BytesRef> values;
    private final @Nullable BytesRef lower;
    private final @Nullable BytesRef upper;
    private final boolean includeLower;
    private final boolean includeUpper;

    BinaryDocValuesQuery(String field, DocValuesMatch<BytesRef> match) {
        this.field = field;
        switch (match) {
            case DocValuesMatch.Values<BytesRef> exact -> {
                values = exact.values().stream().map(BytesRef::deepCopyOf).collect(Collectors.toUnmodifiableSet());
                lower = null;
                upper = null;
                includeLower = false;
                includeUpper = false;
            }
            case DocValuesMatch.Range<BytesRef> range -> {
                values = null;
                lower = range.lower() != null ? BytesRef.deepCopyOf(range.lower()) : null;
                upper = range.upper() != null ? BytesRef.deepCopyOf(range.upper()) : null;
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

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ConstantScoreWeight(this, boost) {
            @Override
            public @Nullable ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
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
                return new DefaultScorerSupplier(new ConstantScoreScorer(score(), scoreMode, iterator));
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
                .append(lower != null ? lower.toString() : "*")
                .append(" TO ")
                .append(upper != null ? upper.toString() : "*")
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
