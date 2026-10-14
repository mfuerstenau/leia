package io.github.mfuerstenau.leia.server.points;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.util.BytesRef;

/** Packed points as {@link PointValue}s, and the checks the point methods share (ADR 0041). */
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

    /**
     * Checks that {@code field} is a field with points. Returns the bytes of each of its dimensions.
     *
     * @throws RpcException {@code FIELD_NOT_FOUND} for an unknown field, {@code INVALID_PARAMS} for a
     *     field without points
     */
    static int checkPointField(ReadOnlyIndex index, String field) throws RpcException {
        FieldInfo info = index.fieldInfo(field)
                .orElseThrow(
                        () -> new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field '" + field + "' in the index"));
        if (info.getPointDimensionCount() == 0) {
            throw new RpcException(ErrorCode.INVALID_PARAMS, "Invalid params: the field '" + field + "' has no points");
        }
        return info.getPointNumBytes();
    }

    static RpcException notReadable(String field, IOException e) {
        return new RpcException(
                ErrorCode.INDEX_NOT_READABLE, "Cannot read the points of field '" + field + "': " + e.getMessage(), e);
    }
}
