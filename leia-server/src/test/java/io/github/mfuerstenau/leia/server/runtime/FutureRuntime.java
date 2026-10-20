package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Extensions;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A runtime built for a newer runtime interface, which leia must refuse (ADR 0044). No services file
 * of the test classpath names it: {@code JarsBackendTest} writes a jar with one.
 */
public final class FutureRuntime implements LuceneRuntime {

    @Override
    public int apiVersion() {
        return 99;
    }

    @Override
    public String luceneVersion() {
        return "3.6.2";
    }

    @Override
    public Set<Integer> indexMajors() {
        return Set.of(2, 3);
    }

    @Override
    public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) {
        throw new AssertionError("A refused runtime is never used");
    }

    @Override
    public Optional<Commits> commits() {
        throw new AssertionError("A refused runtime is never used");
    }

    @Override
    public Optional<Check> check() {
        throw new AssertionError("A refused runtime is never used");
    }

    @Override
    public Optional<Analysis> analysis() {
        throw new AssertionError("A refused runtime is never used");
    }

    @Override
    public Optional<Extensions> extensions() {
        throw new AssertionError("A refused runtime is never used");
    }
}
