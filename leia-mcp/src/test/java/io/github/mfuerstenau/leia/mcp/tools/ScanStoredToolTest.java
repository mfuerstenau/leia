package io.github.mfuerstenau.leia.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.search.GetStoredScanResult;
import io.github.mfuerstenau.leia.protocol.search.Snippet;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScanStoredToolTest {

    @Test
    void endsAPageOnceOneHitMoreIsThere() {
        ScanStoredTool.Page page = new ScanStoredTool.Page(0, 5);

        assertThat(page.add(poll(0, 5))).isEmpty();
        assertThat(page.add(poll(5, 1))).isPresent();
        assertThat(page.hits).hasSize(5);
    }

    @Test
    void endsAPageThatEndsAtTheLastHitOnceItIsFull() {
        // No hit after MAX_HITS ever comes, though the scan goes on counting to the end of the index.
        int offset = StartStoredScanParams.MAX_HITS - 10;
        ScanStoredTool.Page page = new ScanStoredTool.Page(offset, 10);

        assertThat(page.add(poll(0, StartStoredScanParams.MAX_HITS - 1))).isEmpty();
        assertThat(page.add(poll(StartStoredScanParams.MAX_HITS - 1, 1))).isPresent();
        assertThat(page.hits).hasSize(10);
        assertThat(page.hits.getFirst().docId()).isEqualTo(offset);
    }

    /** A poll of a scan that goes on, with {@code count} hits from the document {@code from} on. */
    private static GetStoredScanResult poll(int from, int count) {
        List<StoredScanHit> hits = IntStream.range(from, from + count)
                .mapToObj(docId -> new StoredScanHit(docId, false, "f", 0, 1, 1, List.of(), new Snippet(0, "x")))
                .toList();
        return new GetStoredScanResult(from + count, from + count, from + count, hits, Optional.empty());
    }
}
