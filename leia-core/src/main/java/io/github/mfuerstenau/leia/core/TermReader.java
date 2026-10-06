package io.github.mfuerstenau.leia.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * Pages through the terms of a field and the postings of a term (ADR 0007). Every call seeks
 * afresh, so nothing is kept between calls, and reads at most one page.
 */
final class TermReader {

    private TermReader() {}

    static TermPage terms(DirectoryReader reader, String field, Optional<BytesRef> from, int limit) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return new TermPage(List.of(), Optional.empty());
        }
        TermsEnum termsEnum = terms.iterator();
        BytesRef term;
        if (from.isPresent()) {
            term = termsEnum.seekCeil(from.get()) == TermsEnum.SeekStatus.END ? null : termsEnum.term();
        } else {
            term = termsEnum.next();
        }
        List<TermPage.Entry> entries = new ArrayList<>();
        while (term != null && entries.size() < limit) {
            entries.add(new TermPage.Entry(BytesRef.deepCopyOf(term), termsEnum.docFreq(), termsEnum.totalTermFreq()));
            term = termsEnum.next();
        }
        return new TermPage(List.copyOf(entries), Optional.ofNullable(term).map(BytesRef::deepCopyOf));
    }

    static Optional<PostingsPage> postings(
            DirectoryReader reader, FieldInfo field, BytesRef term, int fromDoc, int limit) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field.getName());
        if (terms == null) {
            return Optional.empty();
        }
        TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(term)) {
            return Optional.empty();
        }
        boolean hasFreqs = field.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS) >= 0;
        PostingsEnum postings = termsEnum.postings(null, hasFreqs ? PostingsEnum.FREQS : PostingsEnum.NONE);
        Bits liveDocs = MultiBits.getLiveDocs(reader);
        List<PostingsPage.Posting> page = new ArrayList<>();
        // Advancing past the last document is not allowed.
        int doc = fromDoc < reader.maxDoc() ? postings.advance(fromDoc) : DocIdSetIterator.NO_MORE_DOCS;
        while (doc != DocIdSetIterator.NO_MORE_DOCS && page.size() < limit) {
            page.add(new PostingsPage.Posting(
                    doc,
                    liveDocs != null && !liveDocs.get(doc),
                    hasFreqs ? OptionalInt.of(postings.freq()) : OptionalInt.empty()));
            doc = postings.nextDoc();
        }
        return Optional.of(new PostingsPage(
                termsEnum.docFreq(),
                termsEnum.totalTermFreq(),
                List.copyOf(page),
                doc == DocIdSetIterator.NO_MORE_DOCS ? OptionalInt.empty() : OptionalInt.of(doc)));
    }

    /** Read from the document's segment, like the rest of a document (ADR 0006). */
    static Optional<List<TermPosition>> positions(DirectoryReader reader, int docId, String field, BytesRef term)
            throws IOException {
        SegmentDoc segmentDoc = SegmentDoc.of(reader, docId);
        Terms terms = segmentDoc.leaf().terms(field);
        if (terms == null) {
            return Optional.empty();
        }
        TermsEnum termsEnum = terms.iterator();
        if (!termsEnum.seekExact(term)) {
            return Optional.empty();
        }
        PostingsEnum postings = termsEnum.postings(null, PostingsEnum.ALL);
        if (postings.advance(segmentDoc.doc()) != segmentDoc.doc()) {
            return Optional.empty();
        }
        // Offsets are only indexed together with positions.
        return Optional.of(
                terms.hasPositions()
                        ? TermPosition.read(postings, postings.freq(), true, terms.hasOffsets())
                        : List.of());
    }
}
