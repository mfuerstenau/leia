package io.github.mfuerstenau.leia.server.check;

import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.IndexCheck;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.runtime.OpenRuntime;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code startCheckIndex}: checks every index of the {@link CurrentIndex}, with or without a reader,
 * in a job ({@link GetCheckIndexHandler}): the opened indexes in order, then the skipped ones (ADR
 * 0022), each with the Lucene that reads it (ADR 0045). The output goes to the job's {@link CheckLog}.
 */
public final class StartCheckIndexHandler implements MethodHandler<StartCheckIndexParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final IndexDirectories directories;
    private final Jobs jobs;

    @Inject
    StartCheckIndexHandler(CurrentIndex currentIndex, IndexDirectories directories, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.directories = directories;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartCheckIndexParams, JobStarted> method() {
        return Methods.START_CHECK_INDEX;
    }

    @Override
    public JobStarted handle(StartCheckIndexParams params) throws RpcException {
        int level = params.level().orElse(StartCheckIndexParams.DEFAULT_LEVEL);
        if (level < StartCheckIndexParams.MIN_LEVEL || level > StartCheckIndexParams.MAX_LEVEL) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: level must be between " + StartCheckIndexParams.MIN_LEVEL + " and "
                            + StartCheckIndexParams.MAX_LEVEL + ", not " + level);
        }
        List<IndexDirectories.IndexDirectory> targets = directories.all();
        Path path = currentIndex
                .runtime()
                .map(OpenRuntime::path)
                .orElseGet(() -> currentIndex.get().orElseThrow().path());
        CheckLog log = new CheckLog();
        long jobId = jobs.start(
                "check index of " + path + " at level " + level,
                CheckIndexResult.class,
                log,
                progress -> check(targets, level, log, progress));
        return new JobStarted(jobId);
    }

    /**
     * Checks each of {@code targets} with the Lucene that reads it (ADR 0045): Lucene 10 at {@code
     * level}, a runtime at its one level.
     */
    private static CheckIndexResult check(
            List<IndexDirectories.IndexDirectory> targets, int level, CheckLog log, ScanProgress progress) {
        long start = System.nanoTime();
        List<io.github.mfuerstenau.leia.protocol.check.IndexCheck> checks = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            IndexDirectories.IndexDirectory target = targets.get(i);
            log.add(
                    targets.size() == 1
                            ? "Checking " + target.path()
                            : "Checking index " + (i + 1) + " of " + targets.size() + ": " + target.path());
            checks.add(
                    switch (target.route()) {
                        case IndexDirectories.Route.Core() ->
                            indexCheck(
                                    IndexCheck.check(
                                            target.path(),
                                            DirectoryImpl.valueOf(target.kind().name()),
                                            level,
                                            log::add,
                                            progress),
                                    target.skipped());
                        case IndexDirectories.Route.Runtime(LuceneRuntime runtime) -> {
                            log.add("Checking with Lucene " + runtime.luceneVersion() + ", which has no levels");
                            yield RuntimeResults.check(
                                    runtime, target.path(), target.kind(), target.skipped(), log::add, progress);
                        }
                        case IndexDirectories.Route.Missing missing -> {
                            String message = missing.message(target.path());
                            log.add(message);
                            yield RuntimeResults.failedCheck(target.path(), target.skipped(), message);
                        }
                    });
        }
        return new CheckIndexResult(List.copyOf(checks), (System.nanoTime() - start) / 1_000_000);
    }

    static io.github.mfuerstenau.leia.protocol.check.IndexCheck indexCheck(IndexCheck.Result result, boolean skipped) {
        return new io.github.mfuerstenau.leia.protocol.check.IndexCheck(
                result.path().toString(),
                skipped,
                result.clean(),
                result.error(),
                result.segmentsFile(),
                result.missingSegments(),
                result.toolOutOfDate(),
                result.segmentCount(),
                result.badSegments(),
                result.lostDocs(),
                result.validCounter(),
                result.segments().stream()
                        .map(segment -> new SegmentCheck(
                                segment.name(),
                                segment.maxDoc(),
                                segment.codec(),
                                segment.compound(),
                                segment.files(),
                                segment.sizeBytes(),
                                segment.hasDeletions(),
                                segment.openReaderPassed(),
                                segment.error()))
                        .toList());
    }
}
