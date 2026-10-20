package io.github.mfuerstenau.leia.lucene5;

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

/** Lucene 5.5, relocated, for indexes written by Lucene 4.x and 5.x (ADR 0046). */
public final class Lucene5Runtime implements LuceneRuntime {

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
        return Set.of(4, 5);
    }

    @Override
    public RuntimeIndex open(List<Path> dirs, DirectoryKind kind) throws IOException {
        return Lucene5Index.open(dirs, kind);
    }

    @Override
    public Optional<Commits> commits() {
        return Optional.of(new Lucene5Commits());
    }

    @Override
    public Optional<Check> check() {
        return Optional.of(new Lucene5Check());
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
