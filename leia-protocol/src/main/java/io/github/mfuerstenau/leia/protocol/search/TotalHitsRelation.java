package io.github.mfuerstenau.leia.protocol.search;

/** How a total hit count relates to the real one, as Lucene's {@code TotalHits.Relation}; serialized as its name. */
public enum TotalHitsRelation {
    /** The count is exact. */
    EQUAL_TO,
    /** Counting stopped early: at least this many documents match. */
    GREATER_THAN_OR_EQUAL_TO
}
