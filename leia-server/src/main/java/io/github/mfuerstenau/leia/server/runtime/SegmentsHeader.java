package io.github.mfuerstenau.leia.server.runtime;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * Finds the Lucene version of an index from the header of its newest {@code segments_N}, without
 * Lucene (ADR 0044 §3), for every version from 2.1, the first with {@code segments_N}, to 10.
 */
public final class SegmentsHeader {

    /** The oldest {@code segments_N} format: 2.1. */
    private static final int FORMAT_LOCKLESS = -2;
    /** The format of 2.9 and 3.0. */
    private static final int FORMAT_DIAGNOSTICS = -9;
    /** The newest format before codecs: 3.1 to 3.6. */
    private static final int FORMAT_3_1 = -11;

    /** {@code CodecUtil.CODEC_MAGIC}, the first int of every {@code segments_N} since 4.0. */
    private static final int CODEC_MAGIC = 0x3fd76c17;

    private static final String CODEC = "segments";
    /** {@code VERSION_50}: the first format with an id and a suffix after the header. */
    private static final int VERSION_50 = 4;
    /** {@code VERSION_53}: the first format with the writer's version. */
    private static final int VERSION_53 = 6;
    /** {@code VERSION_70}: the first format with the created major. */
    private static final int VERSION_70 = 7;
    /** {@code VERSION_86}: the newest format, of 8.6 to 10.x. */
    private static final int VERSION_86 = 10;

    /** The length of the id that follows the header since 5.0. */
    private static final int ID_LENGTH = 16;

    private SegmentsHeader() {}

    /**
     * The version of the index in {@code dir}, or empty if {@code dir} has no {@code segments_N} that
     * this reads: none at all, one that can't be read or is cut short, or one of an unknown format.
     */
    public static Optional<IndexVersion> read(Path dir) {
        Optional<Path> segments = newestSegmentsFile(dir);
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(segments.get());
                DataInputStream data = new DataInputStream(new BufferedInputStream(in))) {
            return read(data);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<IndexVersion> read(DataInputStream in) throws IOException {
        int first = in.readInt();
        if (first <= FORMAT_LOCKLESS && first >= FORMAT_3_1) {
            if (first > FORMAT_DIAGNOSTICS) {
                return Optional.of(new IndexVersion(2, "2.x"));
            }
            if (first == FORMAT_DIAGNOSTICS) {
                return Optional.of(new IndexVersion(2, "2.9 or 3.0"));
            }
            return Optional.of(new IndexVersion(3, "3.x"));
        }
        if (first != CODEC_MAGIC || !CODEC.equals(readString(in))) {
            return Optional.empty();
        }
        int format = in.readInt();
        if (format < 0 || format > VERSION_86) {
            return Optional.empty();
        }
        if (format < VERSION_50) {
            return Optional.of(new IndexVersion(4, "4.x"));
        }
        in.readFully(new byte[ID_LENGTH]);
        in.readFully(new byte[in.readUnsignedByte()]);
        if (format < VERSION_53) {
            return Optional.of(new IndexVersion(5, "5.0 – 5.2"));
        }
        Version writer = readVersion(in);
        if (writer.major() < 1) {
            return Optional.empty();
        }
        if (format >= VERSION_70) {
            int created = readVInt(in);
            if (created < 1 || created > writer.major()) {
                return Optional.empty();
            }
            return Optional.of(
                    created == writer.major()
                            ? new IndexVersion(created, writer.toString())
                            : new IndexVersion(created, writer + ", created with " + created));
        }
        in.readLong(); // version
        in.readInt(); // counter
        int numSegments = in.readInt();
        if (numSegments <= 0) {
            return Optional.of(new IndexVersion(writer.major(), writer.toString()));
        }
        Version oldest = readVersion(in);
        if (oldest.major() < 1 || oldest.major() > writer.major()) {
            return Optional.empty();
        }
        return Optional.of(
                oldest.major() == writer.major()
                        ? new IndexVersion(writer.major(), writer.toString())
                        : new IndexVersion(oldest.major(), writer + ", with a segment from " + oldest));
    }

    private record Version(int major, int minor, int bugfix) {
        @Override
        public String toString() {
            return major + "." + minor + "." + bugfix;
        }
    }

    private static Version readVersion(DataInputStream in) throws IOException {
        return new Version(readVInt(in), readVInt(in), readVInt(in));
    }

    /** Lucene's vint: 7 bits a byte, the low ones first, the high bit set on all but the last byte. */
    private static int readVInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("A vint longer than 5 bytes");
    }

    /** A vint length and that many bytes of UTF-8; a long one is no codec name. */
    private static String readString(DataInputStream in) throws IOException {
        int length = readVInt(in);
        if (length < 0 || length > CODEC.length()) {
            return "";
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Optional<Path> newestSegmentsFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> generation(file).isPresent())
                    .max(Comparator.comparingLong(file -> generation(file).orElseThrow()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static OptionalLong generation(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith("segments_")) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(name.substring("segments_".length()), Character.MAX_RADIX));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
