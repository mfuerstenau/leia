package io.github.mfuerstenau.leia.server.points;

import io.github.mfuerstenau.leia.protocol.points.PointValue;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.lucene.util.BytesRef;

/** Packed points as {@link PointValue}s (ADR 0041). */
public final class PointMapper {

    private PointMapper() {}

    /**
     * A packed point, or the first dimensions of one, split into its dimensions of {@code
     * bytesPerDimension} bytes each.
     */
    public static PointValue pointValue(BytesRef packed, int bytesPerDimension) {
        HexFormat hex = HexFormat.of();
        List<String> dimensions = new ArrayList<>(packed.length / bytesPerDimension);
        for (int start = 0; start + bytesPerDimension <= packed.length; start += bytesPerDimension) {
            int from = packed.offset + start;
            dimensions.add(hex.formatHex(packed.bytes, from, from + bytesPerDimension));
        }
        return new PointValue(List.copyOf(dimensions));
    }

    /** {@link #pointValue(BytesRef, int)} of a packed array. */
    public static PointValue pointValue(byte[] packed, int bytesPerDimension) {
        return pointValue(new BytesRef(packed), bytesPerDimension);
    }
}
