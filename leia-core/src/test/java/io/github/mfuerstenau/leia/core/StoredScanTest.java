package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The UTF-8 offsets that the highlights give positions in a value as (ADR 0027, ADR 0042). */
class StoredScanTest {

    @Test
    void utf8OffsetsCountSurrogatePairsOnce() {
        StoredScan.Utf8Offsets offsets = new StoredScan.Utf8Offsets("a😀b");

        assertThat(offsets.at(1)).isEqualTo(1);
        assertThat(offsets.at(2)).isEqualTo(5);
        // Between the halves of the pair: after it.
        assertThat(offsets.at(2)).isEqualTo(5);
        assertThat(offsets.at(4)).isEqualTo(6);
    }
}
