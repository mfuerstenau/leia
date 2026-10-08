package io.github.mfuerstenau.leia.protocol.commits;

import java.util.List;

/**
 * Result of {@code getCommits}: the commits of the open index's directory (ADR 0009).
 *
 * @param commits the readable commits, newest first
 * @param unreadable the {@code segments_N} files that could not be read, and why (ADR 0021)
 */
public record GetCommitsResult(List<Commit> commits, List<UnreadableCommit> unreadable) {}
