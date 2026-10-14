package io.github.mfuerstenau.leia.protocol.search;

import java.util.Optional;

/**
 * A query, as one of several alternatives: exactly one is present (ADR 0010, ADR 0011, ADR 0013,
 * ADR 0033, ADR 0040).
 *
 * @param classic an expression for Lucene's classic query parser
 * @param standard an expression for Lucene's standard query parser, which knows point fields
 * @param term a query for exactly one term
 * @param moreLikeThis documents like one document of the index
 * @param docValues documents by the doc values of one field
 * @param knn documents by the nearness of their KNN vectors to a target vector
 */
public record SearchQuery(
        Optional<ClassicQuery> classic,
        Optional<StandardQuery> standard,
        Optional<TermQuery> term,
        Optional<MoreLikeThisQuery> moreLikeThis,
        Optional<DocValuesQuery> docValues,
        Optional<KnnQuery> knn) {}
