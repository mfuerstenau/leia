package io.github.mfuerstenau.leia.server.runtime;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.knn.KnnRecallMiss;
import io.github.mfuerstenau.leia.protocol.knn.KnnRecallResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnRecallTarget;
import io.github.mfuerstenau.leia.protocol.knn.StartKnnRecallParams;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.runtime.Knn;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Search;
import io.github.mfuerstenau.leia.server.RpcException;
import io.github.mfuerstenau.leia.server.job.Jobs;
import io.github.mfuerstenau.leia.server.job.ScanProgress;
import io.github.mfuerstenau.leia.server.search.KnnChecks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The recall check of approximate KNN search (ADR 0086): for each target, the approximate top k
 * against the exact top k, both through the runtime's {@link Search#search} as {@code startSearch}
 * runs them, so their scores compare. A job, since a sample runs two searches per target and the
 * exact one scores every vector.
 */
public final class RuntimeKnnRecall {

    /** No stored fields are read for the hits. */
    private static final Optional<List<String>> NO_FIELDS = Optional.of(List.of());

    private RuntimeKnnRecall() {}

    /** A target: a document's vector, or the query's own. */
    private record Target(OptionalInt docId, RuntimeIndex.Vector vector) {}

    /** One target's outcome. */
    private record Checked(KnnRecallTarget target, List<Double> drifts) {}

    /** {@code startKnnRecall}: checks the params at once, then the targets in a job. */
    public static JobStarted start(OpenRuntime open, StartKnnRecallParams params, Jobs jobs) throws RpcException {
        RuntimeIndex index = open.index();
        Search search = index.search().orElseThrow(open::notSupported);
        Knn knn = index.knn().orElseThrow(open::notSupported);
        KnnQuery query = params.query();
        RuntimeIndex.VectorFormat format = RuntimeResults.vectorFormat(open, query.field());
        if (query.exact()) {
            throw invalid("a recall check compares an approximate query with the exact one: exact must be false");
        }
        String field = query.field();
        List<Target> targets;
        if (params.sample().isPresent()) {
            checkSampled(query, format, params.sample().getAsInt());
            targets = null;
        } else {
            KnnChecks.check(query, format.dimension());
            targets = List.of(new Target(query.docId(), RuntimeResults.knnTarget(open, knn, query, format)));
        }
        Optional<Search.ParsedQuery> filter = RuntimeResults.knnFilter(open, search, query);
        int k = query.k().getAsInt();
        String description = "recall check of field " + field + ", k " + k
                + params.sample().stream()
                        .mapToObj(n -> ", " + n + " sampled")
                        .findFirst()
                        .orElse("")
                + " with Lucene " + open.runtime().luceneVersion();
        long jobId = jobs.start(description, KnnRecallResult.class, progress -> {
            try {
                List<Target> checked = targets != null
                        ? targets
                        : sampled(knn, field, params.sample().getAsInt());
                return check(
                        search, knn, field, k, filter, checked, params.sample().isPresent(), progress);
            } catch (IOException e) {
                throw new RpcException(
                        ErrorCode.INDEX_NOT_READABLE,
                        "Cannot check the recall of field '" + field + "': " + e.getMessage(),
                        e);
            }
        });
        return new JobStarted(jobId);
    }

    /** Checks a recall check over a sample of {@code sample} documents, which are its targets. */
    private static void checkSampled(KnnQuery query, RuntimeIndex.VectorFormat format, int sample) throws RpcException {
        if (format.dimension() == 0) {
            throw invalid("the field '" + query.field() + "' has no KNN vectors");
        }
        if (query.docId().isPresent() || query.vector().isPresent()) {
            throw invalid("a sampled recall check takes its targets from the sample: the query has no target");
        }
        if (query.k().isEmpty() || query.k().getAsInt() < 1 || query.k().getAsInt() > KnnQuery.MAX_K - 1) {
            throw invalid("a sampled recall check needs k from 1 to " + (KnnQuery.MAX_K - 1)
                    + (query.k().isPresent() ? ": " + query.k().getAsInt() : ""));
        }
        if (sample < 1 || sample > StartKnnRecallParams.MAX_SAMPLE) {
            throw invalid("sample must be between 1 and " + StartKnnRecallParams.MAX_SAMPLE + ", not " + sample);
        }
    }

    /** The sampled documents' vectors, as targets. */
    private static List<Target> sampled(Knn knn, String field, int n) throws IOException {
        List<Target> targets = new ArrayList<>();
        for (int docId : knn.sample(field, n)) {
            knn.vector(docId, field).ifPresent(vector -> targets.add(new Target(OptionalInt.of(docId), vector)));
        }
        return targets;
    }

    // Separate options of the call (ADR 0088)
    @SuppressWarnings("java:S107")
    private static KnnRecallResult check(
            Search search,
            Knn knn,
            String field,
            int k,
            Optional<Search.ParsedQuery> filter,
            List<Target> targets,
            boolean leaveOutTarget,
            ScanProgress progress)
            throws IOException {
        List<Checked> checked = new ArrayList<>();
        for (Target target : targets) {
            long done = checked.size();
            // A search's own progress only gives the job a chance to stop.
            checked.add(
                    checkTarget(search, knn, field, k, filter, target, leaveOutTarget, docs -> progress.scanned(done)));
            progress.scanned(checked.size());
        }
        List<Double> drifts = checked.stream().flatMap(c -> c.drifts().stream()).toList();
        List<KnnRecallTarget> worst = checked.stream()
                .map(Checked::target)
                .sorted(Comparator.comparingDouble(KnnRecallTarget::recall)
                        .thenComparingInt(target -> target.docId().orElse(-1)))
                .limit(KnnRecallResult.MAX_WORST)
                .toList();
        return new KnnRecallResult(
                field,
                k,
                checked.size(),
                checked.stream().mapToDouble(c -> c.target().recall()).average().orElse(1),
                checked.stream().mapToDouble(c -> c.target().recall()).min().orElse(1),
                (int) checked.stream()
                        .filter(c -> c.target().missed().isEmpty())
                        .count(),
                drifts.stream().mapToDouble(Double::doubleValue).average(),
                drifts.isEmpty()
                        ? OptionalDouble.empty()
                        : drifts.stream().mapToDouble(Double::doubleValue).max(),
                worst);
    }

    /**
     * The approximate and the exact top k of one target. A sampled document is left out of both:
     * each search asks for one more, and the first k others count.
     */
    // Separate options of the call (ADR 0088)
    @SuppressWarnings("java:S107")
    private static Checked checkTarget(
            Search search,
            Knn knn,
            String field,
            int k,
            Optional<Search.ParsedQuery> filter,
            Target target,
            boolean leaveOutTarget,
            java.util.function.LongConsumer progress)
            throws IOException {
        int asked = leaveOutTarget ? k + 1 : k;
        OptionalInt self = leaveOutTarget ? target.docId() : OptionalInt.empty();
        List<Search.Hit> approximate = top(
                search.search(
                                knn.approximate(field, target.vector(), asked, filter),
                                0,
                                asked,
                                false,
                                NO_FIELDS,
                                progress)
                        .hits(),
                self,
                k);
        List<Search.Hit> exact = top(
                search.search(knn.exact(field, target.vector(), filter), 0, asked, false, NO_FIELDS, progress)
                        .hits(),
                self,
                k);
        Map<Integer, Float> found = new HashMap<>();
        approximate.forEach(hit -> found.put(hit.docId(), hit.score()));
        List<KnnRecallMiss> missed = new ArrayList<>();
        List<Double> drifts = new ArrayList<>();
        for (int rank = 0; rank < exact.size(); rank++) {
            Search.Hit hit = exact.get(rank);
            Float score = found.get(hit.docId());
            if (score == null) {
                missed.add(new KnnRecallMiss(rank + 1, hit.docId(), hit.score()));
            } else {
                drifts.add((double) Math.abs(score - hit.score()));
            }
        }
        double recall = exact.isEmpty() ? 1 : (double) (exact.size() - missed.size()) / exact.size();
        return new Checked(new KnnRecallTarget(target.docId(), recall, List.copyOf(missed)), drifts);
    }

    /** The first {@code k} of {@code hits}, without the document {@code self}. */
    private static List<Search.Hit> top(List<Search.Hit> hits, OptionalInt self, int k) {
        return hits.stream()
                .filter(hit -> self.isEmpty() || hit.docId() != self.getAsInt())
                .limit(k)
                .toList();
    }

    private static RpcException invalid(String message) {
        return new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: " + message);
    }
}
