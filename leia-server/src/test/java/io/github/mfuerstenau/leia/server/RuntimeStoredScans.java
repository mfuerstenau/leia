package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The stored-values scan on an index of the runtimes' writers (ADR 0055): documents with an {@code
 * id} and a {@code body} "alpha … document <id> …", beta in the odd ones, and id 2 deleted. Hits are
 * checked by id, since the indexes order their documents differently.
 */
final class RuntimeStoredScans {

    private RuntimeStoredScans() {}

    /**
     * Scans {@code body} with and without the deleted document, all fields, an unknown field, and,
     * if {@code hasBlob}, the non-UTF-8 {@code blob} and the numeric {@code price}, which are skipped.
     */
    static void assertScansTheStoredValues(RealBackend backend, int maxDoc, boolean hasBlob) throws Exception {
        RealBackend.StoredScan idScan =
                backend.storedScan(new StartStoredScanParams("\\d+", false, Optional.of(List.of("id")), true));
        assertThat(idScan.result().scannedDocs()).isEqualTo(maxDoc);
        Map<Integer, String> ids = new HashMap<>();
        for (StoredScanHit hit : idScan.hits()) {
            ids.put(hit.docId(), hit.snippet().text());
        }

        RealBackend.StoredScan live = backend.storedScan(
                new StartStoredScanParams("document [12]\\b", false, Optional.of(List.of("body")), false));
        assertThat(live.hits())
                .extracting(hit -> ids.get(hit.docId()), StoredScanHit::deleted, StoredScanHit::field)
                .containsExactly(tuple("1", false, "body"));
        assertThat(live.hits().getFirst().spans())
                .singleElement()
                .satisfies(span -> assertThat(span.end() - span.start()).isEqualTo("document 1".length()));
        assertThat(live.result().scannedDocs()).isEqualTo(maxDoc);
        assertThat(live.result().matchedDocs()).isEqualTo(1);

        RealBackend.StoredScan withDeleted = backend.storedScan(
                new StartStoredScanParams("DOCUMENT [12]\\b", true, Optional.of(List.of("body")), true));
        assertThat(withDeleted.hits())
                .extracting(hit -> ids.get(hit.docId()), StoredScanHit::deleted)
                .containsExactly(tuple("1", false), tuple("2", true));

        // Every field, deleted documents too: only body has beta, in the odd ids.
        RealBackend.StoredScan all =
                backend.storedScan(new StartStoredScanParams("beta", false, Optional.empty(), true));
        assertThat(all.hits()).extracting(StoredScanHit::field).containsOnly("body");
        assertThat(all.hits()).extracting(StoredScanHit::valueIndex).containsOnly(0);
        assertThat(all.hits()).extracting(hit -> ids.get(hit.docId())).containsOnly("1", "3", "5", "7");

        if (hasBlob) {
            // Bytes that aren't UTF-8 and numbers are skipped.
            RealBackend.StoredScan skipped = backend.storedScan(
                    new StartStoredScanParams(".|\\d", false, Optional.of(List.of("blob", "price")), true));
            assertThat(skipped.hits()).isEmpty();
            assertThat(skipped.result().matchedValues()).isZero();
        }

        backend.fails(
                Methods.START_STORED_SCAN,
                new StartStoredScanParams("x", false, Optional.of(List.of("nothing")), false),
                ErrorCode.FIELD_NOT_FOUND);
    }
}
