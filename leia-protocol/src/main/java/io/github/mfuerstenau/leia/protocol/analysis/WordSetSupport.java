package io.github.mfuerstenau.leia.protocol.analysis;

/** Whether a preset analyzer takes stop words or stem exclusions (ADR 0017); serialized as its name. */
public enum WordSetSupport {
    /** It doesn't take the set. */
    NONE,
    /** It takes the set, and has a default without it. */
    OPTIONAL,
    /** It can't be created without the set. */
    REQUIRED
}
