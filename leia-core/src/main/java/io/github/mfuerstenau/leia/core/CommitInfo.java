package io.github.mfuerstenau.leia.core;

import java.util.Map;
import org.apache.lucene.util.Version;

/**
 * A commit of the index directory, as {@code segments_N} describes it (ADR 0009).
 *
 * @param generation the {@code N} in {@code segments_N}
 * @param segmentsFile the {@code segments_N} file
 * @param current whether this is the commit the index was opened on
 * @param version the version of the commit, which changes with every change to the index
 * @param segmentCount segments in the commit
 * @param maxDoc documents in all segments, deleted ones included
 * @param delCount deleted documents in all segments, soft deletes not included
 * @param luceneVersion the Lucene version that wrote the commit
 * @param userData the user data of the commit, sorted by key
 */
public record CommitInfo(
        long generation,
        String segmentsFile,
        boolean current,
        long version,
        int segmentCount,
        int maxDoc,
        int delCount,
        Version luceneVersion,
        Map<String, String> userData) {}
