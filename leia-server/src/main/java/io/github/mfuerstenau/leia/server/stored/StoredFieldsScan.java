package io.github.mfuerstenau.leia.server.stored;

import io.github.mfuerstenau.leia.protocol.document.StoredField;
import io.github.mfuerstenau.leia.protocol.document.StoredFields;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.server.job.ScanProgress;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
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
        Segments segments = forEachSegment(
                index,
                documents,
                cache,
                ignoreCache,
                progress,
                (docBase, fields, deletedDocs) -> count(fields, deletedDocs, counts));
        List<StoredField> stored = new ArrayList<>();
        counts.forEach((name, count) -> stored.add(new StoredField(name, (int) count[0], (int) count[1])));
        return new StoredFields(
                stored, segments.segments(), segments.cached(), (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * The documents of {@code index} with a stored value in {@code field}, by their ids in the index
     * (ADR 0114): the live ones, and the deleted ones too if {@code includeDeleted}. The segments the
     * cache doesn't know are read and cached, as {@link #scan} reads them.
     *
     * @param progress gets the documents gone through, those of a cached segment at once
     * @throws java.util.concurrent.CancellationException from {@code progress}, to stop the scan
     * @throws IOException if reading a segment fails
     */
    public static BitSet docsWith(
            RuntimeIndex index,
            Documents documents,
            StoredFieldsCache cache,
            String field,
            boolean includeDeleted,
            ScanProgress progress)
            throws IOException {
        BitSet docs = new BitSet(index.maxDoc());
        forEachSegment(index, documents, cache, false, progress, (docBase, fields, deletedDocs) -> {
            RoaringBitmap stored = fields.fields().get(field);
            if (stored == null) {
                return;
            }
            stored.forEach((int doc) -> docs.set(docBase + doc));
            if (!includeDeleted) {
                for (int doc : deletedDocs) {
                    docs.clear(docBase + doc);
                }
            }
        });
        return docs;
    }

    /** What a segment stores, given to {@link #forEachSegment} in the order of the segments. */
    @FunctionalInterface
    private interface SegmentVisitor {

        /**
         * @param docBase the id in the index of the segment's first document
         * @param deletedDocs the segment's deleted documents, segment-local and ascending
         */
        void segment(int docBase, SegmentFields fields, int[] deletedDocs);
    }

    /** How many segments {@link #forEachSegment} went through, and how many the cache knew. */
    private record Segments(int segments, int cached) {}

    /**
     * Gives the stored fields of every segment of {@code index} to {@code visitor}, segment by
     * segment: from the {@link StoredFieldsCache}, or else read and then cached.
     */
    private static Segments forEachSegment(
            RuntimeIndex index,
            Documents documents,
            StoredFieldsCache cache,
            boolean ignoreCache,
            ScanProgress progress,
            SegmentVisitor visitor)
            throws IOException {
        int segment = 0;
        int cached = 0;
        long scanned = 0;
        progress.scanned(0);
        for (RuntimeIndex.IndexStats directory : index.stats().indexes()) {
            for (RuntimeIndex.Segment info : directory.segments()) {
                Optional<SegmentKey> key = SegmentKey.of(directory.path(), info);
                Optional<SegmentFields> known =
                        ignoreCache ? Optional.empty() : key.flatMap(k -> cache.get(k, info.maxDoc()));
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
                visitor.segment(directory.docBase() + info.docBase(), fields, documents.deletedDocs(segment));
                scanned += info.maxDoc();
                progress.scanned(scanned);
                segment++;
            }
        }
        return new Segments(segment, cached);
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
