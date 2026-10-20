package io.github.mfuerstenau.leia.server.index;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexEntry;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.runtime.IndexVersion;
import io.github.mfuerstenau.leia.server.runtime.OpenDirectories;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import io.github.mfuerstenau.leia.server.runtime.SegmentsHeader;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@code openIndex}: opens the indexes of a path read-only, with or without a reader (ADR 0021), and
 * makes them the {@link CurrentIndex}. A runtime reads them (ADR 0071).
 */
public final class OpenIndexHandler implements MethodHandler<OpenIndexParams, OpenIndexResult> {

    private static final Logger LOG = Logger.getLogger(OpenIndexHandler.class.getName());

    private final CurrentIndex currentIndex;
    private final IndexDirectories directories;

    @Inject
    OpenIndexHandler(CurrentIndex currentIndex, IndexDirectories directories) {
        this.currentIndex = currentIndex;
        this.directories = directories;
    }

    @Override
    public RpcMethod<OpenIndexParams, OpenIndexResult> method() {
        return Methods.OPEN_INDEX;
    }

    @Override
    public OpenIndexResult handle(OpenIndexParams params) throws RpcException {
        Path path;
        try {
            path = Path.of(params.path());
        } catch (InvalidPathException e) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: not a path: " + e.getMessage(), e);
        }

        DirectoryKind kind = params.directoryImpl()
                .map(Enum::name)
                .map(DirectoryKind::valueOf)
                .orElse(DirectoryKind.FS);
        OpenMode mode = params.mode().orElse(OpenMode.READER);
        Path root = IndexFinder.root(path);
        List<Path> dirs = IndexFinder.find(root);
        // Routed by the version of the first index found, which decides for all (ADR 0044 §3, ADR 0045).
        Optional<IndexVersion> version = SegmentsHeader.read(dirs.getFirst());
        Optional<LuceneRuntime> runtime = switch (directories.route(version)) {
            case IndexDirectories.Route.Runtime(LuceneRuntime found) -> Optional.of(found);
            case IndexDirectories.Route.Missing missing -> {
                if (mode == OpenMode.READER) {
                    throw missing.error(dirs.getFirst());
                }
                // Without a reader it opens: getCommits and the check say what is missing.
                yield Optional.empty();
            }
        };
        return switch (mode) {
            case READER -> openWithRuntime(root, dirs, version, runtime.orElseThrow(), kind);
            case DIRECTORY_ONLY -> openDirectories(root, dirs, runtime, kind);
        };
    }

    /**
     * Opens the indexes of a path through the runtime for its first index (ADR 0044, ADR 0045): those
     * it reads, as one; the others are skipped.
     */
    private OpenIndexResult openWithRuntime(
            Path root, List<Path> dirs, Optional<IndexVersion> version, LuceneRuntime runtime, DirectoryKind kind)
            throws RpcException {
        List<Path> readable = new ArrayList<>();
        List<RuntimeIndex.Skipped> skipped = new ArrayList<>();
        for (Path dir : dirs) {
            Optional<IndexVersion> dirVersion = SegmentsHeader.read(dir);
            // One without a version the runtime tries, and skips if it fails; so one of the first's
            // major, which the runtime may be trying only to report its format.
            if (dirVersion.isEmpty()
                    || runtime.indexMajors().contains(dirVersion.get().major())
                    || dirVersion.map(IndexVersion::major).equals(version.map(IndexVersion::major))) {
                readable.add(dir);
            } else {
                skipped.add(new RuntimeIndex.Skipped(
                        dir, "Written by Lucene " + dirVersion.get().writtenBy() + "; open it on its own"));
            }
        }
        RuntimeIndex index;
        try {
            index = runtime.open(readable, kind);
        } catch (IndexReadException e) {
            throw RuntimeResults.readFailure(e);
        } catch (IOException | RuntimeException e) {
            throw new RpcException(
                    ErrorCode.INDEX_NOT_READABLE,
                    "Cannot read the index"
                            + version.map(found -> " written by Lucene " + found.writtenBy())
                                    .orElse("") + " with Lucene " + runtime.luceneVersion() + ": " + e,
                    e);
        }
        skipped.addAll(index.skipped());
        skipped.sort(Comparator.comparingInt(skippedIndex -> dirs.indexOf(skippedIndex.path())));
        skipped.forEach(dir -> LOG.info(() -> "Skipped " + dir.path() + ": " + dir.message()));
        OpenRuntime open = new OpenRuntime(root, runtime, index, skipped);
        currentIndex.replace(open);
        LOG.info(() -> "Opened " + root + " (" + RuntimeResults.describe(open) + ", " + kind + ")");
        return RuntimeResults.opened(open);
    }

    /**
     * Opens the indexes of a path without a reader (ADR 0021): only their directories. The commits and
     * the check of each go to the runtime that reads it, and say so if it isn't loaded; the levels of
     * the check follow the runtime of the first index, as the features do (ADR 0046).
     */
    private OpenIndexResult openDirectories(
            Path root, List<Path> dirs, Optional<LuceneRuntime> runtime, DirectoryKind kind) {
        OpenDirectories open = new OpenDirectories(root, dirs, kind);
        currentIndex.replace(open);
        LOG.info(() -> "Opened " + root + " (" + dirs.size() + " indexes without a reader, " + kind + ")");
        Set<Feature> features = EnumSet.copyOf(Feature.withoutReader());
        if (!runtime.flatMap(LuceneRuntime::check).map(Check::hasLevels).orElse(false)) {
            features.remove(Feature.CHECK_LEVELS);
        }
        return new OpenIndexResult(
                root.toString(),
                OpenMode.DIRECTORY_ONLY,
                OptionalInt.empty(),
                OptionalInt.empty(),
                dirs.stream()
                        .map(dir -> new IndexEntry(dir.toString(), Optional.empty()))
                        .toList(),
                List.of(),
                features,
                runtime.map(LuceneRuntime::luceneVersion).orElse(""));
    }
}
