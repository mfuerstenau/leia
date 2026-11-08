package io.github.mfuerstenau.leia.client.values;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.function.Predicate;

/**
 * Floats as Rust's {@code {:?}} prints them, the notation leia shows decoded numbers in: the
 * shortest form that reads back as the same value, with {@code .0} for whole numbers, in exponent
 * form from 1e16 on and below 1e-4, and {@code inf} for the infinities.
 */
public final class RustDebug {

    private RustDebug() {}

    /** {@code value} as Rust's {@code {:?}} prints an {@code f64}. */
    public static String of(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        double abs = Math.abs(value);
        boolean negative = Double.doubleToRawLongBits(value) < 0;
        if (Double.isInfinite(value)) {
            return negative ? "-inf" : "inf";
        }
        return of(
                Double.toString(abs),
                text -> Double.parseDouble(text) == abs,
                negative,
                (abs != 0 && abs < 1e-4) || abs >= 1e16);
    }

    /** {@code value} as Rust's {@code {:?}} prints an {@code f32}, from its own shortest form. */
    public static String of(float value) {
        if (Float.isNaN(value)) {
            return "NaN";
        }
        float abs = Math.abs(value);
        boolean negative = Float.floatToRawIntBits(value) < 0;
        if (Float.isInfinite(value)) {
            return negative ? "-inf" : "inf";
        }
        return of(
                Float.toString(abs),
                text -> Float.parseFloat(text) == abs,
                negative,
                (abs != 0 && abs < 1e-4f) || abs >= 1e16f);
    }

    /**
     * Java's shortest form of a number, {@code shortest}, in Rust's notation. Java keeps at least
     * two digits, where one may do: then the one digit that {@code readsBack} as the number.
     */
    private static String of(String shortest, Predicate<String> readsBack, boolean negative, boolean exponential) {
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
