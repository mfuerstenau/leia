package io.github.mfuerstenau.leia.client.values;

import static io.github.mfuerstenau.leia.client.values.PointFormat.DOUBLE;
import static io.github.mfuerstenau.leia.client.values.PointFormat.FLOAT;
import static io.github.mfuerstenau.leia.client.values.PointFormat.HEX;
import static io.github.mfuerstenau.leia.client.values.PointFormat.INET;
import static io.github.mfuerstenau.leia.client.values.PointFormat.INT;
import static io.github.mfuerstenau.leia.client.values.PointFormat.LAT_LON;
import static io.github.mfuerstenau.leia.client.values.PointFormat.LONG;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.points.PointValue;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Decoding points as Lucene encodes them (ADR 0041); the hex is what Lucene's point fields pack. */
class PointFormatTest {

    private static PointValue point(String... dimensions) {
        return new PointValue(List.of(dimensions));
    }

    @Test
    void decodesSortableInts() {
        assertThat(INT.format(point("8000002a"))).isEqualTo("42");
        assertThat(INT.format(point("7fffffff"))).isEqualTo("-1");
        assertThat(INT.format(point("00000000"))).isEqualTo("-2147483648");
        assertThat(INT.format(point("80000001", "80000002", "80000003"))).isEqualTo("[1, 2, 3]");
    }

    @Test
    void decodesSortableLongs() {
        assertThat(LONG.format(point("800000000000002a"))).isEqualTo("42");
        assertThat(LONG.format(point("7ffffffffffffffe"))).isEqualTo("-2");
    }

    @Test
    void decodesSortableFloatsAndDoubles() {
        assertThat(FLOAT.format(point("c1480000"))).isEqualTo("12.5");
        assertThat(FLOAT.format(point("403fffff"))).isEqualTo("-1.5");
        assertThat(FLOAT.format(point("80000000"))).isEqualTo("0.0");
        assertThat(FLOAT.format(point("7fffffff"))).isEqualTo("-0.0");
        assertThat(DOUBLE.format(point("c029000000000000"))).isEqualTo("12.5");
        assertThat(DOUBLE.format(point("4007ffffffffffff"))).isEqualTo("-1.5");
        assertThat(DOUBLE.format(point("7fffffffffffffff"))).isEqualTo("-0.0");
    }

    @Test
    void decodesLatitudeAndLongitude() {
        assertThat(LAT_LON.format(point("cab1f2cd", "89884e47"))).isEqualTo("52.52, 13.405");
        assertThat(LAT_LON.format(point("4fd44f30", "eb86f0c0"))).isEqualTo("-33.87, 151.21");
        assertThat(LAT_LON.format(point("80000000", "80000000"))).isEqualTo("0, 0");
    }

    @Test
    void decodesAddressesAndIPv4MappedAsIPv4() {
        assertThat(INET.format(point("00000000000000000000ffffc0a80001"))).isEqualTo("192.168.0.1");
        assertThat(INET.format(point("00000000000000000000000000000001"))).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void showsHexAndWhatDoesNotFitAsHex() {
        assertThat(HEX.format(point("8000002a"))).isEqualTo("8000002a");
        assertThat(HEX.format(point("01", "02"))).isEqualTo("[01, 02]");
        assertThat(LONG.format(point("8000002a"))).isEqualTo("8000002a");
        assertThat(LAT_LON.format(point("8000002a"))).isEqualTo("8000002a");
    }

    @Test
    void offersTheFormatsThatFitTheSizeWithTheGuessFirst() {
        assertThat(PointFormat.formatsFor(4, 1)).containsExactly(INT, FLOAT, HEX);
        assertThat(PointFormat.formatsFor(4, 2)).containsExactly(LAT_LON, INT, FLOAT, HEX);
        assertThat(PointFormat.formatsFor(8, 1)).containsExactly(LONG, DOUBLE, HEX);
        assertThat(PointFormat.formatsFor(16, 1)).containsExactly(INET, HEX);
        assertThat(PointFormat.formatsFor(3, 1)).containsExactly(HEX);
    }
}
