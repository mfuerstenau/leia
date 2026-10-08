package io.github.mfuerstenau.leia.protocol.index;

import java.util.Optional;

/**
 * Result of {@code reopenIndex} (ADR 0019): whether the index changed, and what identifies it now,
 * as in {@link OpenIndexResult}. The full statistics come from {@code getIndexStats}.
 *
 * @param changed whether the latest commit differs from the one that was open, which is then
 *     replaced; if not, nothing happened
 * @param path the absolute, normalized index directory
 * @param segmentsFile the {@code segments_N} file of the open commit
 * @param numDocs live documents
 * @param maxDoc documents including deleted ones
 * @param createdVersionMajor the major Lucene version that created the index
 * @param minSegmentLuceneVersion the oldest Lucene version that wrote one of its segments, such as
 *     {@code 9.12.3}; absent if the index has no segments
 */
public record ReopenIndexResult(
        boolean changed,
        String path,
        String segmentsFile,
        int numDocs,
        int maxDoc,
        int createdVersionMajor,
        Optional<String> minSegmentLuceneVersion) {}
