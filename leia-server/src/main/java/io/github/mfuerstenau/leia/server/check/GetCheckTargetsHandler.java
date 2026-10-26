package io.github.mfuerstenau.leia.server.check;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetIndex;
import io.github.mfuerstenau.leia.protocol.check.CheckTargetSegment;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsParams;
import io.github.mfuerstenau.leia.protocol.check.GetCheckTargetsResult;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code getCheckTargets}: the indexes of the {@link CurrentIndex}, opened and skipped, each with the
 * segments of its latest commit, which {@link StartCheckIndexHandler} can be told to check (ADR
 * 0093). It reads only {@code segments_N} and the sizes of the files, so it runs on the calling
 * thread, with or without a reader. An index it can't list has an error, and never fails the call.
 */
public final class GetCheckTargetsHandler implements MethodHandler<GetCheckTargetsParams, GetCheckTargetsResult> {

    private final IndexDirectories directories;

    @Inject
    GetCheckTargetsHandler(IndexDirectories directories) {
        this.directories = directories;
    }

    @Override
    public RpcMethod<GetCheckTargetsParams, GetCheckTargetsResult> method() {
        return Methods.GET_CHECK_TARGETS;
    }

    @Override
    public GetCheckTargetsResult handle(GetCheckTargetsParams params) throws RpcException {
        return new GetCheckTargetsResult(
                directories.all().stream().map(GetCheckTargetsHandler::target).toList());
    }

    private static CheckTargetIndex target(IndexDirectories.IndexDirectory dir) {
        String path = dir.path().toString();
        return switch (dir.route()) {
            case IndexDirectories.Route.Missing missing -> failed(path, dir.skipped(), missing.message(dir.path()));
            case IndexDirectories.Route.Runtime(LuceneRuntime runtime) -> {
                Optional<Commits> commits = runtime.commits();
                if (commits.isEmpty()) {
                    yield failed(path, dir.skipped(), "Lucene " + runtime.luceneVersion() + " can't list the segments");
                }
                try {
                    yield latest(path, dir, commits.get());
                } catch (IOException | RuntimeException e) {
                    yield failed(path, dir.skipped(), "Cannot list the segments: " + e.getMessage());
                }
            }
        };
    }

    /** The latest commit, unless a newer {@code segments_N} can't be read: then the check wouldn't see this one. */
    private static CheckTargetIndex latest(String path, IndexDirectories.IndexDirectory dir, Commits commits)
            throws IOException {
        Commits.CommitList list = commits.commits(dir.path(), dir.kind());
        Optional<Commits.Unreadable> newer = list.unreadable().stream()
                .filter(unreadable -> list.commits().isEmpty()
                        || generation(unreadable.segmentsFile())
                                > list.commits().getFirst().generation())
                .findFirst();
        if (newer.isPresent()) {
            return failed(
                    path,
                    dir.skipped(),
                    newer.get().segmentsFile() + " can't be read: "
                            + newer.get().message());
        }
        if (list.commits().isEmpty()) {
            return failed(path, dir.skipped(), "The index has no commit");
        }
        Commits.Commit latest = list.commits().getFirst();
        Commits.CommitDetails details = commits.commit(dir.path(), dir.kind(), latest.generation())
                .orElseThrow(() -> new IOException("The commit " + latest.segmentsFile() + " is gone"));
        List<CheckTargetSegment> segments = details.segments().stream()
                .map(segment -> new CheckTargetSegment(segment.name(), segment.maxDoc(), segment.sizeInBytes()))
                .toList();
        return new CheckTargetIndex(
                path, dir.skipped(), Optional.of(latest.segmentsFile()), segments, Optional.empty());
    }

    /** The generation in {@code segments_N}, N in base 36; -1 if the name has none. */
    private static long generation(String segmentsFile) {
        int underscore = segmentsFile.indexOf('_');
        if (underscore < 0) {
            return -1;
        }
        try {
            return Long.parseLong(segmentsFile.substring(underscore + 1), Character.MAX_RADIX);
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    private static CheckTargetIndex failed(String path, boolean skipped, String error) {
        return new CheckTargetIndex(path, skipped, Optional.empty(), List.of(), Optional.of(error));
    }
}
