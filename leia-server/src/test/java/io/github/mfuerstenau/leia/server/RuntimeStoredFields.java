package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.StoredField;
import io.github.mfuerstenau.leia.protocol.document.StoredFields;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The stored fields scan on an index of the runtimes' writers (ADR 0113): every document stores
 * {@code id}; those of the writer's loop {@code body} and {@code price}, from Lucene 5 on {@code blob}
 * too, never {@code tag}; and id 2 is deleted.
 */
final class RuntimeStoredFields {

    private RuntimeStoredFields() {}

    /**
     * Finds the stored fields with their live and all documents, takes every segment from the cache
     * the second time, and reads them all again with {@code ignoreCache}.
     */
    static void assertFindsTheStoredFields(RealBackend backend, OpenIndexResult opened, boolean hasBlob)
            throws Exception {
        int maxDoc = opened.maxDoc().orElseThrow();
        int numDocs = opened.numDocs().orElseThrow();
        List<String> stored = hasBlob ? List.of("blob", "body", "id", "price") : List.of("body", "id", "price");

        StoredFields first = backend.storedFields(false);
        assertThat(first.fields())
                .extracting(StoredField::name)
                .containsAll(stored)
                .doesNotContain("tag");
        assertThat(first.fields())
                .filteredOn(field -> field.name().equals("id"))
                .singleElement()
                .satisfies(id -> {
                    assertThat(id.allDocs()).isEqualTo(maxDoc);
                    assertThat(id.liveDocs()).isEqualTo(numDocs);
                });
        // The others are in the documents of the writer's loop, deleted ones too; lucene8to9's added
        // documents have only an id and a vector.
        assertThat(first.fields())
                .filteredOn(field -> stored.contains(field.name()))
                .allSatisfy(
                        field -> assertThat(field.allDocs()).as(field.name()).isGreaterThan(field.liveDocs()));
        assertThat(numDocs).isLessThan(maxDoc);
        assertThat(first.segments()).isPositive();
        assertThat(first.cachedSegments()).isZero();

        StoredFields second = backend.storedFields(false);
        assertThat(second.fields()).isEqualTo(first.fields());
        assertThat(second.cachedSegments()).isEqualTo(second.segments());

        StoredFields ignored = backend.storedFields(true);
        assertThat(ignored.fields()).isEqualTo(first.fields());
        assertThat(ignored.cachedSegments()).isZero();
    }

    /**
     * Scans the index at {@code path} with a cache on disk in {@code cacheDir}, then again in a
     * backend started afresh, which takes every segment from the disk.
     */
    static void assertKeepsTheCacheOnDisk(Path path, Path cacheDir) throws Exception {
        StoredFields first;
        try (RealBackend backend = RealBackend.startWithCache(cacheDir)) {
            backend.call(Methods.OPEN_INDEX, new OpenIndexParams(path.toString()));
            first = backend.storedFields(false);
            assertThat(first.cachedSegments()).isZero();
        }
        assertThat(cacheDir.resolve("stored-fields")).isNotEmptyDirectory();
        try (RealBackend backend = RealBackend.startWithCache(cacheDir)) {
            backend.call(Methods.OPEN_INDEX, new OpenIndexParams(path.toString()));
            StoredFields second = backend.storedFields(false);
            assertThat(second.fields()).isEqualTo(first.fields());
            assertThat(second.cachedSegments()).isEqualTo(second.segments());
        }
        try (RealBackend backend = RealBackend.start(RealBackend.testFile("leia.test.runtimes"), List.of())) {
            backend.call(Methods.OPEN_INDEX, new OpenIndexParams(path.toString()));
            assertThat(backend.storedFields(false).cachedSegments()).isZero();
        }
    }

    /**
     * Scans the stored values of {@code fields} of the index at {@code path} in a backend started
     * afresh, before and after the stored fields scan fills its cache, which then passes over the
     * documents that store none of them (ADR 0114): the same hits either way. Then filters by each
     * stored field, which matches its live documents, and its documents with the deleted ones.
     */
    static void assertScansAndFiltersThroughTheCache(Path path, List<String> fields) throws Exception {
        try (RealBackend backend = RealBackend.start()) {
            backend.call(Methods.OPEN_INDEX, new OpenIndexParams(path.toString()));
            StartStoredScanParams scan = new StartStoredScanParams(".", false, Optional.of(fields), true);
            RealBackend.StoredScan before = backend.storedScan(scan);

            StoredFields stored = backend.storedFields(false);
            RealBackend.StoredScan after = backend.storedScan(scan);

            assertThat(before.hits()).isNotEmpty();
            assertThat(after.hits()).isEqualTo(before.hits());
            assertThat(withoutTook(after.result())).isEqualTo(withoutTook(before.result()));
            for (StoredField field : stored.fields()) {
                FilterResult live = backend.filter(
                        new StartFilterParams(Optional.empty(), Optional.empty(), Optional.of(field.name()), false));
                assertThat(live.query()).isEqualTo("stored:" + field.name());
                assertThat(live.matchCount()).as(field.name()).isEqualTo(field.liveDocs());
                FilterResult all = backend.filter(
                        new StartFilterParams(Optional.empty(), Optional.empty(), Optional.of(field.name()), true));
                assertThat(all.matchCount()).as(field.name()).isEqualTo(field.allDocs());
            }
        }
    }

    private static StoredScanResult withoutTook(StoredScanResult result) {
        return new StoredScanResult(
                result.scannedDocs(),
                result.matchedDocs(),
                result.matchedValues(),
                result.hitsTruncated(),
                result.timedOutValues(),
                result.timedOut(),
                0);
    }
}
