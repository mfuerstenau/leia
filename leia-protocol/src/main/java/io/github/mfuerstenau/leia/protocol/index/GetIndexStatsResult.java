package io.github.mfuerstenau.leia.protocol.index;

import java.util.List;

/**
 * Result of {@code getIndexStats}: statistics of the open indexes that are cheap to compute (ADR
 * 0021). Nothing here iterates over terms or documents, so the number of terms is not included. The
 * counts and fields cover all indexes; what belongs to a commit is per index.
 *
 * @param path the absolute, normalized path that was opened
 * @param directoryImpl the class of the Lucene {@code Directory}, such as {@code MMapDirectory}
 * @param segmentCount segments in all opened commits
 * @param numDocs live documents
 * @param maxDoc documents including deleted ones
 * @param indexes each index directory with its opened commit, in order; at least one
 * @param fields every field, merged over the indexes, ordered by field number
 */
public record GetIndexStatsResult(
        String path,
        String directoryImpl,
        int segmentCount,
        int numDocs,
        int maxDoc,
        List<IndexCommitStats> indexes,
        List<IndexField> fields) {}
