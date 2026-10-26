package io.github.mfuerstenau.leia.protocol.check;

import java.util.List;
import java.util.Optional;

/**
 * An index that {@code startCheckIndex} can check, with the segments of its latest commit.
 *
 * @param path the index directory
 * @param skipped whether it was skipped when the path was opened (ADR 0021)
 * @param segmentsFile the {@code segments_N} file of the latest commit; absent if it can't be read
 * @param segments the segments of the latest commit, in commit order; none if it can't be read
 * @param error why the latest commit can't be listed, such as a runtime that isn't loaded
 */
public record CheckTargetIndex(
        String path,
        boolean skipped,
        Optional<String> segmentsFile,
        List<CheckTargetSegment> segments,
        Optional<String> error) {}
