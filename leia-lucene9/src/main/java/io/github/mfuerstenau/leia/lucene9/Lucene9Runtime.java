package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.util.Version;

/** Lucene 9.12, relocated, for indexes created by Lucene 8.x (ADR 0049). */
public final class Lucene9Runtime implements LuceneRuntime {

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
        return Set.of(8);
    }

    @Override
    public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) throws IOException {
        return Lucene9Index.open(dirs, kind);
    }

    @Override
    public Optional<Commits> commits() {
        return Optional.of(new Lucene9Commits());
    }

    @Override
    public Optional<Check> check() {
        return Optional.of(new Lucene9Check());
    }

    @Override
    public Optional<Analysis> analysis() {
        return Optional.empty();
    }
}
