package io.github.mfuerstenau.leia.lucene3;

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
import org.apache.lucene.util.Constants;

/** Lucene 3.6, in a class loader of its own, for indexes written by Lucene 2.x and 3.x (ADR 0044, ADR 0073). */
public final class Lucene3Runtime implements LuceneRuntime {

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public String luceneVersion() {
        return Constants.LUCENE_MAIN_VERSION;
    }

    @Override
    public Set<Integer> indexMajors() {
        return Set.of(2, 3);
    }

    @Override
    public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) throws IOException {
        return Lucene3Index.open(dirs, kind);
    }

    @Override
    public Optional<Commits> commits() {
        return Optional.of(new Lucene3Commits());
    }

    @Override
    public Optional<Check> check() {
        return Optional.of(new Lucene3Check());
    }

    @Override
    public Optional<Analysis> analysis() {
        return Optional.empty();
    }

    @Override
    public Optional<Extensions> extensions() {
        // Its class loader sees no jar of --jar (ADR 0068, ADR 0073).
        return Optional.empty();
    }
}
