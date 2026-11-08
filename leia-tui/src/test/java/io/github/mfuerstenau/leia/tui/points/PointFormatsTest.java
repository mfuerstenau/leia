package io.github.mfuerstenau.leia.tui.points;

import static io.github.mfuerstenau.leia.client.values.PointFormat.DOUBLE;
import static io.github.mfuerstenau.leia.client.values.PointFormat.INT;
import static io.github.mfuerstenau.leia.client.values.PointFormat.LONG;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The format each point field is shown in, which {@code t} cycles (ADR 0041). */
class PointFormatsTest {

    @Test
    void cyclesAFieldsFormatAndForgetsOneThatNoLongerFits() {
        PointFormats formats = new PointFormats();
        IndexField price = field("price", 8);

        assertThat(formats.of(price)).isEqualTo(LONG);
        formats.cycle(price);
        assertThat(formats.of(price)).isEqualTo(DOUBLE);
        formats.cycle(price);
        formats.cycle(price);
        assertThat(formats.of(price)).isEqualTo(LONG);

        formats.cycle(price);
        // Reopened with 4-byte points: a double doesn't fit, so the guess comes back.
        assertThat(formats.of(field("price", 4))).isEqualTo(INT);
    }

    private static IndexField field(String name, int bytes) {
        return new IndexField(
                name,
                0,
                IndexOptions.NONE,
                false,
                false,
                false,
                DocValuesType.NONE,
                1,
                bytes,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
