package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.List;

/**
 * The commits of the index directory (ADR 0009).
 *
 * @param commits the readable commits, newest first
 * @param unreadable the {@code segments_N} files that could not be read, and why
 */
public record CommitList(List<CommitInfo> commits, List<Unreadable> unreadable) {

    /** A {@code segments_N} file that could not be read. */
    public record Unreadable(String segmentsFile, IOException error) {}
}
