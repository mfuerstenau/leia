package io.github.mfuerstenau.leia.protocol.index;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An index directory of the open path, and its opened commit (ADR 0021).
 *
 * @param path the absolute, normalized index directory
 * @param docBase the document id of its first document in the opened path; 0 for the first index
 * @param segmentsFile the {@code segments_N} file of the opened commit
 * @param generation the generation of that commit, the {@code N} in {@code segments_N}
 * @param version the version of that commit, which changes with every change to the index
 * @param segmentCount segments in the commit; an index with one segment is fully merged
 * @param numDocs live documents
 * @param maxDoc documents including deleted ones
 * @param createdVersionMajor the major Lucene version that created the index
 * @param commitLuceneVersion the Lucene version that wrote the commit, such as {@code 10.5.2}
 * @param minSegmentLuceneVersion the oldest Lucene version that wrote one of its segments; absent if
 *     the index has no segments
 * @param userData the user data of the commit
 * @param segments the open reader's segments of the index directory, in document order (ADR 0051);
 *     they come from the reader, not from {@code segments_N}
 */
public record IndexCommitStats(
        String path,
        int docBase,
        String segmentsFile,
        long generation,
        long version,
        int segmentCount,
        int numDocs,
        int maxDoc,
        int createdVersionMajor,
        String commitLuceneVersion,
        Optional<String> minSegmentLuceneVersion,
        Map<String, String> userData,
        List<ReaderSegment> segments) {}
