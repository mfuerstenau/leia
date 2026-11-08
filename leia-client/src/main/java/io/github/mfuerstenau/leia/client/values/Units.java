package io.github.mfuerstenau.leia.client.values;

import java.util.Locale;

/** Counts and sizes as leia words them, in every frontend alike. */
public final class Units {

    private Units() {}

    /** {@code n} and the noun, in the plural unless {@code n} is 1. */
    public static String count(long n, String noun) {
        return n == 1 ? "1 " + noun : n + " " + noun + "s";
    }

    /** A size in bytes, in the largest binary unit that keeps it at 1 or more, such as {@code 1.5 KiB}. */
    public static String size(long bytes) {
        double kib = 1024.0;
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / kib);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / kib / kib);
        }
        return String.format(Locale.ROOT, "%.1f GiB", bytes / kib / kib / kib);
    }
}
