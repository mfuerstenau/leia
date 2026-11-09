package io.github.mfuerstenau.leia.server.stored;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;
import org.roaringbitmap.RoaringBitmap;

/**
 * Which fields the documents of each segment store, for every segment read so far (ADR 0113): in
 * memory, the most recently used up to a limit, and in a {@link DiskCache} if there is a folder for
 * it. A segment's stored fields never change once it is written, so an entry stays right as long as
 * its segment exists; its deletions are never cached. Safe for the job threads to share.
 */
public final class StoredFieldsCache {

    /** The folder of the disk cache in the cache folder. */
    static final String FOLDER = "stored-fields";
    /** How much the cache may hold in memory. */
    static final long MAX_MEMORY_BYTES = 64L * 1024 * 1024;

    private final @Nullable DiskCache disk;
    private final long maxMemoryBytes;
    private final LinkedHashMap<SegmentKey, SegmentFields> memory = new LinkedHashMap<>(16, 0.75f, true);
    private long memoryBytes;

    /**
     * A cache in memory and, with {@code cacheDir}, on disk below it.
     *
     * @param cacheDir leia's cache folder, such as {@code ~/.cache/leia}; {@code null} for none
     */
    public StoredFieldsCache(@Nullable Path cacheDir) {
        this(cacheDir == null ? null : new DiskCache(cacheDir.resolve(FOLDER)), MAX_MEMORY_BYTES);
    }

    StoredFieldsCache(@Nullable DiskCache disk, long maxMemoryBytes) {
        this.disk = disk;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    /**
     * The documents of {@code segment} of the index in {@code directory} that store a value in one of
     * {@code fields} (in any field if {@code null}), deleted ones included, if the cache knows the segment
     * (ADR 0114): as a function from a document to the first such document at or after it, or the
     * segment's maxDoc for none, as {@link io.github.mfuerstenau.leia.runtime.Documents#storedValues(
     * int, Set, boolean, IntUnaryOperator, java.util.function.Consumer,
     * java.util.function.LongConsumer)} takes it. It never reads the segment; empty if the cache
     * doesn't know it.
     */
    public Optional<IntUnaryOperator> storing(
            Path directory, RuntimeIndex.Segment segment, @Nullable Set<String> fields) {
        return lookup(directory, segment).map(known -> {
            RoaringBitmap docs = known.docsWithAny(fields);
            int maxDoc = segment.maxDoc();
            return doc -> {
                long next = docs.nextValue(doc);
                return next < 0 || next >= maxDoc ? maxDoc : (int) next;
            };
        });
    }

    /**
     * The stored fields of {@code segment} of the index in {@code directory}, if the cache knows the
     * segment.
     */
    private Optional<SegmentFields> lookup(Path directory, RuntimeIndex.Segment segment) {
        return SegmentKey.of(directory, segment).flatMap(key -> get(key, segment.maxDoc()));
    }

    /** The stored fields of the segment of {@code key}, if it has {@code maxDoc} documents. */
    Optional<SegmentFields> get(SegmentKey key, int maxDoc) {
        return get(key).filter(fields -> fields.maxDoc() == maxDoc);
    }

    /** The stored fields of the segment of {@code key}, from memory or else from disk. */
    Optional<SegmentFields> get(SegmentKey key) {
        synchronized (memory) {
            SegmentFields fields = memory.get(key);
            if (fields != null) {
                return Optional.of(fields);
            }
        }
        Optional<SegmentFields> read = disk == null ? Optional.empty() : disk.read(key);
        read.ifPresent(fields -> remember(key, fields));
        return read;
    }

    /** Keeps the stored fields of the segment of {@code key}, in memory and on disk. */
    void put(SegmentKey key, SegmentFields fields) {
        remember(key, fields);
        if (disk != null) {
            disk.write(key, fields);
        }
    }

    private void remember(SegmentKey key, SegmentFields fields) {
        long size = fields.sizeInBytes();
        synchronized (memory) {
            SegmentFields previous = memory.put(key, fields);
            if (previous != null) {
                memoryBytes -= previous.sizeInBytes();
            }
            memoryBytes += size;
            Iterator<Map.Entry<SegmentKey, SegmentFields>> eldest =
                    memory.entrySet().iterator();
            while (memoryBytes > maxMemoryBytes && eldest.hasNext()) {
                Map.Entry<SegmentKey, SegmentFields> entry = eldest.next();
                if (entry.getKey().equals(key)) {
                    // The one just kept goes last, even if it alone is over the limit.
                    continue;
                }
                memoryBytes -= entry.getValue().sizeInBytes();
                eldest.remove();
            }
        }
    }

    /** How many segments it holds in memory. */
    int memorySegments() {
        synchronized (memory) {
            return memory.size();
        }
    }
}
