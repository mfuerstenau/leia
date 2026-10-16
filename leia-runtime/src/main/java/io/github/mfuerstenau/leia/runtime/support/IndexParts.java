package io.github.mfuerstenau.leia.runtime.support;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex.Skipped;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Opens the directories of an index one by one, as a runtime's {@code open} does (ADR 0045, ADR 0050). */
public final class IndexParts {

    private IndexParts() {}

    /** Opens one directory of an index. */
    @FunctionalInterface
    public interface Opener<P> {
        P open(Path dir) throws IOException;
    }

    /** The parts that opened, in the order given, and the directories that failed to. */
    public record Opened<P>(List<P> parts, List<Skipped> skipped) {}

    /**
     * Opens each of {@code dirs}. A single directory that fails throws as it failed; of several, each
     * one that fails is skipped, and if none opens, the first failure is thrown.
     *
     * @throws IllegalArgumentException if {@code dirs} is empty
     */
    public static <P> Opened<P> open(List<Path> dirs, Opener<P> opener) throws IOException {
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("No directory to open");
        }
        List<P> parts = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        IOException firstFailure = null;
        for (Path dir : dirs) {
            try {
                parts.add(opener.open(dir));
            } catch (IOException | RuntimeException e) {
                if (dirs.size() == 1) {
                    throw e;
                }
                skipped.add(new Skipped(dir, Failures.message(e)));
                if (firstFailure == null) {
                    firstFailure = new IOException(dir + ": " + Failures.message(e), e);
                }
            }
        }
        if (parts.isEmpty()) {
            throw firstFailure;
        }
        return new Opened<>(parts, skipped);
    }
}
