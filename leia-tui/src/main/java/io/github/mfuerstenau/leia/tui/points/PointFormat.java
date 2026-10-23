package io.github.mfuerstenau.leia.tui.points;

import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.tui.documents.NumberFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * How the bytes of a point are shown (ADR 0041). The index doesn't record what a point encodes, so
 * {@code t} cycles through the encodings of Lucene's point fields that fit its size, starting with
 * a guess, as the search screen does for its point types (ADR 0011 §6).
 */
public enum PointFormat {
    /** {@code IntPoint}: each dimension a sortable int. */
    INT,
    /** {@code LongPoint}: each dimension a sortable long. */
    LONG,
    /** {@code FloatPoint}: each dimension a sortable float. */
    FLOAT,
    /** {@code DoublePoint}: each dimension a sortable double. */
    DOUBLE,
    /** {@code LatLonPoint}: a latitude and a longitude, 4 bytes each. */
    LAT_LON,
    /** {@code InetAddressPoint}: an IPv6 address, or an IPv4 one mapped into it. */
    INET,
    /** The bytes of each dimension in hex. */
    HEX;

    /** Lucene's {@code GeoEncodingUtils}: the latitude and the longitude per encoded unit. */
    private static final double LAT_DECODE = 180.0 / (1L << 32);

    private static final double LON_DECODE = 360.0 / (1L << 32);

    /**
     * The decimals of a latitude or longitude: about 10 cm. The encoding keeps steps of about 1 cm
     * and rounds down, so a 7th decimal would show the step instead of the value indexed.
     */
    private static final int DEGREE_DECIMALS = 6;

    /**
     * The formats that fit points of {@code dimensions} dimensions of {@code bytesPerDimension}
     * bytes, in the order {@code t} cycles them; the first is the guess.
     */
    public static List<PointFormat> formatsFor(int bytesPerDimension, int dimensions) {
        return switch (bytesPerDimension) {
            case 4 -> dimensions == 2 ? List.of(LAT_LON, INT, FLOAT, HEX) : List.of(INT, FLOAT, HEX);
            case 8 -> List.of(LONG, DOUBLE, HEX);
            case 16 -> dimensions == 1 ? List.of(INET, HEX) : List.of(HEX);
            default -> List.of(HEX);
        };
    }

    /** What the format is called, as in "as int". */
    public String label() {
        return switch (this) {
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case LAT_LON -> "lat/lon";
            case INET -> "inet";
            case HEX -> "hex";
        };
    }

    /**
     * {@code point} decoded as this format: {@code 42}, {@code [1, 2, 3]} for several dimensions,
     * {@code 52.52, 13.405} for a latitude and longitude. A point that doesn't fit the format shows
     * in hex.
     */
    public String format(PointValue point) {
        List<byte[]> dimensions =
                point.dimensions().stream().map(HexFormat.of()::parseHex).toList();
        return switch (this) {
            case INT -> each(dimensions, 4, bytes -> Integer.toString(sortableInt(bytes)));
            case LONG -> each(dimensions, 8, bytes -> Long.toString(sortableLong(bytes)));
            case FLOAT ->
                each(dimensions, 4, bytes -> {
                    int bits = sortableInt(bytes);
                    return NumberFormat.debug(Float.intBitsToFloat(bits ^ ((bits >> 31) & Integer.MAX_VALUE)));
                });
            case DOUBLE ->
                each(dimensions, 8, bytes -> {
                    long bits = sortableLong(bytes);
                    return NumberFormat.debug(Double.longBitsToDouble(bits ^ ((bits >> 63) & Long.MAX_VALUE)));
                });
            case LAT_LON -> {
                if (dimensions.size() != 2 || !fits(dimensions, 4)) {
                    yield hex(point);
                }
                yield degrees(sortableInt(dimensions.get(0)) * LAT_DECODE) + ", "
                        + degrees(sortableInt(dimensions.get(1)) * LON_DECODE);
            }
            case INET -> each(dimensions, 16, PointFormat::inet);
            case HEX -> hex(point);
        };
    }

    /** The format after this one among {@code formats}, or the first if this isn't one of them. */
    PointFormat next(List<PointFormat> formats) {
        int index = formats.indexOf(this);
        return formats.get((index + 1) % formats.size());
    }

    private static String each(List<byte[]> dimensions, int bytes, Function<byte[], String> decode) {
        if (dimensions.isEmpty() || !fits(dimensions, bytes)) {
            return joined(dimensions.stream().map(HexFormat.of()::formatHex).toList());
        }
        return joined(dimensions.stream().map(decode).toList());
    }

    private static boolean fits(List<byte[]> dimensions, int bytes) {
        return dimensions.stream().allMatch(dimension -> dimension.length == bytes);
    }

    private static String joined(List<String> values) {
        return values.size() == 1 ? values.getFirst() : "[" + String.join(", ", values) + "]";
    }

    private static String hex(PointValue point) {
        return joined(point.dimensions());
    }

    /** Lucene's {@code NumericUtils.sortableBytesToInt}: big-endian, with the sign bit flipped. */
    private static int sortableInt(byte[] bytes) {
        int value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xff);
        }
        return value ^ Integer.MIN_VALUE;
    }

    /** Lucene's {@code NumericUtils.sortableBytesToLong}. */
    private static long sortableLong(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xff);
        }
        return value ^ Long.MIN_VALUE;
    }

    private static String degrees(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value)
                .setScale(DEGREE_DECIMALS, RoundingMode.HALF_EVEN)
                .stripTrailingZeros();
        return rounded.signum() == 0 ? "0" : rounded.toPlainString();
    }

    /** An address; an IPv4 address mapped into IPv6 shows as IPv4, as Lucene decodes it. */
    private static String inet(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException _) {
            return HexFormat.of().formatHex(bytes);
        }
    }
}
