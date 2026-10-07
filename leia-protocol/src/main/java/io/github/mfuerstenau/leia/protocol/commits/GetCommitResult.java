package io.github.mfuerstenau.leia.protocol.commits;

import java.util.List;

/**
 * Result of {@code getCommit}: one commit whole (ADR 0009).
 *
 * @param commit the commit, as {@code getCommits} lists it
 * @param segments the segments in commit order, which is the order of their document ids
 * @param files every file of the commit, {@code segments_N} included, sorted by name
 */
public record GetCommitResult(Commit commit, List<Segment> segments, List<CommitFile> files) {}
