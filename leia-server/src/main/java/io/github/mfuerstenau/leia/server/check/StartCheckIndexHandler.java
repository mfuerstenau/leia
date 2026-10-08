package io.github.mfuerstenau.leia.server.check;

import io.github.mfuerstenau.leia.core.DirectoryImpl;
import io.github.mfuerstenau.leia.core.IndexCheck;
import io.github.mfuerstenau.leia.core.OpenedIndex;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.SegmentCheck;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code startCheckIndex}: checks every index of the {@link CurrentIndex}, with or without a reader,
 * in a job ({@link GetCheckIndexHandler}): the opened indexes in order, then the skipped ones (ADR
 * 0022). The output goes to the job's {@link CheckLog}.
 */
public final class StartCheckIndexHandler implements MethodHandler<StartCheckIndexParams, JobStarted> {

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartCheckIndexHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartCheckIndexParams, JobStarted> method() {
        return Methods.START_CHECK_INDEX;
    }

    /** An index directory to check, and whether it was skipped when its path was opened. */
    private record Target(Path dir, boolean skipped) {}

    @Override
    public JobStarted handle(StartCheckIndexParams params) throws RpcException {
        int level = params.level().orElse(StartCheckIndexParams.DEFAULT_LEVEL);
        if (level < StartCheckIndexParams.MIN_LEVEL || level > StartCheckIndexParams.MAX_LEVEL) {
            throw new RpcException(
                    ErrorCode.INVALID_PARAMS,
                    "Invalid params: level must be between " + StartCheckIndexParams.MIN_LEVEL + " and "
                            + StartCheckIndexParams.MAX_LEVEL + ", not " + level);
        }
        OpenedIndex index = currentIndex.opened();
        List<Target> targets = new ArrayList<>();
        index.directories().forEach(dir -> targets.add(new Target(dir, false)));
        index.skippedDirectories().forEach(dir -> targets.add(new Target(dir, true)));
        DirectoryImpl directoryImpl = index.directoryImpl();
        CheckLog log = new CheckLog();
        long jobId = jobs.start(
                "check index of " + index.path() + " at level " + level,
                CheckIndexResult.class,
                log,
                progress -> check(targets, directoryImpl, level, log, progress));
        return new JobStarted(jobId);
    }

    private static CheckIndexResult check(
            List<Target> targets, DirectoryImpl directoryImpl, int level, CheckLog log, ScanProgress progress) {
        long start = System.nanoTime();
        List<io.github.mfuerstenau.leia.protocol.check.IndexCheck> checks = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            log.add(
                    targets.size() == 1
                            ? "Checking " + target.dir()
                            : "Checking index " + (i + 1) + " of " + targets.size() + ": " + target.dir());
            IndexCheck.Result result = IndexCheck.check(target.dir(), directoryImpl, level, log::add, progress);
            checks.add(indexCheck(result, target.skipped()));
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
