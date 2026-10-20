package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Extensions;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.lucene.util.Version;

/**
 * Lucene 10, through leia-core, for indexes created by Lucene 9.x and 10.x (ADR 0065). Unlike the other
 * runtimes, it is loaded into the jar chain, not a class loader of its own: the jars of {@code --jar}
 * link against its {@code org.apache.lucene} (ADR 0073).
 */
public final class Lucene10Runtime implements LuceneRuntime {

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public String luceneVersion() {
        return Version.LATEST.toString();
    }

    @Override
    public Set<Integer> indexMajors() {
        return IntStream.rangeClosed(Version.MIN_SUPPORTED_MAJOR, Version.LATEST.major)
                .boxed()
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) throws IOException {
        return Lucene10Index.open(dirs, kind);
    }

    @Override
    public Optional<Commits> commits() {
        return Optional.of(new Lucene10Commits());
    }

    @Override
    public Optional<Check> check() {
        return Optional.of(new Lucene10Check());
    }

    @Override
    public Optional<Analysis> analysis() {
        return Optional.of(new Lucene10Analysis());
    }

    @Override
    public Optional<Extensions> extensions() {
        return Optional.of(new Lucene10Extensions());
    }
}
