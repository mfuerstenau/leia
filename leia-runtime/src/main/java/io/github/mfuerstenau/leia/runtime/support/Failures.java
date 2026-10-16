package io.github.mfuerstenau.leia.runtime.support;

/** How the runtimes put a failure into words for leia (ADR 0050). */
public final class Failures {

    /** How many causes {@link #describe} names at most. */
    private static final int MAX_CAUSES = 8;

    private Failures() {}

    /** The message of {@code e}, or its class if it has none. */
    public static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** The message of {@code t}, followed by those of its causes, at most {@value #MAX_CAUSES} of them. */
    public static String describe(Throwable t) {
        StringBuilder text = new StringBuilder(message(t));
        int depth = 0;
        for (Throwable cause = t.getCause(); cause != null && depth < MAX_CAUSES; cause = cause.getCause(), depth++) {
            text.append("; caused by ")
                    .append(cause.getClass().getSimpleName())
                    .append(": ")
                    .append(message(cause));
        }
        return text.toString();
    }
}
