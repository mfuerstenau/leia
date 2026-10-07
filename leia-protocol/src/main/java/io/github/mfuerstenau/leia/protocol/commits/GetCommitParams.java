package io.github.mfuerstenau.leia.protocol.commits;

/**
 * Params of {@code getCommit}.
 *
 * @param generation the generation of the commit, the {@code N} in {@code segments_N}
 */
public record GetCommitParams(long generation) {}
