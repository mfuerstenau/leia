package io.github.mfuerstenau.leia.core;

import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.util.NumericUtils;

/**
 * How a number field's doc values encode their numbers, which the index doesn't record (ADR 0033).
 * The same five ways the documents screen decodes them.
 */
public enum DocValuesNumber {
    /** The long itself, as {@code NumericDocValuesField} and {@code LongField} write it. */
    LONG,
    /** The raw bits of a double, as {@code DoubleDocValuesField} writes them. */
    DOUBLE,
    /** {@link NumericUtils#doubleToSortableLong}, as {@code DoubleField} writes it. */
    SORTABLE_DOUBLE,
    /** The raw bits of a float, as {@code FloatDocValuesField} writes them. */
    FLOAT,
    /** {@link NumericUtils#floatToSortableInt}, as {@code FloatField} writes it. */
    SORTABLE_FLOAT;

    /** Whether the doc values hold the raw bits, which don't order negative numbers as numbers. */
    boolean raw() {
        return this == DOUBLE || this == FLOAT;
    }

    /**
     * The number of {@code text}: a long for {@link #LONG}, a double or a float (widened) otherwise.
     *
     * @throws IllegalArgumentException if {@code text} is not such a number, or is NaN
     */
    double parseFloating(String text) {
        double value;
        try {
            value = this == DOUBLE || this == SORTABLE_DOUBLE ? Double.parseDouble(text) : Float.parseFloat(text);
        } catch (NumberFormatException e) {
            throw notANumber(text, e);
        }
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("NaN can't be searched for");
        }
        return value;
    }

    /** @throws IllegalArgumentException if {@code text} is not a long */
    static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a LONG", e);
        }
    }

    /** The doc value that encodes {@code value}, which a float format holds as a float. */
    long encode(double value) {
        return switch (this) {
            case LONG -> throw new IllegalStateException("a LONG is no floating point number");
            case DOUBLE -> Double.doubleToRawLongBits(value);
            case SORTABLE_DOUBLE -> NumericUtils.doubleToSortableLong(value);
            case FLOAT -> Float.floatToRawIntBits((float) value);
            case SORTABLE_FLOAT -> NumericUtils.floatToSortableInt((float) value);
        };
    }

    /** The next number up in this format, keeping {@code -0.0} and {@code 0.0} apart. */
    double nextUp(double value) {
        return this == DOUBLE || this == SORTABLE_DOUBLE ? DoublePoint.nextUp(value) : FloatPoint.nextUp((float) value);
    }

    /** The next number down in this format, keeping {@code -0.0} and {@code 0.0} apart. */
    double nextDown(double value) {
        return this == DOUBLE || this == SORTABLE_DOUBLE
                ? DoublePoint.nextDown(value)
                : FloatPoint.nextDown((float) value);
    }

    private IllegalArgumentException notANumber(String text, NumberFormatException e) {
        return new IllegalArgumentException("'" + text + "' is not a " + name(), e);
    }
}
