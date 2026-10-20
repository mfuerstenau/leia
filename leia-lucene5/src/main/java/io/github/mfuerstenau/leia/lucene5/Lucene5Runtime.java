package io.github.mfuerstenau.leia.lucene5;

import io.github.mfuerstenau.leia.runtime.Analysis;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.Extensions;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.support.Spi;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.analysis.util.CharFilterFactory;
import org.apache.lucene.analysis.util.TokenFilterFactory;
import org.apache.lucene.analysis.util.TokenizerFactory;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.util.Version;

/**
 * Lucene 5.5, in a class loader of its own, for indexes written by Lucene 4.x and 5.x (ADR 0046, ADR
 * 0073).
 */
public final class Lucene5Runtime implements LuceneRuntime {

    public Lucene5Runtime() {
        // 5.5 builds its lists of codecs, formats and analysis factories on first use, through the
        // context class loader, which in leia sees Lucene 10's (ADR 0073): built here, through this
        // runtime's loader, they stay its own.
        Spi.initialize(Lucene5Runtime.class, () -> {
            Codec.availableCodecs();
            PostingsFormat.availablePostingsFormats();
            DocValuesFormat.availableDocValuesFormats();
            TokenizerFactory.availableTokenizers();
            TokenFilterFactory.availableTokenFilters();
            CharFilterFactory.availableCharFilters();
        });
    }

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
        // Its class loader sees no jar of --jar (ADR 0068, ADR 0073).
        return Optional.empty();
    }
}
