package io.github.mfuerstenau.leia.protocol.index;

import java.util.Optional;

/**
 * Result of {@code openIndex}: what identifies the opened index. The full statistics come from
 * {@code getIndexStats}.
 *
 * @param path the absolute, normalized index directory
 * @param segmentsFile the {@code segments_N} file of the opened commit (the latest one)
 * @param numDocs live documents
 * @param maxDoc documents including deleted ones
 * @param createdVersionMajor the major Lucene version that created the index
 * @param minSegmentLuceneVersion the oldest Lucene version that wrote one of its segments, such as
 *     {@code 9.12.3}; absent if the index has no segments
 */
public record OpenIndexResult(
        String path,
        String segmentsFile,
        int numDocs,
        int maxDoc,
        int createdVersionMajor,
        Optional<String> minSegmentLuceneVersion) {}
