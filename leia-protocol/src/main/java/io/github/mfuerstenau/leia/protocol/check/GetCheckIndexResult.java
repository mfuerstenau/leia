package io.github.mfuerstenau.leia.protocol.check;

import java.util.List;
import java.util.Optional;

/**
 * Result of {@code getCheckIndex}: the output of the check so far, and its outcome once it is done.
 *
 * @param lineCount the lines of output so far
 * @param lines the lines from {@code fromLine} on; all of them once the outcome is there
 * @param result the outcome; absent while the check is running
 */
public record GetCheckIndexResult(int lineCount, List<String> lines, Optional<CheckIndexResult> result) {}
