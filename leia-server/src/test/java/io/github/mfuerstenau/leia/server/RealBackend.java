package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.GetFilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.GetExplainResult;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.StartExplainParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A {@link LeiaBackend} as the UI starts it, for the tests that go through it with the indexes and
 * jars that leia-core's build writes (see build.gradle.kts). These took over what the Rust TUI's
 * tests against the real server checked (ADR 0034).
 */
final class RealBackend implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    static final Analyzer STANDARD = new Analyzer(
            Optional.of(new StandardAnalyzer()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    static final Similarity BM25 =
            new Similarity(Optional.of(new Bm25Similarity(1.2f, 0.75f)), Optional.empty(), Optional.empty());
    /** Lucene's defaults, as the search screen starts with them. */
    static final ParserOptions DEFAULTS =
            new ParserOptions(false, 0, 0, true, Optional.empty(), Optional.empty(), Optional.empty());

    private final LeiaBackend backend;

    private RealBackend(LeiaBackend backend) {
        this.backend = backend;
    }

    static RealBackend start() throws RpcException {
        return start(List.of());
    }

    static RealBackend start(List<Path> jars) throws RpcException {
        return new RealBackend(LeiaBackend.start(jars));
    }

    /** A test index that leia-core's build writes, given by the system property {@code leia.test.index.<name>}. */
    static Path testIndex(String name) {
        return testFile("leia.test.index." + name);
    }

    /** A file that the build passes as the system property {@code property}. */
    static Path testFile(String property) {
        String path = System.getProperty(property);
        if (path == null) {
            throw new IllegalStateException("System property " + property + " is not set");
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    /** Calls {@code method} and waits for its result. */
    <P, R> R call(RpcMethod<P, R> method, P params) throws Exception {
        try {
            return backend.call(method, params).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Asserts that {@code method} fails with {@code code}, and returns the failure. */
    <P> RpcException fails(RpcMethod<P, ?> method, P params, ErrorCode code) {
        List<RpcException> failure = new ArrayList<>();
        assertThatThrownBy(() -> call(method, params)).isInstanceOfSatisfying(RpcException.class, e -> {
            assertThat(e.code()).as(e.getMessage()).isEqualTo(code);
            failure.add(e);
        });
        return failure.getFirst();
    }

    /** Starts a job and polls it, as the UI does, until {@code done} gives its outcome. */
    <P, G, T> T job(
            RpcMethod<P, JobStarted> start, P params, RpcMethod<JobParams, G> get, Function<G, Optional<T>> done)
            throws Exception {
        long jobId = call(start, params).jobId();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<T> outcome = done.apply(call(get, new JobParams(jobId)));
            if (outcome.isPresent()) {
                return outcome.get();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    SearchResults search(StartSearchParams params) throws Exception {
        return job(Methods.START_SEARCH, params, Methods.GET_SEARCH, GetSearchResult::results);
    }

    /** The first {@code limit} hits of {@code query} with BM25, with an exact total and no stored fields. */
    SearchResults search(SearchQuery query, int limit) throws Exception {
        return search(new StartSearchParams(query, BM25, List.of(), 0, limit, true, Optional.of(List.of())));
    }

    Explanation explain(SearchQuery query, Similarity similarity, int docId) throws Exception {
        return job(
                Methods.START_EXPLAIN,
                new StartExplainParams(query, similarity, docId),
                Methods.GET_EXPLAIN,
                GetExplainResult::explanation);
    }

    ReconstructResult reconstruct(int docId, Optional<List<String>> fields) throws Exception {
        return job(
                Methods.START_RECONSTRUCT,
                new StartReconstructParams(docId, fields),
                Methods.GET_RECONSTRUCT,
                GetReconstructResult::result);
    }

    FilterResult filter(StartFilterParams params) throws Exception {
        return job(Methods.START_FILTER, params, Methods.GET_FILTER, GetFilterResult::result);
    }

    /** The hits and the outcome of a stored-values scan, polled as the search screen does. */
    record StoredScan(List<StoredScanHit> hits, StoredScanResult result) {}

    StoredScan storedScan(StartStoredScanParams params) throws Exception {
        long jobId = call(Methods.START_STORED_SCAN, params).jobId();
        List<StoredScanHit> hits = new ArrayList<>();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            GetStoredScanResult poll = call(Methods.GET_STORED_SCAN, new GetStoredScanParams(jobId, hits.size()));
            hits.addAll(poll.hits());
            assertThat(poll.hitCount()).isEqualTo(hits.size());
            if (poll.result().isPresent()) {
                return new StoredScan(hits, poll.result().get());
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    /** The classic parser on {@code expression}, with {@code body} as the default field. */
    static SearchQuery classic(String expression) {
        return classic(expression, DEFAULTS);
    }

    static SearchQuery classic(String expression, ParserOptions options) {
        return new SearchQuery(
                Optional.of(new ClassicQuery(expression, "body", QueryOperator.OR, options, 2, false, false, STANDARD)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** The standard parser on {@code expression}, with {@code body} as the default field. */
    static SearchQuery standard(String expression, QueryOperator operator, List<PointField> points) {
        return new SearchQuery(
                Optional.empty(),
                Optional.of(new StandardQuery(expression, "body", operator, DEFAULTS, points, STANDARD)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @Override
    public void close() {
        backend.close();
    }
}
