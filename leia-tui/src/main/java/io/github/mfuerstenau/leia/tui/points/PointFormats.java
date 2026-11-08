package io.github.mfuerstenau.leia.tui.points;

import io.github.mfuerstenau.leia.client.values.PointFormat;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The format each point field is shown in (ADR 0041). The documents and the terms screens share
 * one, so that {@code t} on either changes both.
 */
public final class PointFormats {

    private final Map<String, PointFormat> chosen = new HashMap<>();

    /** The format of {@code field}: the one chosen, if it still fits the field, or else the guess. */
    public PointFormat of(IndexField field) {
        List<PointFormat> formats = formats(field);
        PointFormat format = chosen.get(field.name());
        return format != null && formats.contains(format) ? format : formats.getFirst();
    }

    /** Shows {@code field} in the next format that fits it. */
    public void cycle(IndexField field) {
        chosen.put(field.name(), of(field).next(formats(field)));
    }

    private static List<PointFormat> formats(IndexField field) {
        return PointFormat.formatsFor(field.pointNumBytes(), field.pointDimensionCount());
    }
}
