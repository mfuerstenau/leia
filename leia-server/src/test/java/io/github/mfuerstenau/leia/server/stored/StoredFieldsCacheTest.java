package io.github.mfuerstenau.leia.server.stored;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

class StoredFieldsCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @TempDir
    Path tmp;

    private static SegmentFields fields(int maxDoc, int... idDocs) {
        RoaringBitmap all = new RoaringBitmap();
        all.add(0L, maxDoc);
        return new SegmentFields(maxDoc, Map.of("_source", all, "_id", RoaringBitmap.bitmapOf(idDocs)));
    }

    private DiskCache disk(long maxBytes) {
        return new DiskCache(
                tmp.resolve("stored-fields"), maxBytes, Duration.ofDays(30), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aSegmentWithAnIdIsKnownByIt() {
        RuntimeIndex.Segment segment = new RuntimeIndex.Segment("_0", 0, 7, 6, Optional.of("00ff"), List.of("_0.cfs"));
        assertThat(SegmentKey.of(tmp.resolve("nowhere"), segment)).contains(new SegmentKey("id 00ff 7"));
    }

    @Test
    void aSegmentWithoutAnIdIsKnownByItsFiles() throws IOException {
        Files.writeString(tmp.resolve("_0.fdt"), "stored");
        Files.writeString(tmp.resolve("_0.fdx"), "index");
        RuntimeIndex.Segment segment =
                new RuntimeIndex.Segment("_0", 0, 7, 6, Optional.empty(), List.of("_0.fdt", "_0.fdx"));

        Optional<SegmentKey> key = SegmentKey.of(tmp, segment);
        assertThat(key).isPresent();
        assertThat(key.get().value()).startsWith("files " + tmp.toRealPath() + " _0 7 _0.fdt 6 ");

        Files.writeString(tmp.resolve("_0.fdt"), "stored again");
        assertThat(SegmentKey.of(tmp, segment)).isPresent().isNotEqualTo(key);
        // A file that is gone, and a segment that doesn't say, have none.
        Files.delete(tmp.resolve("_0.fdx"));
        assertThat(SegmentKey.of(tmp, segment)).isEmpty();
        assertThat(SegmentKey.of(tmp, new RuntimeIndex.Segment("_0", 0, 7, 6))).isEmpty();
    }

    @Test
    void namesTheDocumentsThatStoreOneOfTheFields() {
        StoredFieldsCache cache = new StoredFieldsCache(Optional.empty());
        RuntimeIndex.Segment segment = new RuntimeIndex.Segment("_0", 0, 10, 10, Optional.of("00ff"), List.of());
        Path dir = tmp.resolve("nowhere");
        assertThat(cache.storing(dir, segment, Optional.empty())).isEmpty();

        // _source in all ten, _id in 2 and 7.
        cache.put(SegmentKey.of(dir, segment).orElseThrow(), fields(10, 2, 7));

        IntUnaryOperator id =
                cache.storing(dir, segment, Optional.of(Set.of("_id"))).orElseThrow();
        assertThat(IntStream.of(0, 2, 3, 7, 8, 10).map(id)).containsExactly(2, 2, 7, 7, 10, 10);
        IntUnaryOperator none =
                cache.storing(dir, segment, Optional.of(Set.of("other"))).orElseThrow();
        assertThat(none.applyAsInt(0)).isEqualTo(10);
        IntUnaryOperator any = cache.storing(dir, segment, Optional.empty()).orElseThrow();
        assertThat(IntStream.of(0, 5, 9, 10).map(any)).containsExactly(0, 5, 9, 10);
        IntUnaryOperator either = cache.storing(dir, segment, Optional.of(Set.of("_id", "_source")))
                .orElseThrow();
        assertThat(either.applyAsInt(3)).isEqualTo(3);
        // A segment with another number of documents isn't the one cached.
        assertThat(cache.storing(
                        dir,
                        new RuntimeIndex.Segment("_0", 0, 11, 11, Optional.of("00ff"), List.of()),
                        Optional.empty()))
                .isEmpty();
    }

    @Test
    void keepsTheMostRecentlyUsedInMemory() {
        SegmentFields one = fields(10, 1);
        long size = one.sizeInBytes();
        StoredFieldsCache cache = new StoredFieldsCache(Optional.empty(), 2 * size + size / 2);
        SegmentKey a = new SegmentKey("a");
        SegmentKey b = new SegmentKey("b");
        SegmentKey c = new SegmentKey("c");
        cache.put(a, one);
        cache.put(b, fields(10, 2));
        assertThat(cache.get(a)).contains(one);
        cache.put(c, fields(10, 3));

        assertThat(cache.memorySegments()).isEqualTo(2);
        assertThat(cache.get(a)).contains(one);
        assertThat(cache.get(b)).isEmpty();
        assertThat(cache.get(c)).isPresent();
    }

    @Test
    void readsWhatItWroteToDisk() {
        SegmentKey key = new SegmentKey("id 00ff 100");
        SegmentFields written = fields(100, 3, 50, 99);
        new StoredFieldsCache(Optional.of(disk(Long.MAX_VALUE)), Long.MAX_VALUE).put(key, written);

        StoredFieldsCache fresh = new StoredFieldsCache(Optional.of(disk(Long.MAX_VALUE)), Long.MAX_VALUE);
        assertThat(fresh.get(key)).contains(written);
        assertThat(fresh.memorySegments()).isEqualTo(1);
        assertThat(fresh.get(new SegmentKey("id 0100 100"))).isEmpty();
    }

    @Test
    void deletesABrokenFile() throws IOException {
        SegmentKey key = new SegmentKey("id 00ff 100");
        DiskCache disk = disk(Long.MAX_VALUE);
        disk.write(key, fields(100, 1));
        Path file = tmp.resolve("stored-fields").resolve(key.fileName());
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 1;
        Files.write(file, bytes);

        assertThat(disk.read(key)).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void ignoresTheFileOfAnotherKey() throws IOException {
        SegmentKey key = new SegmentKey("id 00ff 100");
        DiskCache disk = disk(Long.MAX_VALUE);
        Path file = tmp.resolve("stored-fields").resolve(key.fileName());
        Files.createDirectories(file.getParent());
        Files.write(file, DiskCache.encode(new SegmentKey("another"), fields(100, 1)));

        assertThat(disk.read(key)).isEmpty();
        assertThat(file).exists();
    }

    @Test
    void prunesTheOldAndTheLeastRecentlyUsed() throws IOException {
        DiskCache writer = disk(Long.MAX_VALUE);
        SegmentKey old = new SegmentKey("old");
        SegmentKey a = new SegmentKey("a");
        SegmentKey b = new SegmentKey("b");
        writer.write(old, fields(100, 1));
        writer.write(a, fields(100, 1));
        writer.write(b, fields(100, 1));
        Path dir = tmp.resolve("stored-fields");
        Files.setLastModifiedTime(dir.resolve(old.fileName()), FileTime.from(NOW.minus(Duration.ofDays(31))));
        Files.setLastModifiedTime(dir.resolve(a.fileName()), FileTime.from(NOW.minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(dir.resolve(b.fileName()), FileTime.from(NOW.minus(Duration.ofDays(1))));
        long size = Files.size(dir.resolve(b.fileName()));

        DiskCache pruning = disk(size + size / 2);
        assertThat(pruning.read(b)).isPresent();

        assertThat(dir.resolve(old.fileName())).doesNotExist();
        assertThat(dir.resolve(a.fileName())).doesNotExist();
        assertThat(dir.resolve(b.fileName())).exists();
    }
}
