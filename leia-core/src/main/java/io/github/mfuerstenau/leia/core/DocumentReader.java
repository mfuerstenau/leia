package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.IndexDocument.DocValues;
import io.github.mfuerstenau.leia.core.IndexDocument.StoredValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.StreamSupport;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * Reads one document from the segment that holds it, with the segment's own document id (ADR
 * 0006). Nothing goes through the composite reader's merged views, which would build ordinal maps
 * over whole fields.
 */
final class DocumentReader {

    private DocumentReader() {}

    static IndexDocument document(DirectoryReader reader, FieldInfos fieldInfos, int docId) throws IOException {
        SegmentDoc segmentDoc = SegmentDoc.of(reader, docId);
        LeafReader leaf = segmentDoc.leaf();
        int doc = segmentDoc.doc();
        Bits liveDocs = leaf.getLiveDocs();
        boolean deleted = liveDocs != null && !liveDocs.get(doc);

        StoredValues stored = new StoredValues(null);
        leaf.storedFields().document(doc, stored);
        Fields termVectors = leaf.termVectors().get(doc);

        List<FieldInfo> fields = StreamSupport.stream(fieldInfos.spliterator(), false)
                .sorted(Comparator.comparingInt(FieldInfo::getFieldNumber))
                .toList();
        List<IndexDocument.Field> result = new ArrayList<>(fields.size());
        for (FieldInfo field : fields) {
            String name = field.getName();
            // The segment's own field info: a field that the segment lacks has nothing here.
            FieldInfo segmentField = leaf.getFieldInfos().fieldInfo(name);
            result.add(new IndexDocument.Field(
                    name,
                    segmentField != null && segmentField.hasNorms() ? norm(leaf, name, doc) : OptionalLong.empty(),
                    List.copyOf(stored.values.getOrDefault(name, List.of())),
                    segmentField != null ? docValues(leaf, segmentField, doc) : Optional.empty(),
                    termVectors != null && termVectors.terms(name) != null));
        }
        return new IndexDocument(docId, deleted, List.copyOf(result));
    }

    private static OptionalLong norm(LeafReader leaf, String field, int doc) throws IOException {
        NumericDocValues norms = leaf.getNormValues(field);
        return norms != null && norms.advanceExact(doc) ? OptionalLong.of(norms.longValue()) : OptionalLong.empty();
    }

    private static Optional<DocValues> docValues(LeafReader leaf, FieldInfo field, int doc) throws IOException {
        String name = field.getName();
        return switch (field.getDocValuesType()) {
            case NONE -> Optional.empty();
            case NUMERIC -> {
                NumericDocValues values = leaf.getNumericDocValues(name);
                yield values != null && values.advanceExact(doc)
                        ? numbers(field, List.of(values.longValue()))
                        : Optional.empty();
            }
            case SORTED_NUMERIC -> {
                SortedNumericDocValues values = leaf.getSortedNumericDocValues(name);
                if (values == null || !values.advanceExact(doc)) {
                    yield Optional.empty();
                }
                List<Long> numbers = new ArrayList<>(values.docValueCount());
                for (int i = 0; i < values.docValueCount(); i++) {
                    numbers.add(values.nextValue());
                }
                yield numbers(field, numbers);
            }
            case BINARY -> {
                BinaryDocValues values = leaf.getBinaryDocValues(name);
                yield values != null && values.advanceExact(doc)
                        ? bytes(field, List.of(BytesRef.deepCopyOf(values.binaryValue())))
                        : Optional.empty();
            }
            case SORTED -> {
                SortedDocValues values = leaf.getSortedDocValues(name);
                yield values != null && values.advanceExact(doc)
                        ? bytes(field, List.of(BytesRef.deepCopyOf(values.lookupOrd(values.ordValue()))))
                        : Optional.empty();
            }
            case SORTED_SET -> {
                SortedSetDocValues values = leaf.getSortedSetDocValues(name);
                if (values == null || !values.advanceExact(doc)) {
                    yield Optional.empty();
                }
                List<BytesRef> bytes = new ArrayList<>(values.docValueCount());
                for (int i = 0; i < values.docValueCount(); i++) {
                    bytes.add(BytesRef.deepCopyOf(values.lookupOrd(values.nextOrd())));
                }
                yield bytes(field, bytes);
            }
        };
    }

    private static Optional<DocValues> numbers(FieldInfo field, List<Long> numbers) {
        return Optional.of(new DocValues(field.getDocValuesType(), List.copyOf(numbers), List.of()));
    }

    private static Optional<DocValues> bytes(FieldInfo field, List<BytesRef> values) {
        return Optional.of(new DocValues(field.getDocValuesType(), List.of(), List.copyOf(values)));
    }

    static List<StoredValue> storedValues(DirectoryReader reader, int docId, String field) throws IOException {
        SegmentDoc segmentDoc = SegmentDoc.of(reader, docId);
        StoredValues stored = new StoredValues(field);
        segmentDoc.leaf().storedFields().document(segmentDoc.doc(), stored);
        return List.copyOf(stored.values.getOrDefault(field, List.of()));
    }

    /** Collects the stored values, by field name, in the order the values were added. */
    private static final class StoredValues extends StoredFieldVisitor {

        final Map<String, List<StoredValue>> values = new HashMap<>();
        /** The only field to collect; {@code null} for every field. */
        private final String only;

        StoredValues(String only) {
            this.only = only;
        }

        private void add(FieldInfo field, StoredValue value) {
            values.computeIfAbsent(field.getName(), _ -> new ArrayList<>()).add(value);
        }

        @Override
        public Status needsField(FieldInfo field) {
            return only == null || only.equals(field.getName()) ? Status.YES : Status.NO;
        }

        @Override
        public void stringField(FieldInfo field, String value) {
            add(field, new StoredValue.Text(value));
        }

        @Override
        public void binaryField(FieldInfo field, byte[] value) {
            add(field, new StoredValue.Binary(new BytesRef(value)));
        }

        @Override
        public void intField(FieldInfo field, int value) {
            add(field, new StoredValue.IntValue(value));
        }

        @Override
        public void longField(FieldInfo field, long value) {
            add(field, new StoredValue.LongValue(value));
        }

        @Override
        public void floatField(FieldInfo field, float value) {
            add(field, new StoredValue.FloatValue(value));
        }

        @Override
        public void doubleField(FieldInfo field, double value) {
            add(field, new StoredValue.DoubleValue(value));
        }
    }

    static List<TermVectorTerm> termVector(DirectoryReader reader, int docId, String field) throws IOException {
        SegmentDoc segmentDoc = SegmentDoc.of(reader, docId);
        Terms terms = segmentDoc.leaf().termVectors().get(segmentDoc.doc(), field);
        if (terms == null) {
            return List.of();
        }
        boolean hasPositions = terms.hasPositions();
        boolean hasOffsets = terms.hasOffsets();
        List<TermVectorTerm> result = new ArrayList<>();
        TermsEnum termsEnum = terms.iterator();
        PostingsEnum postings = null;
        for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
            // A term vector holds one document: its postings have exactly one entry.
            postings = termsEnum.postings(postings, PostingsEnum.ALL);
            postings.nextDoc();
            int freq = postings.freq();
            List<TermPosition> positions = hasPositions || hasOffsets
                    ? TermPosition.read(postings, freq, hasPositions, hasOffsets)
                    : List.of();
            result.add(new TermVectorTerm(BytesRef.deepCopyOf(term), freq, positions));
        }
        return List.copyOf(result);
    }
}
