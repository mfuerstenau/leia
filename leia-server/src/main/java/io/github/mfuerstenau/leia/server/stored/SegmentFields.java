package io.github.mfuerstenau.leia.server.stored;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.roaringbitmap.RoaringBitmap;

/**
 * The stored fields of one segment (ADR 0113): for each field with a stored value in at least one of
 * its documents, deleted ones included, the documents that have one. Never changed once made.
 *
 * @param maxDoc the documents of the segment, deleted ones included
 * @param fields the documents by field, segment-local
 */
record SegmentFields(int maxDoc, Map<String, RoaringBitmap> fields) {

    SegmentFields {
        fields = Map.copyOf(fields);
    }

    /**
     * The documents with a stored value in one of {@code names}, or in any field if empty (ADR 0114);
     * a new bitmap.
     */
    RoaringBitmap docsWithAny(Optional<Set<String>> names) {
        if (names.isEmpty()) {
            return RoaringBitmap.or(fields.values().iterator());
        }
        return RoaringBitmap.or(
                names.get().stream().map(fields::get).filter(Objects::nonNull).iterator());
    }

    /** About how many bytes it takes in memory. */
    long sizeInBytes() {
        long size = 64;
        for (Map.Entry<String, RoaringBitmap> field : fields.entrySet()) {
            size += 64 + 2L * field.getKey().length() + field.getValue().getLongSizeInBytes();
        }
        return size;
    }
}
