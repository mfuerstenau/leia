package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explain on an index of the runtimes' writers (ADR 0057): documents with a {@code body} "alpha …
 * document <id> …", beta in some of them, and deleted ones.
 */
final class RuntimeExplains {

    private RuntimeExplains() {}

    /**
     * Explains the hits of a query, whose values are their scores whatever the similarity asked for,
     * and a document the query doesn't match; a document id out of range and an invalid query fail.
     *
     * @param exact whether the values are the scores to the bit; Lucene 7.7's BM25 explanation
     *     computes them in another order than its scorer, so they may differ in the last bits
     */
    static void explainsAHit(RealBackend backend, int maxDoc, boolean exact) throws Exception {
        List<Hit> alpha = backend.search(classic("alpha"), maxDoc).hits();
        assertThat(alpha).isNotEmpty();
        for (Hit hit : alpha.subList(0, Math.min(3, alpha.size()))) {
            explainsTheHit(backend, classic("alpha"), hit, exact);
        }

        Set<Integer> beta = backend.search(classic("beta"), maxDoc).hits().stream()
                .map(Hit::docId)
                .collect(Collectors.toSet());
        int withoutBeta = alpha.stream()
                .map(Hit::docId)
                .filter(docId -> !beta.contains(docId))
                .findFirst()
                .orElseThrow();
        assertThat(backend.explain(classic("beta"), BM25, withoutBeta).match()).isFalse();

        backend.fails(
                Methods.START_EXPLAIN, new StartExplainParams(classic("alpha"), BM25, maxDoc), ErrorCode.DOC_NOT_FOUND);
        backend.fails(
                Methods.START_EXPLAIN,
                new StartExplainParams(classic("body:(alpha"), BM25, 0),
                ErrorCode.INVALID_QUERY);
    }

    /** Explains {@code hit} of {@code query}: it matches, with its score as the value. */
    static void explainsTheHit(RealBackend backend, SearchQuery query, Hit hit, boolean exact) throws Exception {
        Explanation explanation = backend.explain(query, BM25, hit.docId());
        assertThat(explanation.match()).isTrue();
        float value = Float.parseFloat(explanation.value());
        if (exact) {
            assertThat(value).isEqualTo(hit.score());
        } else {
            assertThat(value).isCloseTo(hit.score(), withinPercentage(0.001));
        }
        assertThat(explanation.description()).isNotEmpty();
    }
}
