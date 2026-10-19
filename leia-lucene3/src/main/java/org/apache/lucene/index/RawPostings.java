package org.apache.lucene.index;

/**
 * The postings of a segment over every document, deleted ones included, for leia's rebuild of a
 * document (ADR 0062). Lucene 3.6 reads its postings through the segment's deletions, and its only
 * way around them, {@link IndexReader#rawTermDocs}, has no positions. The term docs and positions of
 * a segment are package-private, so this class lives in Lucene's package; it is relocated with the
 * rest of Lucene into the module's jar.
 */
public final class RawPostings {

    private RawPostings() {}

    /** The term docs of {@code segment}, without its deletions; unpositioned until {@code seek}. */
    public static TermDocs termDocs(SegmentReader segment) {
        return new SegmentTermDocs(segment, true);
    }

    /** The term positions of {@code segment}, without its deletions; unpositioned until {@code seek}. */
    public static TermPositions termPositions(SegmentReader segment) {
        SegmentTermPositions positions = new SegmentTermPositions(segment);
        positions.deletedDocs = null;
        return positions;
    }
}
