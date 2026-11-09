package io.github.mfuerstenau.leia.server.check;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.CheckTarget;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.runtime.Check;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.runtime.support.Optionals;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.index.IndexDirectories;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.job.ScanProgress;
import io.github.mfuerstenau.leia.server.runtime.RuntimeResults;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code startCheckIndex}: checks the indexes of the {@link CurrentIndex}, with or without a reader,
 * in a job ({@link GetCheckIndexHandler}): the opened indexes in order, then the skipped ones (ADR
 * 0022), each with the Lucene that reads it (ADR 0045); all of them, or the indexes and segments
 * that the caller names (ADR 0093). The output goes to the job's {@link CheckLog}.
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
            throw invalid("level must be between " + StartCheckIndexParams.MIN_LEVEL + " and "
                    + StartCheckIndexParams.MAX_LEVEL + ", not " + level);
        }
        int processors = Runtime.getRuntime().availableProcessors();
        if (params.threads().isPresent()
                && (params.threads().getAsInt() < StartCheckIndexParams.MIN_THREADS
                        || params.threads().getAsInt() > processors)) {
            throw invalid("threads must be between " + StartCheckIndexParams.MIN_THREADS + " and " + processors
                    + ", not " + params.threads().getAsInt());
        }
        List<IndexDirectories.IndexDirectory> all = directories.all();
        List<Chosen> chosen = choose(all, params.only().orElse(null));
        Check.Options options = new Check.Options(level, Optionals.orNull(params.threads()), null);
        // The current index owns it (ADR 0088)
        @SuppressWarnings("java:S2095")
        Path path = currentIndex.opened().path();
        CheckLog log = new CheckLog();
        long jobId = jobs.start(
                "check " + (params.only().isPresent() ? "selected indexes" : "index") + " of " + path + " at level "
                        + level,
                CheckIndexResult.class,
                log,
                progress -> check(chosen, options, log, progress));
        return new JobStarted(jobId);
    }

    private static RpcException invalid(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }

    /** An index directory to check, and the segments of it to check; {@code null} for all. */
    private record Chosen(
            IndexDirectories.IndexDirectory dir, @Nullable List<String> segments) {}

    /**
     * The directories of {@code only}, or all of them if it is {@code null}, in the order of {@code all}
     * (ADR 0093).
     *
     * @throws RpcException {@code INVALID_PARAMS} for a path that isn't one of {@code all} or is named
     *     twice, an empty list of segments, or a segment named twice
     */
    private static List<Chosen> choose(List<IndexDirectories.IndexDirectory> all, @Nullable List<CheckTarget> only)
            throws RpcException {
        if (only == null) {
            return all.stream().map(dir -> new Chosen(dir, null)).toList();
        }
        Map<Path, CheckTarget> requested = new HashMap<>();
        for (CheckTarget target : only) {
            Path path = Path.of(target.path()).normalize();
            if (all.stream().noneMatch(dir -> dir.path().normalize().equals(path))) {
                throw invalid("no index " + target.path() + " in the open path");
            }
            if (requested.put(path, target) != null) {
                throw invalid("index " + target.path() + " twice");
            }
            if (target.segments().isPresent()) {
                List<String> names = target.segments().get();
                if (names.isEmpty()) {
                    throw invalid("no segments for " + target.path() + "; leave them out to check all");
                }
                if (new HashSet<>(names).size() != names.size()) {
                    throw invalid("a segment of " + target.path() + " twice");
                }
            }
        }
        List<Chosen> chosen = new ArrayList<>();
        for (IndexDirectories.IndexDirectory dir : all) {
            CheckTarget target = requested.get(dir.path().normalize());
            if (target != null) {
                chosen.add(new Chosen(dir, target.segments().orElse(null)));
            }
        }
        return List.copyOf(chosen);
    }

    /**
     * Checks each of {@code chosen} with the runtime that reads it (ADR 0045, ADR 0071): a runtime
     * with levels at the level of {@code options}, a runtime without at its one level (ADR 0046), and
     * likewise for the threads (ADR 0093).
     */
    private static CheckIndexResult check(
            List<Chosen> chosen, Check.Options options, CheckLog log, ScanProgress progress) {
        long start = System.nanoTime();
        List<io.github.mfuerstenau.leia.protocol.check.IndexCheck> checks = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            IndexDirectories.IndexDirectory target = chosen.get(i).dir();
            Optional<List<String>> segments = Optional.ofNullable(chosen.get(i).segments());
            log.add(
                    chosen.size() == 1
                            ? "Checking " + target.path()
                            : "Checking index " + (i + 1) + " of " + chosen.size() + ": " + target.path());
            checks.add(
                    switch (target.route()) {
                        case IndexDirectories.Route.Runtime(LuceneRuntime runtime) -> {
                            Optional<Check> check = runtime.check();
                            boolean levels = check.map(Check::hasLevels).orElse(false);
                            boolean threads = check.map(Check::hasThreads).orElse(false);
                            log.add("Checking with Lucene " + runtime.luceneVersion()
                                    + (levels ? "" : ", which has no levels")
                                    + (threads && options.threads() != null
                                            ? ", " + options.threads() + " threads"
                                            : "")
                                    + segments.map(names -> ", segments " + String.join(" ", names))
                                            .orElse(""));
                            yield RuntimeResults.check(
                                    runtime,
                                    target.path(),
                                    target.kind(),
                                    new Check.Options(options.level(), options.threads(), segments.orElse(null)),
                                    target.skipped(),
                                    log::add,
                                    progress);
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
}
