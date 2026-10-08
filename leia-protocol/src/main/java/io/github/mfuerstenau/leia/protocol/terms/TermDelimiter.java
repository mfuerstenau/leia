package io.github.mfuerstenau.leia.protocol.terms;

/** What separates a term from its document frequency in an export (ADR 0022); serialized as its name. */
public enum TermDelimiter {
    COMMA,
    SPACE,
    TAB
}
