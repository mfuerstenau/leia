package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.queries.function.FunctionScoreQuery;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;

/**
 * Every document with a KNN vector in a field, scored by the field's similarity to a target (ADR
 * 0040), as Lucene's KNN queries score the documents they find. It rewrites to a {@link
 * FunctionScoreQuery} over a {@link FieldExistsQuery}, so the search reads every vector through the
 * searcher's reader, which can stop it.
 */
final class ExactKnnQuery extends Query {

    private final String field;
    private final KnnVector target;
    private final Optional<Query> filter;

    ExactKnnQuery(String field, KnnVector target, Optional<Query> filter) {
        this.field = field;
        this.target = target;
        this.filter = filter;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) {
        BooleanQuery.Builder matches = new BooleanQuery.Builder().add(new FieldExistsQuery(field), Occur.FILTER);
        filter.ifPresent(query -> matches.add(query, Occur.FILTER));
        return new FunctionScoreQuery(matches.build(), new Similarity(field, target));
    }

    @Override
    public void visit(QueryVisitor visitor) {
        if (visitor.acceptField(field)) {
            visitor.visitLeaf(this);
        }
    }

    /** As Lucene writes its KNN queries: the first component of the target only. */
    @Override
    public String toString(String defaultField) {
        String first = switch (target) {
            case KnnVector.Floats floats -> Float.toString(floats.values()[0]);
            case KnnVector.Bytes bytes -> Byte.toString(bytes.values()[0]);
        };
        return "ExactKnnQuery:" + field + "[" + first + ",...]"
                + filter.map(query -> "[" + query + "]").orElse("");
    }

    @Override
    public boolean equals(Object o) {
        return sameClassAs(o)
                && o instanceof ExactKnnQuery other
                && field.equals(other.field)
                && target.equals(other.target)
                && filter.equals(other.filter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classHash(), field, target, filter);
    }

    /** The similarity of a document's vector to the target; a document without one has no value. */
    private static final class Similarity extends DoubleValuesSource {

        private final String field;
        private final KnnVector target;

        Similarity(String field, KnnVector target) {
            this.field = field;
            this.target = target;
        }

        @Override
        public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
            FieldInfo info = ctx.reader().getFieldInfos().fieldInfo(field);
            if (info == null || info.getVectorDimension() == 0) {
                return DoubleValues.EMPTY;
            }
            return switch (target) {
                case KnnVector.Floats floats -> similarityToQueryVector(ctx, floats.values(), field);
                case KnnVector.Bytes bytes -> similarityToQueryVector(ctx, bytes.values(), field);
            };
        }

        @Override
        public boolean needsScores() {
            return false;
        }

        @Override
        public DoubleValuesSource rewrite(IndexSearcher searcher) {
            return this;
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Similarity other && field.equals(other.field) && target.equals(other.target);
        }

        @Override
        public int hashCode() {
            return Objects.hash(field, target);
        }

        @Override
        public String toString() {
            return "similarity(" + field + ")";
        }
    }
}
