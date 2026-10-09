package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.core.ScanProgress;
import io.github.mfuerstenau.leia.core.StoredScan;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.ValueRef;
import io.github.mfuerstenau.leia.server.MethodHandler;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.index.CurrentIndex;
import io.github.mfuerstenau.leia.server.job.Jobs;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code startStoredScan}: checks the params and compiles the pattern at once, then scans the stored
 * values of the {@link CurrentIndex} in a job ({@link GetStoredScanHandler}, ADR 0027).
 */
public final class StartStoredScanHandler implements MethodHandler<StartStoredScanParams, JobStarted> {

    /** How long matching one value may take before the scan skips it. */
    static final Duration VALUE_TIME_LIMIT = Duration.ofSeconds(1);

    private final CurrentIndex currentIndex;
    private final Jobs jobs;

    @Inject
    StartStoredScanHandler(CurrentIndex currentIndex, Jobs jobs) {
        this.currentIndex = currentIndex;
        this.jobs = jobs;
    }

    @Override
    public RpcMethod<StartStoredScanParams, JobStarted> method() {
        return Methods.START_STORED_SCAN;
    }

    @Override
    public JobStarted handle(StartStoredScanParams params) throws RpcException {
        ReadOnlyIndex index = currentIndex.readable();
        Optional<Set<String>> fields = params.fields().map(Set::copyOf);
        if (fields.isPresent()) {
            for (String field : fields.get()) {
                SearchQueries.checkField(index, field);
            }
        }
        Pattern pattern = pattern(params.pattern(), params.caseInsensitive());
        boolean includeDeleted = params.includeDeleted();
        StoredScanHits hits = new StoredScanHits();
        long jobId = jobs.start(
                "scan stored values" + fields.map(names -> " of " + names).orElse("") + " for /" + pattern + "/"
                        + (params.caseInsensitive() ? "i" : "") + (includeDeleted ? ", deleted documents too" : ""),
                StoredScanResult.class,
                hits,
                progress -> scan(index, pattern, fields, includeDeleted, hits, progress));
        return new JobStarted(jobId);
    }

    /**
     * @throws RpcException {@code INVALID_QUERY} if the pattern doesn't compile, with where it fails
     */
    private static Pattern pattern(String pattern, boolean caseInsensitive) throws RpcException {
        try {
            return Pattern.compile(pattern, caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            throw new RpcException(
                    ErrorCode.INVALID_QUERY,
                    "Invalid query: " + e.getDescription() + (e.getIndex() >= 0 ? " at index " + e.getIndex() : ""),
                    e);
        }
    }

    private static StoredScanResult scan(
            ReadOnlyIndex index,
            Pattern pattern,
            Optional<Set<String>> fields,
            boolean includeDeleted,
            StoredScanHits hits,
            ScanProgress progress)
            throws RpcException {
        try {
            StoredScan.Summary summary = index.storedScan(
                    pattern, fields, includeDeleted, StartStoredScanParams.MAX_HITS, VALUE_TIME_LIMIT, hits, progress);
            return new StoredScanResult(
                    summary.scannedDocs(),
                    summary.matchedDocs(),
                    summary.matchedValues(),
                    summary.hitsTruncated(),
                    summary.timedOutValues(),
                    summary.timedOut().stream()
                            .map(value -> new ValueRef(value.docId(), value.field(), value.valueIndex()))
                            .toList(),
                    summary.took().toMillis());
        } catch (IOException e) {
            throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot read the stored values: " + e.getMessage(), e);
        }
    }
}
