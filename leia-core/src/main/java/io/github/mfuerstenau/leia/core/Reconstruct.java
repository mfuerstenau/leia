package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

/**
 * Rebuilds the terms a document was indexed with, field by field (ADR 0029). A field whose term
 * vector in the document holds all that its postings hold is read from the term vector. Any other
 * field is rebuilt from the postings: every term of the field in the document's segment is
 * advanced to the document, so it costs a pass over all the field's terms in the segment. The
 * postings don't skip deleted documents, so a deleted document is rebuilt too.
 */
public final class Reconstruct {

    /** How many terms the scan reads between two progress reports. */
    public static final int PROGRESS_INTERVAL = 1024;

    /** Where the terms of a field come from. */
    public enum Source {
        /** The document's term vector, read whole. */
        TERM_VECTOR,
        /** The postings of every term of the field in the document's segment. */
        POSTINGS
    }

    /**
     * The terms a document was indexed with in a field.
     *
     * @param field the field name
     * @param indexOptions what the postings of the field hold in the document's segment; {@link
     *     IndexOptions#NONE} if the segment doesn't have the field
     * @param source where the terms come from
     * @param terms the terms in term order, with their occurrences in the document; {@code freq} is 1
     *     for postings without freqs, and the positions are empty for postings without positions
     */
    public record Field(String field, IndexOptions indexOptions, Source source, List<TermVectorTerm> terms) {}

    private final ScanProgress progress;
    private long scanned;

    private Reconstruct(ScanProgress progress) {
        this.progress = progress;
    }

    /**
     * Rebuilds the terms of a document in each of {@code fields}, in that order.
     *
     * @throws CancellationException from {@code progress}, to stop the scan
     * @throws IOException if reading the term vectors or postings fails
     */
    static List<Field> reconstruct(IndexReader reader, int docId, List<String> fields, ScanProgress progress)
            throws IOException {
        return new Reconstruct(progress).run(SegmentDoc.of(reader, docId), fields);
    }

    /**
     * How many terms {@link #reconstruct} goes through: the terms of the fields it rebuilds from the
     * postings, in the document's segment. Empty if the segment doesn't know how many terms a field
     * has.
     */
    static OptionalLong termCount(IndexReader reader, int docId, List<String> fields) throws IOException {
        SegmentDoc segmentDoc = SegmentDoc.of(reader, docId);
        long count = 0;
        for (String name : fields) {
            Terms terms = postings(segmentDoc, name);
            if (terms != null) {
                long size = terms.size();
                if (size < 0) {
                    return OptionalLong.empty();
                }
                count += size;
            }
        }
        return OptionalLong.of(count);
    }

    private List<Field> run(SegmentDoc segmentDoc, List<String> fields) throws IOException {
        List<Field> result = new ArrayList<>(fields.size());
        for (String name : fields) {
            result.add(field(segmentDoc, name));
        }
        progress.scanned(scanned);
        return List.copyOf(result);
    }

    private Field field(SegmentDoc segmentDoc, String name) throws IOException {
        LeafReader leaf = segmentDoc.leaf();
        FieldInfo info = leaf.getFieldInfos().fieldInfo(name);
        IndexOptions options = info != null ? info.getIndexOptions() : IndexOptions.NONE;
        if (options == IndexOptions.NONE) {
            return new Field(name, options, Source.POSTINGS, List.of());
        }
        Terms termVector = leaf.termVectors().get(segmentDoc.doc(), name);
        if (termVector != null && covers(termVector, info)) {
            return new Field(name, options, Source.TERM_VECTOR, DocumentReader.terms(termVector));
        }
        Terms terms = leaf.terms(name);
        return new Field(
                name, options, Source.POSTINGS, terms != null ? scan(terms, info, segmentDoc.doc()) : List.of());
    }

    /**
     * The postings to scan for a field of the document's segment: none if the field isn't indexed
     * there, or its term vector in the document is read instead.
     */
    private static Terms postings(SegmentDoc segmentDoc, String name) throws IOException {
        LeafReader leaf = segmentDoc.leaf();
        FieldInfo info = leaf.getFieldInfos().fieldInfo(name);
        if (info == null || info.getIndexOptions() == IndexOptions.NONE) {
            return null;
        }
        Terms termVector = leaf.termVectors().get(segmentDoc.doc(), name);
        return termVector != null && covers(termVector, info) ? null : leaf.terms(name);
    }

    /** Whether a term vector holds all that the postings of its field hold. */
    private static boolean covers(Terms termVector, FieldInfo info) {
        IndexOptions options = info.getIndexOptions();
        return (!hasPositions(options) || termVector.hasPositions())
                && (!hasOffsets(options) || termVector.hasOffsets())
                && (!info.hasPayloads() || termVector.hasPayloads());
    }

    private static boolean hasPositions(IndexOptions options) {
        return options.compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0;
    }

    private static boolean hasOffsets(IndexOptions options) {
        return options.compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
    }

    /** Advances the postings of every term of a field to the document, and keeps those that have it. */
    private List<TermVectorTerm> scan(Terms terms, FieldInfo info, int doc) throws IOException {
        IndexOptions options = info.getIndexOptions();
        boolean positions = hasPositions(options);
        boolean offsets = hasOffsets(options);
        int flags =
                positions ? PostingsEnum.ALL : options == IndexOptions.DOCS ? PostingsEnum.NONE : PostingsEnum.FREQS;
        List<TermVectorTerm> result = new ArrayList<>();
        TermsEnum termsEnum = terms.iterator();
        PostingsEnum postings = null;
        for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
            if (scanned % PROGRESS_INTERVAL == 0) {
                progress.scanned(scanned);
            }
            scanned++;
            postings = termsEnum.postings(postings, flags);
            if (postings.advance(doc) != doc) {
                continue;
            }
            int freq = postings.freq();
            result.add(new TermVectorTerm(
                    BytesRef.deepCopyOf(term),
                    freq,
                    positions ? TermPosition.read(postings, freq, true, offsets) : List.of()));
        }
        return List.copyOf(result);
    }
}
