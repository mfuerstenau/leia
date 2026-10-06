package io.github.mfuerstenau.leia.protocol.index;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Result of {@code getIndexStats}: statistics of the open index that are cheap to compute. Nothing
 * here iterates over terms or documents, so the number of terms is not included.
 *
 * @param path the absolute, normalized index directory
 * @param directoryImpl the class of the Lucene {@code Directory}, such as {@code MMapDirectory}
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
 * @param fields every field in the index, ordered by field number
 */
public record GetIndexStatsResult(
        String path,
        String directoryImpl,
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
        List<IndexField> fields) {}
