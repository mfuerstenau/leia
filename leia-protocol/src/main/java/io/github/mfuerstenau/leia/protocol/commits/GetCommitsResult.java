package io.github.mfuerstenau.leia.protocol.commits;

import java.util.List;

/**
 * Result of {@code getCommits}: the commits of the open index's directory (ADR 0009).
 *
 * @param commits the readable commits, newest first
 */
public record GetCommitsResult(List<Commit> commits) {}
