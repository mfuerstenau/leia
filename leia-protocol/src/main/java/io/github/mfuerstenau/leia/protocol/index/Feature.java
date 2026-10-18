package io.github.mfuerstenau.leia.protocol.index;

import java.util.EnumSet;
import java.util.Set;

/**
 * What can be done with an opened index (ADR 0044). An index opened with Lucene 10 has every feature,
 * one opened without a reader only {@link #COMMITS}, {@link #CHECK_INDEX} and, if Lucene 10 checks its
 * first index, {@link #CHECK_LEVELS}, and one written by an old Lucene what the Lucene that reads it
 * supports (ADR 0045).
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
    /** {@code getCommits} and {@code getCommit}. */
    COMMITS,
    /**
     * {@code startSearch}, with every kind of query, similarity and sort: every part of search. A
     * runtime reports the parts it has instead, such as {@link #CLASSIC_SEARCH} and {@link
     * #TERM_SEARCH} (ADR 0058).
     */
    SEARCH,
    /**
     * {@code startSearch} with a classic query only, or with {@link #KNN_VECTORS} a KNN query too
     * (ADR 0049); best score first, without highlighting.
     */
    CLASSIC_SEARCH,
    /** {@code startSearch}, {@code startFilter} and {@code startExplain} with a term query, best score first (ADR 0058). */
    TERM_SEARCH,
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
    /** {@code startExportTerms}. */
    EXPORT_TERMS,
    /** {@code reopenIndex}. */
    REOPEN,
    /** {@code startAutoStopWords}. */
    AUTO_STOP_WORDS;

    /** Every feature: an index that Lucene 10 opens with a reader. */
    public static Set<Feature> all() {
        return EnumSet.allOf(Feature.class);
    }

    /** An index opened without a reader (ADR 0021) whose first index Lucene 10 checks. */
    public static Set<Feature> withoutReader() {
        return EnumSet.of(COMMITS, CHECK_INDEX, CHECK_LEVELS);
    }
}
