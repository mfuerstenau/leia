package io.github.mfuerstenau.leia.protocol.check;

import java.util.List;

/**
 * Result of {@code getCheckTargets}.
 *
 * @param indexes every index of the open path, as {@code startCheckIndex} checks them: the opened
 *     ones in order, then the skipped ones
 */
public record GetCheckTargetsResult(List<CheckTargetIndex> indexes) {}
