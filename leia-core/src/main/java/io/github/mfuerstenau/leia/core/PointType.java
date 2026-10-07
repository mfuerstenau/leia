package io.github.mfuerstenau.leia.core;

import org.apache.lucene.index.FieldInfo;

/**
 * The type of a point field's values, which the index doesn't record (ADR 0011): the standard query
 * parser needs it to turn a range into a {@code PointRangeQuery}.
 */
public enum PointType {
    INT(Integer.class, Integer.BYTES),
    LONG(Long.class, Long.BYTES),
    FLOAT(Float.class, Float.BYTES),
    DOUBLE(Double.class, Double.BYTES);

    private final Class<? extends Number> type;
    private final int bytes;

    PointType(Class<? extends Number> type, int bytes) {
        this.type = type;
        this.bytes = bytes;
    }

    Class<? extends Number> type() {
        return type;
    }

    /** Whether {@code field} has one-dimensional points as wide as values of this type. */
    public boolean fits(FieldInfo field) {
        return field.getPointDimensionCount() == 1 && field.getPointNumBytes() == bytes;
    }
}
