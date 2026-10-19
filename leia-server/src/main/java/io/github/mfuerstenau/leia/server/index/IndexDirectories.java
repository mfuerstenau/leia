package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.IndexSummary;
import io.github.mfuerstenau.leia.core.OpenedIndex;
import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.IndexVersion;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import io.github.mfuerstenau.leia.server.runtime.SegmentsHeader;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.util.Version;

/**
 * The index directories of the open path, of either kind of open index, and the Lucene that reads
 * each (ADR 0045): the one place that lists them for the commits and the check, which read a
 * directory anew, without the open reader.
 */
public final class IndexDirectories {

    private final CurrentIndex currentIndex;
    private final Runtimes runtimes;
    private final Routing routing;

    @Inject
    public IndexDirectories(CurrentIndex currentIndex, Runtimes runtimes, Routing routing) {
        this.currentIndex = currentIndex;
        this.runtimes = runtimes;
        this.routing = routing;
    }

    /**
     * An index directory of the open path.
     *
     * @param position its place among the opened directories, as {@code getCommits} counts them;
     *     empty for a skipped one
     * @param generation the generation of its opened commit; empty without a reader or if skipped
     */
    public record IndexDirectory(
            Path path, DirectoryKind kind, OptionalInt position, OptionalLong generation, Route route) {

        public boolean skipped() {
            return position.isEmpty();
        }
    }

    /** Which Lucene reads an index directory. */
    public sealed interface Route {

        /** Lucene 10, through leia-core. */
        record Core() implements Route {}

        /** A runtime of {@code leia-runtime} (ADR 0044). */
        record Runtime(LuceneRuntime runtime) implements Route {}

        /** A runtime that isn't loaded. */
        record Missing(IndexVersion version) implements Route {

            /** The error of a method that needs the runtime, naming its jar. */
            public RpcException error(Path dir) {
                return new RpcException(ErrorCode.MISSING_RUNTIME, message(dir));
            }

            public String message(Path dir) {
                return "The index was written by Lucene " + version.writtenBy() + "; reading it needs the runtime "
                        + Runtimes.jarFor(version.major()).orElse("for Lucene " + version.major())
                        + ", which isn't loaded: " + dir;
            }
        }
    }

    /**
     * Every index directory of the open path: the opened ones in order, then the skipped ones.
     *
     * @throws RpcException {@code NO_INDEX_OPEN} if no index is open
     */
    public List<IndexDirectory> all() throws RpcException {
        List<IndexDirectory> all = new ArrayList<>();
        if (currentIndex.runtime().isPresent()) {
            OpenRuntime open = currentIndex.runtime().get();
            RuntimeIndex index = open.index();
            List<RuntimeIndex.IndexStats> stats;
            try {
                stats = index.stats().indexes();
            } catch (IOException e) {
                throw new RpcException(
                        ErrorCode.INDEX_NOT_READABLE, "Cannot read the index statistics: " + e.getMessage(), e);
            }
            for (int i = 0; i < stats.size(); i++) {
                all.add(new IndexDirectory(
                        stats.get(i).path(),
                        index.directory(),
                        OptionalInt.of(i),
                        OptionalLong.of(stats.get(i).generation()),
                        new Route.Runtime(open.runtime())));
            }
            for (RuntimeIndex.Skipped skipped : open.skipped()) {
                all.add(skipped(skipped.path(), index.directory()));
            }
            return List.copyOf(all);
        }
        OpenedIndex opened = currentIndex.opened();
        DirectoryKind kind = DirectoryKind.valueOf(opened.directoryImpl().name());
        List<Path> dirs = opened.directories();
        for (int i = 0; i < dirs.size(); i++) {
            OptionalLong generation = switch (opened) {
                case ReadOnlyIndex readOnly -> generation(readOnly.indexes().get(i));
                case DirectoryOnlyIndex _ -> OptionalLong.empty();
            };
            all.add(new IndexDirectory(dirs.get(i), kind, OptionalInt.of(i), generation, route(dirs.get(i))));
        }
        for (Path dir : opened.skippedDirectories()) {
            all.add(skipped(dir, kind));
        }
        return List.copyOf(all);
    }

    /**
     * The opened index directory at {@code index}, 0 if absent (ADR 0021).
     *
     * @throws RpcException {@code INVALID_PARAMS} if the open path has no such index directory
     */
    public IndexDirectory opened(OptionalInt index) throws RpcException {
        int position = index.orElse(0);
        List<IndexDirectory> opened =
                all().stream().filter(dir -> !dir.skipped()).toList();
        if (position < 0 || position >= opened.size()) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS, "Invalid params: no index " + position + "; there are " + opened.size());
        }
        return opened.get(position);
    }

    private IndexDirectory skipped(Path dir, DirectoryKind kind) {
        return new IndexDirectory(dir, kind, OptionalInt.empty(), OptionalLong.empty(), route(dir));
    }

    private static OptionalLong generation(IndexSummary summary) {
        return OptionalLong.of(SegmentInfos.generationFromSegmentsFileName(summary.segmentsFile()));
    }

    /**
     * The Lucene that reads {@code dir}, by the version of its newest {@code segments_N} (ADR 0044
     * §3): an old one that a loaded runtime reads goes to it, one before 4.0 without a runtime is
     * missing it, and every other one goes to Lucene 10, as 4.x to 8.x do without a reader. With
     * {@link Routing#lucene10Runtime()}, one of Lucene 9 or 10 goes to a runtime too, if one reads it.
     */
    public Route route(Path dir) {
        Optional<IndexVersion> version = SegmentsHeader.read(dir);
        if (version.isEmpty() || routing.core(version.get().major(), Version.MIN_SUPPORTED_MAJOR)) {
            return new Route.Core();
        }
        Optional<LuceneRuntime> runtime = runtimes.forMajor(version.get().major());
        if (runtime.isPresent()) {
            return new Route.Runtime(runtime.get());
        }
        return version.get().major() < 4 ? new Route.Missing(version.get()) : new Route.Core();
    }
}
