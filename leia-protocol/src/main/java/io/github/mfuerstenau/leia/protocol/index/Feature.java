package io.github.mfuerstenau.leia.protocol.index;

import java.util.EnumSet;
import java.util.Set;

/**
 * What can be done with an opened index (ADR 0044): what the runtime that reads it supports (ADR
 * 0045, ADR 0071). One opened without a reader has only {@link #COMMITS}, {@link #CHECK_INDEX} and,
 * if the runtime of its first index checks at levels, {@link #CHECK_LEVELS}, and in threads, {@link
 * #CHECK_THREADS}.
 */
public enum Feature {
    /** {@code getIndexStats}. */
    INDEX_STATS,
    /** The top terms of a field: {@code startFieldTermScan}. */
    TOP_TERMS,
    /** {@code getDocument}. */
    DOCUMENTS,
    /** {@code getStoredValue}. */
    STORED_VALUES,
    /** {@code getTermVector}. */
    TERM_VECTORS,
    /** {@code getPositions}. */
    POSITIONS,
    /** {@code startReconstruct}. */
    RECONSTRUCT,
    /** {@code startFilter} and the filter of the other methods (ADR 0031). */
    FILTER,
    /** {@code getTerms}. */
    TERMS,
    /** {@code getPostings}. */
    POSTINGS,
    /** {@code getPointTree} and {@code getPointCell}. */
    POINTS,
    /** Doc values in documents and queries. */
    DOC_VALUES,
    /** KNN vectors in documents and queries. */
    KNN_VECTORS,
    /** {@code getKnnGraph}, {@code getKnnGraphNodes} and {@code getKnnGraphNode} (ADR 0085), with {@link #KNN_VECTORS}. */
    KNN_GRAPH,
    /** {@code getCommits} and {@code getCommit}. */
    COMMITS,
    /**
     * {@code startSearch} with a classic query only, or with {@link #KNN_VECTORS} a KNN query too
     * (ADR 0049); best score first, without highlighting, unless with {@link #SCORING}.
     */
    CLASSIC_SEARCH,
    /**
     * {@code startSearch}, {@code startFilter} and {@code startExplain} with a term query, best score
     * first unless with {@link #SCORING} (ADR 0058).
     */
    TERM_SEARCH,
    /**
     * The similarity and sort of {@code startSearch} and the similarity of {@code startExplain} (ADR
     * 0064).
     */
    SCORING,
    /**
     * {@code startSearch}, {@code startFilter} and {@code startExplain} with a doc values query, with
     * {@link #DOC_VALUES} (ADR 0066).
     */
    DOC_VALUES_SEARCH,
    /**
     * {@code startSearch}, {@code startFilter}, {@code startExplain} and, with {@link #HIGHLIGHT},
     * {@code startHighlight} with a query of the standard parser, with dates (ADR 0066, ADR 0069).
     */
    STANDARD_SEARCH,
    /**
     * {@code startSearch}, {@code startFilter}, {@code startExplain} and, with {@link #HIGHLIGHT},
     * {@code startHighlight} with a More-Like-This query, by a document or a text (ADR 0066).
     */
    MORE_LIKE_THIS,
    /** {@code startExplain}. */
    EXPLAIN,
    /** Highlighting in {@code startSearch}, and {@code startHighlight}. */
    HIGHLIGHT,
    /** {@code startStoredScan}. */
    STORED_SCAN,
    /** {@code startCheckIndex}. */
    CHECK_INDEX,
    /** The {@code level} of {@code startCheckIndex}; a Lucene without it checks at its one level (ADR 0045). */
    CHECK_LEVELS,
    /** The {@code threads} of {@code startCheckIndex}; a Lucene without it checks segments one at a time (ADR 0093). */
    CHECK_THREADS,
    /** {@code startExportTerms}. */
    EXPORT_TERMS,
    /** {@code reopenIndex}. */
    REOPEN,
    /** {@code startAutoStopWords}. */
    AUTO_STOP_WORDS;

    /** Every feature: an index whose runtime has every group, with every kind of data. */
    public static Set<Feature> all() {
        return EnumSet.allOf(Feature.class);
    }

    /** An index opened without a reader (ADR 0021) whose first index's runtime checks at levels and in threads. */
    public static Set<Feature> withoutReader() {
        return EnumSet.of(COMMITS, CHECK_INDEX, CHECK_LEVELS, CHECK_THREADS);
    }
}
