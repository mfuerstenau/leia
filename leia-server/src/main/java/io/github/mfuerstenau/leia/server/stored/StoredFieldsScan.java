package io.github.mfuerstenau.leia.server.stored;

import io.github.mfuerstenau.leia.protocol.document.StoredField;
import io.github.mfuerstenau.leia.protocol.document.StoredFields;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.job.ScanProgress;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongConsumer;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

/**
 * Finds the fields the documents of an index store values in (ADR 0113): segment by segment, from the
 * {@link StoredFieldsCache} or else by reading the segment's stored fields without their values,
 * which the cache then keeps. The deletions are the reader's, never cached, and only count.
 */
public final class StoredFieldsScan {

    private StoredFieldsScan() {}

    /**
     * Scans every segment of {@code index}.
     *
     * @param ignoreCache whether to read every segment, as if the cache knew none
     * @param progress gets the documents gone through, those of a cached segment at once
     * @throws java.util.concurrent.CancellationException from {@code progress}, to stop the scan; a
     *     segment it stops in is not cached
     * @throws IOException if reading a segment fails
     */
    public static StoredFields scan(
            RuntimeIndex index,
            Documents documents,
            StoredFieldsCache cache,
            boolean ignoreCache,
            ScanProgress progress)
            throws IOException {
        long start = System.nanoTime();
        Map<String, long[]> counts = new TreeMap<>();
        int segment = 0;
        int cached = 0;
        long scanned = 0;
        progress.scanned(0);
        for (RuntimeIndex.IndexStats directory : index.stats().indexes()) {
            for (RuntimeIndex.Segment info : directory.segments()) {
                Optional<SegmentKey> key = SegmentKey.of(directory.path(), info);
                Optional<SegmentFields> known = ignoreCache
                        ? Optional.empty()
                        : key.flatMap(cache::get).filter(fields -> fields.maxDoc() == info.maxDoc());
                SegmentFields fields;
                if (known.isPresent()) {
                    fields = known.get();
                    cached++;
                } else {
                    long base = scanned;
                    fields = read(documents, segment, info.maxDoc(), docs -> progress.scanned(base + docs));
                    // A segment without a key is read every time.
                    key.ifPresent(k -> cache.put(k, fields));
                }
                count(fields, documents.deletedDocs(segment), counts);
                scanned += info.maxDoc();
                progress.scanned(scanned);
                segment++;
            }
        }
        List<StoredField> stored = new ArrayList<>();
        counts.forEach((name, count) -> stored.add(new StoredField(name, (int) count[0], (int) count[1])));
        return new StoredFields(stored, segment, cached, (System.nanoTime() - start) / 1_000_000);
    }

    /** Reads which fields the documents of segment {@code segment} store values in. */
    static SegmentFields read(Documents documents, int segment, int maxDoc, LongConsumer progress) throws IOException {
        Map<String, Builder> builders = new HashMap<>();
        documents.storedFields(
                segment,
                (doc, field) ->
                        builders.computeIfAbsent(field, _ -> new Builder()).add(doc),
                progress);
        Map<String, RoaringBitmap> fields = new HashMap<>();
        builders.forEach((field, builder) -> fields.put(field, builder.build()));
        return new SegmentFields(maxDoc, fields);
    }

    /** Adds the live and all documents of each field of {@code fields} to {@code counts}. */
    private static void count(SegmentFields fields, int[] deletedDocs, Map<String, long[]> counts) {
        RoaringBitmap deleted = RoaringBitmap.bitmapOf(deletedDocs);
        fields.fields().forEach((name, docs) -> {
            long all = docs.getLongCardinality();
            long live = deleted.isEmpty() ? all : all - RoaringBitmap.andCardinality(docs, deleted);
            long[] count = counts.computeIfAbsent(name, _ -> new long[2]);
            count[0] += live;
            count[1] += all;
        });
    }

    /** The documents of one field, which come in ascending order, a document once per value. */
    private static final class Builder {

        private final RoaringBitmapWriter<RoaringBitmap> writer =
                RoaringBitmapWriter.writer().runCompress(true).get();
        private int last = -1;

        void add(int doc) {
            if (doc != last) {
                writer.add(doc);
                last = doc;
            }
        }

        RoaringBitmap build() {
            RoaringBitmap docs = writer.get();
            docs.runOptimize();
            return docs;
        }
    }
}
