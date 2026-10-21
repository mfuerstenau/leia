package io.github.mfuerstenau.leia.lucene5;

import java.io.IOException;
import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.BytesRef;

/**
 * A matching term scores its query boost: a port of Lucene 6.4's {@code BooleanSimilarity}, which
 * 5.5 doesn't have (ADR 0076). The coordination factor and the query norm are 1, as {@link
 * Similarity} has them, so a query scores as it does with 9.12's.
 */
final class Lucene5BooleanSimilarity extends Similarity {

    private static final Similarity BM25 = new BM25Similarity();

    /** {@inheritDoc} As BM25 writes them; never called, as leia only reads. */
    @Override
    public long computeNorm(FieldInvertState state) {
        return BM25.computeNorm(state);
    }

    @Override
    public SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats) {
        return new BooleanWeight();
    }

    @Override
    public SimScorer simScorer(SimWeight weight, LeafReaderContext context) throws IOException {
        float boost = ((BooleanWeight) weight).boost;
        return new SimScorer() {

            @Override
            public float score(int doc, float freq) {
                return boost;
            }

            @Override
            public Explanation explain(int doc, Explanation freq) {
                Explanation queryBoost = Explanation.match(boost, "query boost");
                return Explanation.match(
                        queryBoost.getValue(),
                        "score(" + getClass().getSimpleName() + ", doc=" + doc + "), computed from:",
                        queryBoost);
            }

            @Override
            public float computeSlopFactor(int distance) {
                return 1f;
            }

            @Override
            public float computePayloadFactor(int doc, int start, int end, BytesRef payload) {
                return 1f;
            }
        };
    }

    /** The query boost, which the query norm leaves alone. */
    private static final class BooleanWeight extends SimWeight {

        private float boost = 1f;

        @Override
        public float getValueForNormalization() {
            return boost * boost;
        }

        @Override
        public void normalize(float queryNorm, float boost) {
            this.boost = boost;
        }
    }
}
