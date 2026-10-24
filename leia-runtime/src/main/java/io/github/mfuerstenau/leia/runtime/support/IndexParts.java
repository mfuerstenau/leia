package io.github.mfuerstenau.leia.runtime.support;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex.Skipped;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Opens the directories of an index one by one, as a runtime's {@code open} does (ADR 0045, ADR 0050). */
public final class IndexParts {

    private IndexParts() {}

    /** Opens one directory of an index. */
    @FunctionalInterface
    public interface Opener<P> {
        P open(Path dir) throws IOException;
    }

    /** Why the fields of some parts can't be combined (ADR 0021 §5). */
    @FunctionalInterface
    public interface Conflict<P> {
        /** Why the fields of {@code parts} can't be combined; empty if they can. */
        Optional<String> of(List<P> parts) throws IOException;
    }

    /** The parts that opened, in the order given, and the directories that failed to. */
    public record Opened<P>(List<P> parts, List<Skipped> skipped) {}

    /**
     * Opens each of {@code dirs}. A single directory that fails throws as it failed; of several, each
     * one that fails is skipped, and if none opens, the first failure is thrown, named after its
     * directory and with its reason if it has one ({@link Failures#named}).
     *
     * @throws IllegalArgumentException if {@code dirs} is empty
     */
    public static <P> Opened<P> open(List<Path> dirs, Opener<P> opener) throws IOException {
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("No directory to open");
        }
        List<P> parts = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<IOException> failures = new ArrayList<>();
        for (Path dir : dirs) {
            try {
                parts.add(opener.open(dir));
            } catch (IOException | RuntimeException e) {
                if (dirs.size() == 1) {
                    throw e;
                }
                skipped.add(new Skipped(dir, Failures.message(e)));
                failures.add(Failures.named(dir, e));
            }
        }
        if (parts.isEmpty()) {
            throw failures.getFirst();
        }
        return new Opened<>(parts, skipped);
    }

    /**
     * The parts of {@code opened} whose fields can be combined with those of the parts before them
     * (ADR 0021 §5). The others are closed and added to the skipped directories, which stay in the
     * order of {@code dirs}. It owns the parts of {@code opened}: if it throws, it has closed them all.
     *
     * @param dir the directory of a part
     * @param conflict why the fields of some parts can't be combined
     * @param closeables what closes a part, in order
     */
    public static <P> Opened<P> compatible(
            List<Path> dirs,
            Opened<P> opened,
            Function<P, Path> dir,
            Conflict<P> conflict,
            Function<P, List<Closeable>> closeables)
            throws IOException {
        List<P> open = new ArrayList<>(opened.parts());
        try {
            if (conflict.of(open).isEmpty()) {
                return opened;
            }
            List<P> accepted = new ArrayList<>();
            List<P> refused = new ArrayList<>();
            List<Skipped> skipped = new ArrayList<>(opened.skipped());
            for (P part : opened.parts()) {
                List<P> candidates = new ArrayList<>(accepted);
                candidates.add(part);
                Optional<String> reason = conflict.of(candidates);
                if (reason.isEmpty()) {
                    accepted.add(part);
                } else {
                    refused.add(part);
                    skipped.add(new Skipped(
                            dir.apply(part),
                            "Its fields conflict with those of the indexes before it: " + reason.get()));
                }
            }
            // From here on, a failure closes only the accepted parts: closeAll tries every refused one.
            open = accepted;
            List<Closeable> release = new ArrayList<>();
            for (P part : refused) {
                release.addAll(closeables.apply(part));
            }
            closeAll(release);
            skipped.sort(Comparator.comparingInt(skip -> dirs.indexOf(skip.path())));
            return new Opened<>(accepted, skipped);
        } catch (IOException | RuntimeException e) {
            List<Closeable> release = new ArrayList<>();
            for (P part : open) {
                release.addAll(closeables.apply(part));
            }
            closeAll(e, release);
            throw e;
        }
    }

    /** Closes each of {@code closeables}, then throws the first failure, with the others suppressed. */
    public static void closeAll(List<? extends Closeable> closeables) throws IOException {
        IOException failure = null;
        for (Closeable closeable : closeables) {
            try {
                closeable.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Closes each of {@code closeables} after {@code failure}, to which their failures are added. */
    public static void closeAll(Throwable failure, List<? extends Closeable> closeables) {
        for (Closeable closeable : closeables) {
            try {
                closeable.close();
            } catch (IOException | RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
    }
}
