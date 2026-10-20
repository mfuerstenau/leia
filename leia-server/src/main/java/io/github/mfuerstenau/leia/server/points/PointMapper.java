package io.github.mfuerstenau.leia.server.points;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Packed points as {@link PointValue}s, and the checks the point methods share (ADR 0041). */
public final class PointMapper {

    private PointMapper() {}

    /**
     * A packed point, or the first dimensions of one, split into its dimensions of {@code
     * bytesPerDimension} bytes each.
     */
    public static PointValue pointValue(byte[] packed, int bytesPerDimension) {
        HexFormat hex = HexFormat.of();
        List<String> dimensions = new ArrayList<>(packed.length / bytesPerDimension);
        for (int start = 0; start + bytesPerDimension <= packed.length; start += bytesPerDimension) {
            dimensions.add(hex.formatHex(packed, start, start + bytesPerDimension));
        }
        return new PointValue(List.copyOf(dimensions));
    }

    /** The index directory that a document id is in: the last one starting at or before it. */
    public static int indexOf(List<Integer> docBases, int docId) {
        int found = 0;
        for (int i = 0; i < docBases.size(); i++) {
            if (docBases.get(i) <= docId) {
                found = i;
            }
        }
        return found;
    }

    public static RpcException notReadable(String field, IOException e) {
        return new RpcException(
                ErrorCode.INDEX_NOT_READABLE, "Cannot read the points of field '" + field + "': " + e.getMessage(), e);
    }
}
