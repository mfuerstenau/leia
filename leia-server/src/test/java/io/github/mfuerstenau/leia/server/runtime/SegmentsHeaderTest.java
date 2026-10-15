package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentsHeaderTest {

    private static Path testIndex(String name) {
        String dir = System.getProperty("leia.test.index." + name);
        if (dir == null) {
            throw new IllegalStateException("System property leia.test.index." + name + " is not set");
        }
        return Path.of(dir).toAbsolutePath();
    }

    @Test
    void tellsOnlyIndexesBeforeCodecs(@TempDir Path tmp) throws IOException {
        assertThat(SegmentsHeader.isPreCodec(testIndex("lucene24"))).isTrue();
        assertThat(SegmentsHeader.isPreCodec(testIndex("lucene9"))).isFalse();
        assertThat(SegmentsHeader.isPreCodec(tmp)).isFalse();
        assertThat(SegmentsHeader.isPreCodec(tmp.resolve("missing"))).isFalse();
        // The header of every segments_N since Lucene 4.0: CodecUtil's magic.
        Files.write(tmp.resolve("segments_1"), new byte[] {0x3f, (byte) 0xd7, 0x6c, 0x17});
        assertThat(SegmentsHeader.isPreCodec(tmp)).isFalse();
    }
}
