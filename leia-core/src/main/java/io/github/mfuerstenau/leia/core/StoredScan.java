package io.github.mfuerstenau.leia.core;

import io.github.mfuerstenau.leia.core.DocumentReader.StoredValues;
import io.github.mfuerstenau.leia.core.IndexDocument.StoredValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.util.Bits;

/**
 * The stored values of documents, as the stored scan of the server reads them through a runtime (ADR
 * 0027, ADR 0055), and the UTF-8 offsets of positions in a value, which the highlights use (ADR
 * 0042). The scan itself is the server's.
 */
public final class StoredScan {

    private StoredScan() {}

    /**
     * Where a match is in a value.
     *
     * @param start the UTF-8 byte offset of the match's first byte
     * @param end the UTF-8 byte offset after the match's last byte; {@code start} for an empty match
     */
    public record Span(int start, int end) {}

    /**
     * A document's stored values, as a {@link StoredSource} reads them.
     *
     * @param values the values by field, each field's values in stored order
     */
    public record Document(int docId, boolean deleted, Map<String, List<StoredValue>> values) {}

    /**
     * The stored values of an index, read a range of document ids at a time (ADR 0055). Its creator
     * decides which fields it reads and whether it reads deleted documents.
     */
    public interface StoredSource {

        /** The documents of the index, deleted ones included. */
        int maxDoc();

        /**
         * The documents from {@code fromDoc} up to {@code toDoc} (exclusive) that the source reads,
         * in document id order.
         */
        List<Document> read(int fromDoc, int toDoc) throws IOException;
    }

    /**
     * The stored values of {@code reader} in {@code fields} (all if empty): of the live documents,
     * and of the deleted ones too if {@code includeDeleted}. It keeps each leaf's stored fields across
     * reads, so a scan opens them once.
     */
    static StoredSource source(IndexReader reader, Optional<Set<String>> fields, boolean includeDeleted) {
        List<LeafReaderContext> leaves = reader.leaves();
        StoredFields[] storedFields = new StoredFields[leaves.size()];
        return new StoredSource() {
            @Override
            public int maxDoc() {
                return reader.maxDoc();
            }

            @Override
            public List<Document> read(int fromDoc, int toDoc) throws IOException {
                List<Document> documents = new ArrayList<>();
                int leafIndex = ReaderUtil.subIndex(fromDoc, leaves);
                for (int docId = fromDoc; docId < toDoc; docId++) {
                    while (docId
                            >= leaves.get(leafIndex).docBase
                                    + leaves.get(leafIndex).reader().maxDoc()) {
                        leafIndex++;
                    }
                    LeafReaderContext context = leaves.get(leafIndex);
                    LeafReader leaf = context.reader();
                    int doc = docId - context.docBase;
                    Bits liveDocs = leaf.getLiveDocs();
                    boolean deleted = liveDocs != null && !liveDocs.get(doc);
                    if (deleted && !includeDeleted) {
                        continue;
                    }
                    if (storedFields[leafIndex] == null) {
                        storedFields[leafIndex] = leaf.storedFields();
                    }
                    StoredValues values = new StoredValues(
                            name -> fields.isEmpty() || fields.get().contains(name));
                    storedFields[leafIndex].document(doc, values);
                    documents.add(new Document(docId, deleted, values.values));
                }
                return documents;
            }
        };
    }

    /**
     * Turns UTF-16 positions in a string into UTF-8 byte offsets, as {@code UnicodeUtil} encodes
     * the string: a surrogate pair is 4 bytes, a lone surrogate 3 (U+FFFD). Positions must be asked
     * for in ascending order. A position between the two halves of a pair counts the whole pair.
     */
    static final class Utf8Offsets {

        private final String text;
        private int chars;
        private int bytes;

        Utf8Offsets(String text) {
            this.text = text;
        }

        int at(int position) {
            if (position < chars) {
                if (position == chars - 1 && Character.isLowSurrogate(text.charAt(position))) {
                    // Between the halves of the pair just counted.
                    return bytes;
                }
                throw new IllegalArgumentException("Position " + position + " is before " + chars);
            }
            while (chars < position) {
                char c = text.charAt(chars);
                if (c < 0x80) {
                    bytes += 1;
                } else if (c < 0x800) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(c)
                        && chars + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(chars + 1))) {
                    bytes += 4;
                    chars++;
                } else {
                    bytes += 3;
                }
                chars++;
            }
            return bytes;
        }
    }
}
