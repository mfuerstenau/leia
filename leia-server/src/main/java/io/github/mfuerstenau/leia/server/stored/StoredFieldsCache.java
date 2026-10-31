package io.github.mfuerstenau.leia.server.stored;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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

    private final Optional<DiskCache> disk;
    private final long maxMemoryBytes;
    private final LinkedHashMap<SegmentKey, SegmentFields> memory = new LinkedHashMap<>(16, 0.75f, true);
    private long memoryBytes;

    /**
     * A cache in memory and, with {@code cacheDir}, on disk below it.
     *
     * @param cacheDir leia's cache folder, such as {@code ~/.cache/leia}; empty for none
     */
    public StoredFieldsCache(Optional<Path> cacheDir) {
        this(cacheDir.map(dir -> new DiskCache(dir.resolve(FOLDER))), MAX_MEMORY_BYTES);
    }

    StoredFieldsCache(Optional<DiskCache> disk, long maxMemoryBytes) {
        this.disk = disk;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    /** The stored fields of the segment of {@code key}, from memory or else from disk. */
    Optional<SegmentFields> get(SegmentKey key) {
        synchronized (memory) {
            SegmentFields fields = memory.get(key);
            if (fields != null) {
                return Optional.of(fields);
            }
        }
        Optional<SegmentFields> read = disk.flatMap(cache -> cache.read(key));
        read.ifPresent(fields -> remember(key, fields));
        return read;
    }

    /** Keeps the stored fields of the segment of {@code key}, in memory and on disk. */
    void put(SegmentKey key, SegmentFields fields) {
        remember(key, fields);
        disk.ifPresent(cache -> cache.write(key, fields));
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
