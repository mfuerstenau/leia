package io.github.mfuerstenau.leia.lucene7;

import io.github.mfuerstenau.leia.runtime.Documents.Position;
import io.github.mfuerstenau.leia.runtime.Documents.VectorTerm;
import io.github.mfuerstenau.leia.runtime.Rebuild;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongConsumer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

/**
 * The rebuild of a document with Lucene 7.7 (ADR 0059), a copy of leia-core's {@code Reconstruct}
 * (ADR 0050): 7.7 has the same postings and term vectors. It has no {@code PointTree} yet, so the
 * points of a field are visited through {@link PointValues#intersect} with a visitor that crosses
 * every cell.
 */
final class Lucene7Rebuild implements Rebuild {

    private final IndexReader reader;

    Lucene7Rebuild(IndexReader reader) {
        this.reader = reader;
    }

    /** The segment of a document, and the document's id within it. */
    private record SegmentDoc(LeafReader leaf, int doc) {}

    private SegmentDoc segmentDoc(int docId) {
        List<LeafReaderContext> leaves = reader.leaves();
        LeafReaderContext context = leaves.get(ReaderUtil.subIndex(docId, leaves));
        return new SegmentDoc(context.reader(), docId - context.docBase);
    }

    @Override
    public OptionalLong count(int docId, List<String> fields) throws IOException {
        SegmentDoc segmentDoc = segmentDoc(docId);
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
            PointValues points = segmentDoc.leaf().getPointValues(name);
            if (points != null) {
                count += points.size();
            }
        }
        return OptionalLong.of(count);
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
        Terms termVector = termVector(segmentDoc, name);
        return termVector != null && covers(termVector, info) ? null : leaf.terms(name);
    }

    private static Terms termVector(SegmentDoc segmentDoc, String name) throws IOException {
        Fields vectors = segmentDoc.leaf().getTermVectors(segmentDoc.doc());
        return vectors == null ? null : vectors.terms(name);
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

    private static RuntimeIndex.IndexOptions indexOptions(IndexOptions options) {
        return switch (options) {
            case NONE -> RuntimeIndex.IndexOptions.NONE;
            case DOCS -> RuntimeIndex.IndexOptions.DOCS;
            case DOCS_AND_FREQS -> RuntimeIndex.IndexOptions.DOCS_AND_FREQS;
            case DOCS_AND_FREQS_AND_POSITIONS -> RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS;
            case DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS ->
                RuntimeIndex.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS;
        };
    }

    /** One rebuild, which counts the terms and points it goes through. */
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
            LeafReader leaf = segmentDoc.leaf();
            FieldInfo info = leaf.getFieldInfos().fieldInfo(name);
            IndexOptions options = info != null ? info.getIndexOptions() : IndexOptions.NONE;
            Source source = Source.POSTINGS;
            List<VectorTerm> terms = List.of();
            if (options != IndexOptions.NONE) {
                Terms termVector = termVector(segmentDoc, name);
                if (termVector != null && covers(termVector, info)) {
                    source = Source.TERM_VECTOR;
                    terms = Lucene7Index.vectorTerms(termVector);
                } else {
                    Terms postings = leaf.terms(name);
                    terms = postings != null ? scan(postings, info, segmentDoc.doc()) : List.of();
                }
            }
            PointValues points = leaf.getPointValues(name);
            return new RebuiltField(
                    name,
                    indexOptions(options),
                    source,
                    terms,
                    points != null ? points(points, segmentDoc.doc()) : List.of());
        }

        /** Advances the postings of every term of a field to the document, and keeps those that have it. */
        private List<VectorTerm> scan(Terms terms, FieldInfo info, int doc) throws IOException {
            IndexOptions options = info.getIndexOptions();
            boolean positions = hasPositions(options);
            boolean offsets = hasOffsets(options);
            int flags = positions
                    ? PostingsEnum.ALL
                    : options == IndexOptions.DOCS ? PostingsEnum.NONE : PostingsEnum.FREQS;
            List<VectorTerm> result = new ArrayList<>();
            TermsEnum termsEnum = terms.iterator();
            PostingsEnum postings = null;
            for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
                next();
                postings = termsEnum.postings(postings, flags);
                if (postings.advance(doc) != doc) {
                    continue;
                }
                int freq = postings.freq();
                List<Position> occurrences = new ArrayList<>(positions ? freq : 0);
                for (int i = 0; positions && i < freq; i++) {
                    occurrences.add(Lucene7Index.position(postings, true, offsets));
                }
                result.add(new VectorTerm(BytesRef.deepCopyOf(term).bytes, freq, List.copyOf(occurrences)));
            }
            return List.copyOf(result);
        }

        /** Goes through every point of a field in the segment, and keeps those of the document. */
        private List<byte[]> points(PointValues points, int doc) throws IOException {
            List<byte[]> result = new ArrayList<>();
            points.intersect(new PointValues.IntersectVisitor() {
                @Override
                public void visit(int docID) {
                    throw new IllegalStateException("A point without its value");
                }

                @Override
                public void visit(int docID, byte[] packedValue) {
                    next();
                    if (docID == doc) {
                        result.add(packedValue.clone());
                    }
                }

                @Override
                public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                    return PointValues.Relation.CELL_CROSSES_QUERY;
                }
            });
            result.sort(Arrays::compareUnsigned);
            return List.copyOf(result);
        }
    }
}
