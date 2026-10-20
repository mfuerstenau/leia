package io.github.mfuerstenau.leia.lucene7;

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
import org.apache.lucene.util.Version;

/** Lucene 7.7, relocated, for indexes written by Lucene 6.x and 7.x (ADR 0048). */
public final class Lucene7Runtime implements LuceneRuntime {

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
        return Set.of(6, 7);
    }

    @Override
    public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) throws IOException {
        return Lucene7Index.open(dirs, kind);
    }

    @Override
    public Optional<Commits> commits() {
        return Optional.of(new Lucene7Commits());
    }

    @Override
    public Optional<Check> check() {
        return Optional.of(new Lucene7Check());
    }

    @Override
    public Optional<Analysis> analysis() {
        return Optional.empty();
    }

    @Override
    public Optional<Extensions> extensions() {
        // Its service files are relocated too, so a jar compiled against org.apache.lucene adds nothing (ADR 0068).
        return Optional.empty();
    }
}
