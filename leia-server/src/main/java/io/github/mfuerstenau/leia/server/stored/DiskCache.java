package io.github.mfuerstenau.leia.server.stored;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.roaringbitmap.RoaringBitmap;

/**
 * The stored fields of segments in a folder of the user's, a file per segment (ADR 0113), never in an
 * index's folder. A file holds the key it is for, the segment's number of documents and each field's
 * bitmap in Roaring's portable format, with a CRC32 at the end. It is written to a temporary file and
 * moved into place, so two leia processes can share the folder. A file that doesn't check is deleted;
 * one that is read is touched, and once the folder holds more than its limit, the least recently
 * used go, as do those unused for longer than the maximal age. A failure is logged, never passed on:
 * without the cache, a segment is read again.
 */
final class DiskCache {

    private static final Logger LOG = Logger.getLogger(DiskCache.class.getName());

    /** {@code LSF} and the format's version, at the start of every file. */
    static final int MAGIC = 0x4C534601;
    /** How much the folder may hold. */
    static final long MAX_BYTES = 512L * 1024 * 1024;
    /** How long a file may go unused. */
    static final Duration MAX_AGE = Duration.ofDays(30);
    /** How old a temporary file must be before a prune takes it for one left over. */
    private static final Duration STALE_TEMP = Duration.ofDays(1);

    private static final String SUFFIX = ".bin";
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path dir;
    private final long maxBytes;
    private final Duration maxAge;
    private final Clock clock;
    private boolean pruned;

    DiskCache(Path dir) {
        this(dir, MAX_BYTES, MAX_AGE, Clock.systemUTC());
    }

    DiskCache(Path dir, long maxBytes, Duration maxAge, Clock clock) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** The stored fields of the segment of {@code key}; empty if the folder has none that check. */
    Optional<SegmentFields> read(SegmentKey key) {
        pruneOnce();
        Path file = dir.resolve(key.fileName());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException _) {
            return Optional.empty();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot read the stored fields cache file " + file);
            return Optional.empty();
        }
        Optional<SegmentFields> fields = decode(bytes, key);
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        touch(file);
        return fields;
    }

    /**
     * The stored fields in {@code bytes}, if they check and are those of {@code key}. A file that
     * doesn't check is deleted; one of another key, whose name clashes, is left to be replaced.
     */
    private Optional<SegmentFields> decode(byte[] bytes, SegmentKey key) {
        Path file = dir.resolve(key.fileName());
        try {
            if (bytes.length < Long.BYTES) {
                throw new IOException("too short");
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, 0, bytes.length - Long.BYTES);
            if (crc.getValue()
                    != ByteBuffer.wrap(bytes, bytes.length - Long.BYTES, Long.BYTES)
                            .getLong()) {
                throw new IOException("checksum mismatch");
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - Long.BYTES));
            if (in.readInt() != MAGIC) {
                throw new IOException("not a stored fields cache file of this version");
            }
            if (!readString(in).equals(key.value())) {
                return Optional.empty();
            }
            int maxDoc = in.readInt();
            int count = in.readInt();
            Map<String, RoaringBitmap> fields = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String name = readString(in);
                RoaringBitmap docs = new RoaringBitmap();
                docs.deserialize(in);
                fields.put(name, docs);
            }
            if (in.available() != 0) {
                throw new IOException("trailing bytes");
            }
            return Optional.of(new SegmentFields(maxDoc, fields));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "Deleting the broken stored fields cache file " + file);
            delete(file);
            return Optional.empty();
        }
    }

    /** Writes the stored fields of the segment of {@code key}, replacing a file there is. */
    void write(SegmentKey key, SegmentFields fields) {
        pruneOnce();
        Path file = dir.resolve(key.fileName());
        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, "segment", TEMP_SUFFIX);
            Files.write(temp, encode(key, fields));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot write the stored fields cache file " + file);
            if (temp != null) {
                delete(temp);
            }
            return;
        }
        prune(false);
    }

    static byte[] encode(SegmentKey key, SegmentFields fields) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        writeString(out, key.value());
        out.writeInt(fields.maxDoc());
        List<String> names = new ArrayList<>(fields.fields().keySet());
        names.sort(Comparator.naturalOrder());
        out.writeInt(names.size());
        for (String name : names) {
            writeString(out, name);
            Objects.requireNonNull(fields.fields().get(name)).serialize(out);
        }
        out.flush();
        CRC32 crc = new CRC32();
        crc.update(bytes.toByteArray());
        out.writeLong(crc.getValue());
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw new IOException("invalid string length " + length);
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private synchronized void pruneOnce() {
        if (!pruned) {
            pruned = true;
            prune(true);
        }
    }

    /**
     * Deletes the files unused for longer than the maximal age and temporary files left over, if
     * {@code byAge}, and the least recently used while the folder holds more than its limit.
     */
    synchronized void prune(boolean byAge) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant now = clock.instant();
        record Entry(Path path, long size, FileTime modified) {}
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                String name = path.getFileName().toString();
                try {
                    FileTime modified = Files.getLastModifiedTime(path);
                    boolean old = modified.toInstant().isBefore(now.minus(maxAge));
                    if (name.endsWith(TEMP_SUFFIX)) {
                        if (byAge && modified.toInstant().isBefore(now.minus(STALE_TEMP))) {
                            delete(path);
                        }
                    } else if (name.endsWith(SUFFIX)) {
                        if (byAge && old) {
                            delete(path);
                        } else {
                            entries.add(new Entry(path, Files.size(path), modified));
                        }
                    }
                } catch (IOException _) {
                    // Gone meanwhile, or another process's.
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Cannot list the stored fields cache " + dir);
            return;
        }
        long total = entries.stream().mapToLong(Entry::size).sum();
        if (total <= maxBytes) {
            return;
        }
        entries.sort(Comparator.comparing(Entry::modified));
        for (Entry entry : entries) {
            if (total <= maxBytes) {
                break;
            }
            delete(entry.path());
            total -= entry.size();
        }
    }

    private void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(clock.instant()));
        } catch (IOException _) {
            // Only the order in which the files go.
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.FINE, e, () -> "Cannot delete " + file);
        }
    }
}
