package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.core.DocFilter;
import io.github.mfuerstenau.leia.core.IndexDocument;
import io.github.mfuerstenau.leia.core.StoredScan;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Terms;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.LongConsumer;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;

/**
 * Reading a runtime's terms page by page for an export, its stored values for a scan, and its
 * postings through a filter.
 */
class RuntimeResultsTest {

    @Test
    void pagesPassEveryTermInOrderFromEachPagesNext() throws Exception {
        PagedTerms terms = new PagedTerms(List.of("a", "b", "c", "d", "e"));
        List<String> exported = new ArrayList<>();

        RuntimeResults.pages(terms, "f", 2)
                .forEachTerm((bytes, offset, length, docFreq) ->
                        exported.add(new String(bytes, offset, length, StandardCharsets.UTF_8) + docFreq));

        assertThat(exported).containsExactly("a1", "b2", "c3", "d4", "e5");
        assertThat(terms.froms).containsExactly("", "c", "e");
    }

    @Test
    void anEmptyFieldGivesNothing() throws Exception {
        PagedTerms terms = new PagedTerms(List.of());
        List<String> exported = new ArrayList<>();

        RuntimeResults.pages(terms, "f", 2).forEachTerm((_, _, _, _) -> exported.add("term"));

        assertThat(exported).isEmpty();
        assertThat(terms.froms).containsExactly("");
    }

    @Test
    void storedValuesKeepTheirOrderAndNumbersTheirType() {
        Map<String, List<RuntimeIndex.StoredValue>> values = new LinkedHashMap<>();
        values.put(
                "z",
                List.of(
                        new RuntimeIndex.StoredValue.Numeric(1),
                        new RuntimeIndex.StoredValue.Numeric(2L),
                        new RuntimeIndex.StoredValue.Numeric(3f),
                        new RuntimeIndex.StoredValue.Numeric(4d)));
        values.put(
                "a",
                List.of(new RuntimeIndex.StoredValue.Text("text"), new RuntimeIndex.StoredValue.Binary(new byte[] {1, 2
                })));

        StoredScan.Document document = RuntimeResults.storedDocument(new Documents.StoredDocument(5, true, values));

        assertThat(document.docId()).isEqualTo(5);
        assertThat(document.deleted()).isTrue();
        assertThat(document.values().keySet()).containsExactly("z", "a");
        assertThat(document.values().get("z"))
                .containsExactly(
                        new IndexDocument.StoredValue.IntValue(1),
                        new IndexDocument.StoredValue.LongValue(2L),
                        new IndexDocument.StoredValue.FloatValue(3f),
                        new IndexDocument.StoredValue.DoubleValue(4d));
        assertThat(document.values().get("a"))
                .containsExactly(
                        new IndexDocument.StoredValue.Text("text"),
                        new IndexDocument.StoredValue.Binary(new BytesRef(new byte[] {1, 2})));
    }

    @Test
    void onlyMatchesLeapfrogsThroughThePagesToTheNextSharedDocument() throws Exception {
        // Postings in every document from 0 to 19; the filter has 2, 3, 9, 15 and 30.
        PagedPostings postings = new PagedPostings(20);
        DocFilter filter = filter(40, 2, 3, 9, 15, 30);

        Terms.PostingsPage page = RuntimeResults.matchingPostings(postings, "f", new byte[0], 0, 2, filter);

        assertThat(page.postings()).extracting(Terms.Posting::docId).containsExactly(2, 3);
        assertThat(page.nextDoc()).hasValue(9);
        assertThat(page.docFreq()).isEqualTo(20);
        // Each read starts at the filter's next match after the page before.
        assertThat(postings.froms).containsExactly(2, 9);

        Terms.PostingsPage next = RuntimeResults.matchingPostings(postings, "f", new byte[0], 9, 2, filter);

        assertThat(next.postings()).extracting(Terms.Posting::docId).containsExactly(9, 15);
        assertThat(next.nextDoc()).isEmpty();
        assertThat(postings.froms).containsExactly(2, 9, 9, 15, 30);
    }

    @Test
    void onlyMatchesSkipsPostingsOutsideTheFilter() throws Exception {
        // Postings in the even documents only.
        PagedPostings postings = new PagedPostings(20, 2);
        DocFilter filter = filter(20, 1, 3, 4, 5, 8, 12);

        Terms.PostingsPage page = RuntimeResults.matchingPostings(postings, "f", new byte[0], 0, 2, filter);

        assertThat(page.postings()).extracting(Terms.Posting::docId).containsExactly(4, 8);
        assertThat(page.nextDoc()).hasValue(12);
    }

    @Test
    void onlyMatchesWithNoMatchLeftGivesTheStatisticsAlone() throws Exception {
        PagedPostings postings = new PagedPostings(20);

        Terms.PostingsPage page = RuntimeResults.matchingPostings(postings, "f", new byte[0], 6, 5, filter(20, 1, 5));

        assertThat(page.postings()).isEmpty();
        assertThat(page.nextDoc()).isEmpty();
        assertThat(page.docFreq()).isEqualTo(20);
        assertThat(page.totalTermFreq()).isEqualTo(20);
    }

    private static DocFilter filter(int maxDoc, int... docIds) {
        BitSet docs = new BitSet();
        for (int docId : docIds) {
            docs.set(docId);
        }
        return DocFilter.of(docs, maxDoc, "q", "q", Duration.ZERO);
    }

    /** Postings of a term in every {@code step}th document below {@code end}; records each page's start. */
    private static final class PagedPostings extends PagedTerms {

        private final int end;
        private final int step;
        final List<Integer> froms = new ArrayList<>();

        PagedPostings(int end) {
            this(end, 1);
        }

        PagedPostings(int end, int step) {
            super(List.of());
            this.end = end;
            this.step = step;
        }

        @Override
        public PostingsPage postings(String field, byte[] term, int fromDoc, int limit) {
            froms.add(fromDoc);
            List<Posting> page = new ArrayList<>();
            int doc = (fromDoc + step - 1) / step * step;
            for (; doc < end && page.size() < limit; doc += step) {
                page.add(new Posting(doc, 1, false));
            }
            int docFreq = (end + step - 1) / step;
            return new PostingsPage(docFreq, docFreq, page, doc < end ? OptionalInt.of(doc) : OptionalInt.empty());
        }
    }

    /** Pages of sorted terms, the docFreq of each its position from 1; records each page's start. */
    private static class PagedTerms implements Terms {

        private final List<String> terms;
        final List<String> froms = new ArrayList<>();

        PagedTerms(List<String> terms) {
            this.terms = terms;
        }

        @Override
        public TermsPage terms(String field, Optional<byte[]> from, int limit) {
            String start =
                    from.map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("");
            froms.add(start);
            int first = 0;
            while (first < terms.size() && terms.get(first).compareTo(start) < 0) {
                first++;
            }
            List<TermEntry> page = new ArrayList<>();
            for (int i = first; i < Math.min(first + limit, terms.size()); i++) {
                page.add(new TermEntry(terms.get(i).getBytes(StandardCharsets.UTF_8), i + 1, i + 1));
            }
            int next = first + limit;
            return new TermsPage(
                    page,
                    next < terms.size()
                            ? Optional.of(terms.get(next).getBytes(StandardCharsets.UTF_8))
                            : Optional.empty());
        }

        @Override
        public PostingsPage postings(String field, byte[] term, int fromDoc, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<List<Documents.Position>> positions(int docId, String field, byte[] term) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FieldTerms topTerms(String field, int topN, LongConsumer progress) {
            throw new UnsupportedOperationException();
        }
    }
}
