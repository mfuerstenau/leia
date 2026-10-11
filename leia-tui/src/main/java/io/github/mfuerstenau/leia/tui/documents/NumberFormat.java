package io.github.mfuerstenau.leia.tui.documents;

import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.function.Predicate;

/**
 * How the raw numbers of numeric doc values are shown. The index doesn't record what they encode,
 * so {@code x} cycles through the encodings Lucene's fields use.
 */
public enum NumberFormat {
    LONG,
    /** {@code DoubleDocValuesField}: the raw bits of a double. */
    DOUBLE,
    /** {@code NumericUtils.doubleToSortableLong}, as for sorted numeric doubles. */
    SORTABLE_DOUBLE,
    /** {@code FloatDocValuesField}: the raw bits of a float. */
    FLOAT,
    /** {@code NumericUtils.floatToSortableInt}. */
    SORTABLE_FLOAT;

    NumberFormat next() {
        return switch (this) {
            case LONG -> DOUBLE;
            case DOUBLE -> SORTABLE_DOUBLE;
            case SORTABLE_DOUBLE -> FLOAT;
            case FLOAT -> SORTABLE_FLOAT;
            case SORTABLE_FLOAT -> LONG;
        };
    }

    /** The same encoding on the wire, for a doc values query. */
    DocValuesNumber docValuesNumber() {
        return switch (this) {
            case LONG -> DocValuesNumber.LONG;
            case DOUBLE -> DocValuesNumber.DOUBLE;
            case SORTABLE_DOUBLE -> DocValuesNumber.SORTABLE_DOUBLE;
            case FLOAT -> DocValuesNumber.FLOAT;
            case SORTABLE_FLOAT -> DocValuesNumber.SORTABLE_FLOAT;
        };
    }

    String label() {
        return switch (this) {
            case LONG -> "long";
            case DOUBLE -> "double bits";
            case SORTABLE_DOUBLE -> "sortable double";
            case FLOAT -> "float bits";
            case SORTABLE_FLOAT -> "sortable float";
        };
    }

    /** {@code number} decoded as this format. Floats print as Rust's {@code {:?}} prints them. */
    public String format(long number) {
        return switch (this) {
            case LONG -> Long.toString(number);
            case DOUBLE -> debug(Double.longBitsToDouble(number));
            case SORTABLE_DOUBLE -> debug(Double.longBitsToDouble(number ^ ((number >> 63) & Long.MAX_VALUE)));
            case FLOAT -> debug(Float.intBitsToFloat((int) number));
            case SORTABLE_FLOAT -> {
                int bits = (int) number;
                yield debug(Float.intBitsToFloat(bits ^ ((bits >> 31) & Integer.MAX_VALUE)));
            }
        };
    }

    /**
     * {@code value} as Rust's {@code {:?}} prints an {@code f64}: the shortest form that reads back
     * as the same value, with {@code .0} for whole numbers, in exponent form from 1e16 on and below
     * 1e-4, and {@code inf} for the infinities.
     */
    static String debug(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        double abs = Math.abs(value);
        boolean negative = Double.doubleToRawLongBits(value) < 0;
        if (Double.isInfinite(value)) {
            return negative ? "-inf" : "inf";
        }
        return debug(
                Double.toString(abs),
                text -> Double.parseDouble(text) == abs,
                negative,
                (abs != 0 && abs < 1e-4) || abs >= 1e16);
    }

    /** {@code value} as Rust's {@code {:?}} prints an {@code f32}, from its own shortest form. */
    static String debug(float value) {
        if (Float.isNaN(value)) {
            return "NaN";
        }
        float abs = Math.abs(value);
        boolean negative = Float.floatToRawIntBits(value) < 0;
        if (Float.isInfinite(value)) {
            return negative ? "-inf" : "inf";
        }
        return debug(
                Float.toString(abs),
                text -> Float.parseFloat(text) == abs,
                negative,
                (abs != 0 && abs < 1e-4f) || abs >= 1e16f);
    }

    /**
     * Java's shortest form of a number, {@code shortest}, in Rust's notation. Java keeps at least
     * two digits, where one may do: then the one digit that {@code readsBack} as the number.
     */
    private static String debug(String shortest, Predicate<String> readsBack, boolean negative, boolean exponential) {
        BigDecimal decimal = new BigDecimal(shortest).stripTrailingZeros();
        if (decimal.precision() == 2) {
            BigDecimal one = decimal.round(new MathContext(1, RoundingMode.HALF_EVEN));
            if (readsBack.test(one.toString())) {
                decimal = one.stripTrailingZeros();
            }
        }
        String sign = negative ? "-" : "";
        if (!exponential) {
            String plain = decimal.toPlainString();
            return sign + (plain.contains(".") ? plain : plain + ".0");
        }
        String digits = decimal.unscaledValue().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + exponent;
    }
}
