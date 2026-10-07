package io.github.mfuerstenau.leia.protocol.commits;

import java.util.Map;

/**
 * A commit of the index directory, as {@code segments_N} describes it.
 *
 * @param generation the {@code N} in {@code segments_N}
 * @param segmentsFile the {@code segments_N} file
 * @param current whether this is the commit the open index was opened on
 * @param version the version of the commit, which changes with every change to the index
 * @param segmentCount segments in the commit
 * @param maxDoc documents in all segments, deleted ones included
 * @param delCount deleted documents in all segments, soft deletes not included
 * @param luceneVersion the Lucene version that wrote the commit, such as {@code 10.5.2}
 * @param userData the user data of the commit
 */
public record Commit(
        long generation,
        String segmentsFile,
        boolean current,
        long version,
        int segmentCount,
        int maxDoc,
        int delCount,
        String luceneVersion,
        Map<String, String> userData) {}
