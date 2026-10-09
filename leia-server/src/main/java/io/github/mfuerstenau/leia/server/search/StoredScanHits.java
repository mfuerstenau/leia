package io.github.mfuerstenau.leia.server.search;

import io.github.mfuerstenau.leia.core.StoredScan;
import io.github.mfuerstenau.leia.protocol.search.MatchSpan;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import java.util.ArrayList;
import java.util.List;

/**
 * The hits of a scan of the stored values (ADR 0027): the job adds them from its thread, polls read
 * them from the request loop.
 */
final class StoredScanHits implements StoredScan.Listener {

    private final List<StoredScanHit> hits = new ArrayList<>();
    private volatile long matchedValues;

    @Override
    public synchronized void hit(StoredScan.Hit hit) {
        hits.add(new StoredScanHit(
                hit.docId(),
                hit.deleted(),
                hit.field(),
                hit.valueIndex(),
                hit.length(),
                hit.matchCount(),
                hit.spans().stream()
                        .map(span -> new MatchSpan(span.start(), span.end()))
                        .toList(),
                new Snippet(hit.snippetStart(), hit.snippet())));
    }

    @Override
    public void matched(long matchedValues) {
        this.matchedValues = matchedValues;
    }

    /**
     * The hits from {@code from} on, how many there are, and how many values matched.
     *
     * @param from the first hit; past the end gives none
     */
    synchronized Snapshot from(int from) {
        List<StoredScanHit> tail = from < hits.size() ? List.copyOf(hits.subList(from, hits.size())) : List.of();
        return new Snapshot(hits.size(), tail, matchedValues);
    }

    record Snapshot(int hitCount, List<StoredScanHit> hits, long matchedValues) {}
}
