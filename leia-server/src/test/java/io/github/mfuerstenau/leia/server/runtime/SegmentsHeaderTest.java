package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The version that {@link SegmentsHeader} reads from real indexes and from headers written here byte
 * for byte, as Lucene writes them.
 */
class SegmentsHeaderTest {

    private static final int CODEC_MAGIC = 0x3fd76c17;

    @TempDir
    Path tmp;

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    @Test
    void readsRealIndexes() throws IOException {
        assertThat(SegmentsHeader.read(testIndex("lucene24"))).contains(new IndexVersion(2, "2.x"));
        assertThat(SegmentsHeader.read(testIndex("lucene6"))).contains(new IndexVersion(6, "6.6.6"));
        assertThat(SegmentsHeader.read(testIndex("lucene7"))).contains(new IndexVersion(7, "7.7.3"));
        assertThat(SegmentsHeader.read(testIndex("lucene8"))).contains(new IndexVersion(8, "8.11.4"));
        // Created by 8 and written by 9, as after an upgrade: still an index of 8 (ADR 0049).
        assertThat(SegmentsHeader.read(testIndex("lucene8to9")))
                .contains(new IndexVersion(8, "9.12.3, created with 8"));
        assertThat(SegmentsHeader.read(testIndex("lucene9"))).hasValueSatisfying(version -> {
            assertThat(version.major()).isEqualTo(9);
            assertThat(version.writtenBy()).startsWith("9.");
        });

        try (FSDirectory directory = FSDirectory.open(tmp);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new StringField("id", "1", Field.Store.YES));
            writer.addDocument(doc);
            writer.commit();
        }
        assertThat(SegmentsHeader.read(tmp))
                .contains(new IndexVersion(Version.LATEST.major, Version.LATEST.toString()));
    }

    @Test
    void readsTheFormatsBeforeCodecs() throws IOException {
        assertThat(read(header(-2))).contains(new IndexVersion(2, "2.x"));
        assertThat(read(header(-8))).contains(new IndexVersion(2, "2.x"));
        assertThat(read(header(-9))).contains(new IndexVersion(2, "2.9 or 3.0"));
        assertThat(read(header(-10))).contains(new IndexVersion(3, "3.x"));
        assertThat(read(header(-11))).contains(new IndexVersion(3, "3.x"));
    }

    @Test
    void readsLucene4And5() throws IOException {
        assertThat(read(codecHeader(0))).contains(new IndexVersion(4, "4.x"));
        assertThat(read(codecHeader(3))).contains(new IndexVersion(4, "4.x"));
        assertThat(read(withId(codecHeader(4)))).contains(new IndexVersion(5, "5.0 – 5.2"));
        assertThat(read(withId(codecHeader(5)))).contains(new IndexVersion(5, "5.0 – 5.2"));
    }

    @Test
    void readsTheOldestSegmentOfLucene53To6() throws IOException {
        assertThat(read(format6(new int[] {6, 6, 6}, null))).contains(new IndexVersion(6, "6.6.6"));
        assertThat(read(format6(new int[] {5, 5, 0}, new int[] {5, 3, 1}))).contains(new IndexVersion(5, "5.5.0"));
        assertThat(read(format6(new int[] {6, 6, 6}, new int[] {6, 0, 0}))).contains(new IndexVersion(6, "6.6.6"));
        assertThat(read(format6(new int[] {6, 6, 6}, new int[] {5, 5, 4})))
                .contains(new IndexVersion(5, "6.6.6, with a segment from 5.5.4"));
    }

    @Test
    void readsTheCreatedMajorFrom7On() throws IOException {
        assertThat(read(format7(7, new int[] {7, 7, 3}, 7))).contains(new IndexVersion(7, "7.7.3"));
        assertThat(read(format7(10, new int[] {9, 12, 3}, 8))).contains(new IndexVersion(8, "9.12.3, created with 8"));
        // A version of 128 or more takes a vint of two bytes.
        assertThat(read(format7(10, new int[] {10, 200, 1}, 10))).contains(new IndexVersion(10, "10.200.1"));
    }

    @Test
    void rejectsWhatItCannotRead() throws IOException {
        assertThat(SegmentsHeader.read(tmp)).isEmpty();
        assertThat(SegmentsHeader.read(tmp.resolve("missing"))).isEmpty();
        assertThat(read(header(CODEC_MAGIC))).isEmpty();
        assertThat(read(new byte[] {0x3f, (byte) 0xd7})).isEmpty();
        assertThat(read(codecHeader("postings", 0))).isEmpty();
        assertThat(read(withId(codecHeader(11)))).isEmpty();
        assertThat(read(header(-1))).isEmpty();
        assertThat(read(header(-12))).isEmpty();
        // Cut short after the id.
        assertThat(read(Arrays.copyOf(format7(10, new int[] {10, 3, 0}, 10), 36)))
                .isEmpty();
    }

    @Test
    void readsTheNewestSegmentsFile() throws IOException {
        Files.write(tmp.resolve("segments_9"), header(-2));
        Files.write(tmp.resolve("segments_a"), header(-11));
        Files.write(tmp.resolve("segments.gen"), header(-9));
        assertThat(SegmentsHeader.read(tmp)).contains(new IndexVersion(3, "3.x"));
    }

    private Optional<IndexVersion> read(byte[] segments) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "index");
        Files.write(dir.resolve("segments_1"), segments);
        return SegmentsHeader.read(dir);
    }

    private static byte[] header(int first) throws IOException {
        return bytes(out -> out.writeInt(first));
    }

    private static byte[] codecHeader(int format) throws IOException {
        return codecHeader("segments", format);
    }

    private static byte[] codecHeader(String codec, int format) throws IOException {
        return bytes(out -> {
            out.writeInt(CODEC_MAGIC);
            writeString(out, codec);
            out.writeInt(format);
        });
    }

    /** A header with the id and the suffix of the generation that follow it from 5.0 on. */
    private static byte[] withId(byte[] header) throws IOException {
        return bytes(out -> {
            out.write(header);
            out.write(new byte[16]);
            writeString(out, "1");
        });
    }

    private static byte[] format6(int[] writer, int[] oldest) throws IOException {
        byte[] header = withId(codecHeader(6));
        return bytes(out -> {
            out.write(header);
            writeVersion(out, writer);
            out.writeLong(42);
            out.writeInt(7);
            out.writeInt(oldest == null ? 0 : 3);
            if (oldest != null) {
                writeVersion(out, oldest);
            }
        });
    }

    private static byte[] format7(int format, int[] writer, int created) throws IOException {
        byte[] header = withId(codecHeader(format));
        return bytes(out -> {
            out.write(header);
            writeVersion(out, writer);
            writeVInt(out, created);
            out.writeLong(42);
        });
    }

    private static void writeVersion(DataOutputStream out, int[] version) throws IOException {
        for (int part : version) {
            writeVInt(out, part);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeVInt(out, utf8.length);
        out.write(utf8);
    }

    private static void writeVInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            out.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private interface Writes {
        void to(DataOutputStream out) throws IOException;
    }

    private static byte[] bytes(Writes writes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writes.to(out);
        }
        return bytes.toByteArray();
    }
}
