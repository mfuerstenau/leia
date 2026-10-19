package io.github.mfuerstenau.leia.server.index;

/**
 * Which indexes go to a runtime of {@code leia-runtime} (ADR 0044, ADR 0065).
 *
 * @param lucene10Runtime whether an index of Lucene 9 or 10 goes to a runtime that reads it, such as
 *     leia-lucene10, instead of leia-core; for {@code Lucene10ParityTest} only, until the server routes
 *     every index to a runtime
 */
public record Routing(boolean lucene10Runtime) {

    /** As leia routes: indexes of Lucene 9 and 10 to leia-core. */
    public static final Routing DEFAULT = new Routing(false);

    /** Whether an index whose version has {@code major} goes to leia-core. */
    boolean core(int major, int minSupportedMajor) {
        return major >= minSupportedMajor && !lucene10Runtime;
    }
}
