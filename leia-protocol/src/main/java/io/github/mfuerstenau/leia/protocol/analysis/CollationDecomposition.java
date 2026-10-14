package io.github.mfuerstenau.leia.protocol.analysis;

/** The decomposition of a collator (ADR 0038): how text is normalized before it is compared. */
public enum CollationDecomposition {
    /** None. */
    NO,
    /** Canonical decomposition, as in Unicode's NFD. */
    CANONICAL,
    /** Also compatibility decomposition, as in NFKD; the JDK's collator only. */
    FULL
}
