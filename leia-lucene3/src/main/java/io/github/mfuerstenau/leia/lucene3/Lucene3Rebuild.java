package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.Documents.Position;
import io.github.mfuerstenau.leia.runtime.Documents.VectorTerm;
import io.github.mfuerstenau.leia.runtime.Rebuild;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongConsumer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.RawPostings;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.index.TermPositionVector;
import org.apache.lucene.index.TermPositions;
import org.apache.lucene.util.ReaderUtil;

/**
 * The rebuild of a document with Lucene 3.6 (ADR 0062), after the one of Lucene 5.5 (ADR 0059) over
 * 3.6's term enums and term vectors. The term vectors of 3.6 have no payloads, so a field with
 * payloads is always rebuilt from the postings; the postings have no offsets. The postings are read
 * without the segment's deletions through {@link RawPostings}, so deleted documents are rebuilt too.
 * 3.6 doesn't know how many terms a field has, and has no points.
 */
final class Lucene3Rebuild implements Rebuild {

    private final List<IndexReader> segments;
    private final int[] starts;

    Lucene3Rebuild(IndexReader reader) {
        List<IndexReader> gathered = new ArrayList<>();
        ReaderUtil.gatherSubReaders(gathered, reader);
        this.segments = List.copyOf(gathered);
        this.starts = new int[gathered.size()];
        int start = 0;
        for (int i = 0; i < gathered.size(); i++) {
            starts[i] = start;
            start += gathered.get(i).maxDoc();
        }
    }

    /** The segment of a document, and the document's id within it. */
    private record SegmentDoc(SegmentReader segment, int doc) {}

    private SegmentDoc segmentDoc(int docId) {
        int i = ReaderUtil.subIndex(docId, starts);
        if (!(segments.get(i) instanceof SegmentReader segment)) {
            throw new IllegalStateException("Not a segment: " + segments.get(i));
        }
        return new SegmentDoc(segment, docId - starts[i]);
    }

    /** {@inheritDoc} Empty as soon as a field is rebuilt from the postings. */
    @Override
    public OptionalLong count(int docId, List<String> fields) throws IOException {
        SegmentDoc segmentDoc = segmentDoc(docId);
        for (String name : fields) {
            if (postingsNeeded(segmentDoc, name)) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.of(0);
    }

    @Override
    public List<RebuiltField> rebuild(int docId, List<String> fields, LongConsumer progress) throws IOException {
        SegmentDoc segmentDoc = segmentDoc(docId);
        Scan scan = new Scan(progress);
        List<RebuiltField> result = new ArrayList<>(fields.size());
        for (String name : fields) {
            result.add(scan.field(segmentDoc, name));
        }
        progress.accept(scan.scanned);
        return List.copyOf(result);
    }

    /** Whether a field of the document is indexed in its segment, and its term vector doesn't cover it. */
    private static boolean postingsNeeded(SegmentDoc segmentDoc, String name) throws IOException {
        FieldInfo info = segmentDoc.segment().getFieldInfos().fieldInfo(name);
        if (info == null || !info.isIndexed) {
            return false;
        }
        TermFreqVector vector = segmentDoc.segment().getTermFreqVector(segmentDoc.doc(), name);
        return vector == null || !covers(vector, info);
    }

    /**
     * Whether a term vector holds all that the postings of its field hold: 3.6's term vectors have
     * no payloads, and its postings no offsets.
     */
    private static boolean covers(TermFreqVector vector, FieldInfo info) {
        if (info.storePayloads) {
            return false;
        }
        if (info.indexOptions != FieldInfo.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) {
            return true;
        }
        // A vector with offsets only is a TermPositionVector without positions.
        return vector instanceof TermPositionVector withPositions
                && (withPositions.size() == 0 || withPositions.getTermPositions(0) != null);
    }

    /** One rebuild, which counts the terms it goes through. */
    private static final class Scan {

        private final LongConsumer progress;
        private long scanned;

        Scan(LongConsumer progress) {
            this.progress = progress;
        }

        private void next() {
            if (scanned % PROGRESS_INTERVAL == 0) {
                progress.accept(scanned);
            }
            scanned++;
        }

        RebuiltField field(SegmentDoc segmentDoc, String name) throws IOException {
            SegmentReader segment = segmentDoc.segment();
            FieldInfo info = segment.getFieldInfos().fieldInfo(name);
            if (info == null || !info.isIndexed) {
                return new RebuiltField(name, RuntimeIndex.IndexOptions.NONE, Source.POSTINGS, List.of(), List.of());
            }
            RuntimeIndex.IndexOptions options = Lucene3Index.indexOptions(info);
            TermFreqVector vector = segment.getTermFreqVector(segmentDoc.doc(), name);
            if (vector != null && covers(vector, info)) {
                return new RebuiltField(name, options, Source.TERM_VECTOR, Lucene3Index.vectorTerms(vector), List.of());
            }
            return new RebuiltField(name, options, Source.POSTINGS, scan(segmentDoc, info), List.of());
        }

        /** Moves the raw postings of every term of a field to the document, and keeps those that have it. */
        private List<VectorTerm> scan(SegmentDoc segmentDoc, FieldInfo info) throws IOException {
            boolean positions = info.indexOptions == FieldInfo.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
            boolean freqs = info.indexOptions != FieldInfo.IndexOptions.DOCS_ONLY;
            int doc = segmentDoc.doc();
            List<VectorTerm> result = new ArrayList<>();
            SegmentReader segment = segmentDoc.segment();
            try (TermEnum termEnum = segment.terms(new Term(info.name, ""));
                    TermDocs docs = positions ? RawPostings.termPositions(segment) : RawPostings.termDocs(segment)) {
                for (Term term = termEnum.term();
                        term != null && term.field().equals(info.name);
                        term = termEnum.next() ? termEnum.term() : null) {
                    next();
                    docs.seek(termEnum);
                    if (!docs.skipTo(doc) || docs.doc() != doc) {
                        continue;
                    }
                    int freq = freqs ? docs.freq() : 1;
                    List<Position> occurrences = new ArrayList<>(positions ? freq : 0);
                    for (int i = 0; positions && i < freq; i++) {
                        occurrences.add(Lucene3Index.position((TermPositions) docs));
                    }
                    result.add(new VectorTerm(Lucene3Index.utf8(term.text()), freq, List.copyOf(occurrences)));
                }
            }
            return List.copyOf(result);
        }
    }
}
