package io.github.mfuerstenau.leia.protocol.index;

import java.util.Optional;

/**
 * What the reader of an index directory opened.
 *
 * @param docBase the document id of its first document in the opened path; 0 for the first index
 * @param segmentsFile the {@code segments_N} file of the opened commit (the latest one)
 * @param numDocs live documents
 * @param maxDoc documents including deleted ones
 * @param createdVersionMajor the major Lucene version that created the index
 * @param minSegmentLuceneVersion the oldest Lucene version that wrote one of its segments, such as
 *     {@code 9.12.3}; absent if the index has no segments
 */
public record ReaderInfo(
        int docBase,
        String segmentsFile,
        int numDocs,
        int maxDoc,
        int createdVersionMajor,
        Optional<String> minSegmentLuceneVersion) {}
