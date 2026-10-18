package io.github.mfuerstenau.leia.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.core.IndexDocument;
import io.github.mfuerstenau.leia.core.StoredScan;
import io.github.mfuerstenau.leia.runtime.Documents;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.Terms;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;

/** Reading a runtime's terms page by page for an export, and its stored values for a scan. */
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

    /** Pages of sorted terms, the docFreq of each its position from 1; records each page's start. */
    private static final class PagedTerms implements Terms {

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
