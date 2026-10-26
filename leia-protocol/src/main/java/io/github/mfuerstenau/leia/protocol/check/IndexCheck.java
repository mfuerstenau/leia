package io.github.mfuerstenau.leia.protocol.check;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of checking one index directory with Lucene's {@code CheckIndex}: its latest commit.
 *
 * @param path the index directory
 * @param skipped whether it was skipped when the path was opened (ADR 0021)
 * @param clean whether no problem was found
 * @param error why it could not be checked at all, such as no {@code segments_N}; then the other
 *     fields say nothing
 * @param segmentsFile the {@code segments_N} file of the latest commit, if it could be read
 * @param missingSegments whether the latest commit, or an older one, could not be read
 * @param toolOutOfDate whether the index was written by a newer Lucene than the server's
 * @param segmentCount the segments of the latest commit
 * @param badSegments the segments that failed a test
 * @param lostDocs the documents in the bad segments
 * @param validCounter whether the commit's counter for segment names is above every segment's name
 * @param segments each segment of the latest commit that was checked, in commit order
 * @param onlySegments the segments that were asked for; absent if the whole index was
 * @param segmentsNotFound the segments that were asked for and are not in the latest commit; then
 *     {@code clean} is false (ADR 0093)
 */
public record IndexCheck(
        String path,
        boolean skipped,
        boolean clean,
        Optional<String> error,
        Optional<String> segmentsFile,
        boolean missingSegments,
        boolean toolOutOfDate,
        int segmentCount,
        int badSegments,
        int lostDocs,
        boolean validCounter,
        List<SegmentCheck> segments,
        Optional<List<String>> onlySegments,
        List<String> segmentsNotFound) {}
