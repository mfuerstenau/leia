package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.IndexVersion;
import io.github.mfuerstenau.leia.server.runtime.OpenDirectories;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
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

/**
 * The index directories of the open path, with or without a reader, and the runtime that reads
 * each (ADR 0045, ADR 0071): the one place that lists them for the commits and the check, which read
 * a directory anew, without the open reader.
 */
public final class IndexDirectories {

    private final CurrentIndex currentIndex;
    private final Runtimes runtimes;

    @Inject
    public IndexDirectories(CurrentIndex currentIndex, Runtimes runtimes) {
        this.currentIndex = currentIndex;
        this.runtimes = runtimes;
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

    /** Which runtime reads an index directory. */
    public sealed interface Route {

        /** A runtime of {@code leia-runtime} (ADR 0044). */
        record Runtime(LuceneRuntime runtime) implements Route {}

        /**
         * A runtime that isn't loaded.
         *
         * @param version the index's version; empty if it can't be detected
         */
        record Missing(Optional<IndexVersion> version) implements Route {

            public Missing(IndexVersion version) {
                this(Optional.of(version));
            }

            /** The error of a method that needs the runtime, naming its folder. */
            public RpcException error(Path dir) {
                return new RpcException(ErrorCode.MISSING_RUNTIME, message(dir));
            }

            public String message(Path dir) {
                return version.map(found -> "The index was written by Lucene " + found.writtenBy()
                                        + "; reading it needs the runtime "
                                        + RuntimeFolders.forMajor(found.major())
                                                .map(IndexDirectories::folder)
                                                .orElse("for Lucene " + found.major()))
                                .orElse("Reading the index needs the runtime " + folder(RuntimeFolders.LATEST))
                        + ", which isn't loaded: " + dir;
            }
        }
    }

    /** How a message names a runtime: its folder, such as {@code runtimes/lucene9}. */
    static String folder(RuntimeFolders.Known known) {
        return "runtimes/" + known.folder();
    }

    /**
     * Every index directory of the open path: the opened ones in order, then the skipped ones.
     *
     * @throws RpcException {@code NO_INDEX_OPEN} if no index is open
     */
    // The current index owns it (ADR 0088)
    @SuppressWarnings("java:S2095")
    public List<IndexDirectory> all() throws RpcException {
        List<IndexDirectory> all = new ArrayList<>();
        switch (currentIndex.opened()) {
            case OpenRuntime open -> {
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
                    all.add(new IndexDirectory(
                            skipped.path(),
                            index.directory(),
                            OptionalInt.empty(),
                            OptionalLong.empty(),
                            route(skipped.path())));
                }
            }
            case OpenDirectories open -> {
                List<Path> dirs = open.directories();
                for (int i = 0; i < dirs.size(); i++) {
                    all.add(new IndexDirectory(
                            dirs.get(i), open.kind(), OptionalInt.of(i), OptionalLong.empty(), route(dirs.get(i))));
                }
            }
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

    /**
     * The runtime that reads {@code dir}, by the version of its newest {@code segments_N} (ADR 0044
     * §3, ADR 0071): the one for its major; for a major newer than any runtime reads, or a version that
     * can't be detected, the one for the newest indexes ({@link Runtimes#latest()}), which then reports
     * the format error itself.
     */
    public Route route(Path dir) {
        return route(SegmentsHeader.read(dir));
    }

    /** As {@link #route(Path)}, for the version of a directory. */
    public Route route(Optional<IndexVersion> version) {
        if (version.isPresent() && !runtimes.newerThanAll(version.get().major())) {
            return runtimes.forMajor(version.get().major())
                    .<Route>map(Route.Runtime::new)
                    .orElseGet(() -> new Route.Missing(version));
        }
        return runtimes.latest().<Route>map(Route.Runtime::new).orElseGet(() -> new Route.Missing(version));
    }
}
