package io.github.mfuerstenau.leia.core;

import static io.github.mfuerstenau.leia.core.TestIndexes.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.IndexDocument.StoredValue;
import io.github.mfuerstenau.leia.core.Queries.Operator;
import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import io.github.mfuerstenau.leia.core.SearchResults.HitField;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTest {

    private static final Similarity BM25 = Similarities.bm25(1.2f, 0.75f);

    /** Lucene's analyzers are thread-safe; this one lives as long as the tests. */
    private static final Analyzer ANALYZER = new StandardAnalyzer();

    private static final BytesRef BINARY = new BytesRef(new byte[] {(byte) 0xff, 0});

    @TempDir
    Path tmp;

    @Test
    void findsTheBestHitsFirst() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            SearchResults results = search(index, classic("quick fox"), 0, 10);

            assertThat(results.query()).isEqualTo("body:quick body:fox");
            assertThat(results.rewrittenQuery()).isEqualTo("body:quick body:fox");
            assertThat(results.totalHits()).isEqualTo(new TotalHits(3, TotalHits.Relation.EQUAL_TO));
            // "the quick brown fox" has both terms, and the short "a fox" beats the long document 3;
            // the deleted document 1 never matches.
            assertThat(results.hits()).extracting(Hit::docId).containsExactly(0, 2, 3);
            assertThat(results.hits().get(0).score())
                    .isGreaterThan(results.hits().get(1).score());
            assertThat(results.took()).isPositive();
        }
    }

    @Test
    void andRequiresEveryTerm() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            SearchResults results = index.search(
                    classic("quick fox", Operator.AND),
                    BM25,
                    Optional.empty(),
                    0,
                    10,
                    true,
                    Optional.empty(),
                    ScanProgress.NONE);

            assertThat(results.query()).isEqualTo("+body:quick +body:fox");
            assertThat(results.hits()).extracting(Hit::docId).containsExactly(0);
        }
    }

    @Test
    void pagesThroughTheHits() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Query query = classic("quick fox");
            List<Integer> all =
                    search(index, query, 0, 10).hits().stream().map(Hit::docId).toList();

            assertThat(search(index, query, 0, 2).hits()).extracting(Hit::docId).isEqualTo(all.subList(0, 2));
            SearchResults second = search(index, query, 2, 2);
            assertThat(second.hits()).extracting(Hit::docId).isEqualTo(all.subList(2, 3));
            assertThat(second.totalHits().value()).isEqualTo(3);
            assertThat(search(index, query, 5, 2).hits()).isEmpty();
        }
    }

    @Test
    void readsTheStoredFieldsAskedFor() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Query query = classic("lazy");

            assertThat(search(index, query, 0, 1).hits().getFirst().fields())
                    .containsExactly(
                            new HitField("id", List.of(new StoredValue.Text("3"))),
                            new HitField("body", List.of(new StoredValue.Text("the lazy dog and the quick cat"))),
                            new HitField("rank", List.of(new StoredValue.IntValue(7), new StoredValue.IntValue(8))));
            SearchResults onlyRank = index.search(
                    query,
                    BM25,
                    Optional.empty(),
                    0,
                    1,
                    false,
                    Optional.of(Set.of("rank", "nothing")),
                    ScanProgress.NONE);
            assertThat(onlyRank.hits().getFirst().fields())
                    .extracting(HitField::name)
                    .containsExactly("rank");
        }
    }

    @Test
    void termQueriesTakeTheTermAsItIs() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(search(index, Queries.term("key", new BytesRef("a:b c")), 0, 10)
                            .hits())
                    .extracting(Hit::docId)
                    .containsExactly(2);
            SearchResults binary = search(index, Queries.term("key", BINARY), 0, 10);
            assertThat(binary.hits()).extracting(Hit::docId).containsExactly(3);
            assertThat(binary.query()).isEqualTo("key:[ff 0]");
        }
    }

    @Test
    void countsUpToTheThresholdUnlessExact() throws Exception {
        Path dir = tmp.resolve("many");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 0; i < 1500; i++) {
                Document doc = new Document();
                doc.add(new TextField("body", "word " + (i % 3 == 0 ? "rare" : ""), Field.Store.NO));
                writer.addDocument(doc);
            }
        }
        try (ReadOnlyIndex index = ReadOnlyIndex.open(dir)) {
            Query query = classic("word", Operator.OR);

            SearchResults approximate =
                    index.search(query, BM25, Optional.empty(), 0, 10, false, Optional.empty(), ScanProgress.NONE);
            assertThat(approximate.totalHits().relation()).isEqualTo(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO);
            assertThat(approximate.totalHits().value()).isBetween(1000L, 1500L);
            assertThat(approximate.hits()).hasSize(10);

            SearchResults exact =
                    index.search(query, BM25, Optional.empty(), 0, 10, true, Optional.empty(), ScanProgress.NONE);
            assertThat(exact.totalHits()).isEqualTo(new TotalHits(1500, TotalHits.Relation.EQUAL_TO));
        }
    }

    @Test
    void reportsTheDocumentsSearchedPerSegment() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            List<Long> reported = new java.util.ArrayList<>();

            index.search(classic("quick"), BM25, Optional.empty(), 0, 10, true, Optional.empty(), reported::add);

            // Segment 0 starts at document 0, segment 1 at 2; maxDoc at the end.
            assertThat(reported).contains(0L, 2L).endsWith(5L);
        }
    }

    @Test
    void cancellingStopsTheSearch() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            AtomicInteger calls = new AtomicInteger();
            ScanProgress cancelled = _ -> {
                if (calls.incrementAndGet() > 1) {
                    throw new CancellationException("cancelled");
                }
            };

            assertThatThrownBy(() -> index.search(
                            classic("*:*"), BM25, Optional.empty(), 0, 10, true, Optional.empty(), cancelled))
                    .isInstanceOf(CancellationException.class);
            calls.set(0);
            assertThatThrownBy(() -> index.explain(classic("qu*"), BM25, 0, cancelled))
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void findsEveryMatchingDocumentWithOrWithoutTheDeletedOnes() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            DocFilter live = index.matches(classic("quick fox"), false, ScanProgress.NONE);

            assertThat(live.query()).isEqualTo("body:quick body:fox");
            assertThat(live.rewrittenQuery()).isEqualTo("body:quick body:fox");
            assertThat(live.matchCount()).isEqualTo(3);
            assertThat(matchIds(live)).containsExactly(0, 2, 3);

            DocFilter all = index.matches(classic("quick fox"), true, ScanProgress.NONE);

            assertThat(all.matchCount()).isEqualTo(4);
            assertThat(matchIds(all)).containsExactly(0, 1, 2, 3);
            // The index itself still knows what is deleted.
            assertThat(index.numDocs()).isEqualTo(4);
            assertThat(matchIds(index.matches(classic("quick fox"), false, ScanProgress.NONE)))
                    .containsExactly(0, 2, 3);
        }
    }

    @Test
    void findsMatchesOfAMoreLikeThisQuery() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            var options = new MoreLikeThisOptions(List.of("body"), 1, 1, OptionalInt.empty(), 25, false);
            DocFilter like = index.matches(Queries.moreLikeThis(0, options, ANALYZER), false, ScanProgress.NONE);

            assertThat(matchIds(like)).contains(0, 2, 3).doesNotContain(1, 4);
        }
    }

    @Test
    void cancellingStopsFindingMatches() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            AtomicInteger calls = new AtomicInteger();
            ScanProgress cancelled = _ -> {
                if (calls.incrementAndGet() > 1) {
                    throw new CancellationException("cancelled");
                }
            };

            assertThatThrownBy(() -> index.matches(classic("*:*"), true, cancelled))
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void explainsAMatchAndAMiss() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Query query = classic("quick fox");

            Explanation match = index.explain(query, BM25, 0, ScanProgress.NONE);
            assertThat(match.isMatch()).isTrue();
            assertThat(match.getValue().floatValue())
                    .isEqualTo(search(index, query, 0, 1).hits().getFirst().score());
            assertThat(match.getDetails()).hasSize(2);

            Explanation miss = index.explain(query, BM25, 4, ScanProgress.NONE);
            assertThat(miss.isMatch()).isFalse();
        }
    }

    @Test
    void rejectsBadQueriesAndPages() throws Exception {
        assertThatThrownBy(() -> classic("body:(quick", Operator.OR))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("Cannot parse");
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Query query = classic("quick");
            assertThatThrownBy(() -> search(index, query, -1, 10)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> search(index, query, 0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> search(index, query, Integer.MAX_VALUE, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> index.explain(query, BM25, 5, ScanProgress.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void searchesALucene9Index() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(testIndex("lucene9"))) {
            // "document number N" for N = 1..3; document 2 (id 2) is deleted.
            SearchResults results = search(index, classic("document", Operator.OR), 0, 10);

            assertThat(results.hits()).extracting(Hit::docId).containsExactly(0, 2);
            assertThat(results.hits().getFirst().fields())
                    .contains(new HitField("id", List.of(new StoredValue.Text("1"))));
        }
    }

    private static List<Integer> matchIds(DocFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (var match = filter.step(0, true);
                match.isPresent();
                match = filter.step(match.get().docId() + 1, true)) {
            assertThat(match.get().index()).isEqualTo(ids.size());
            ids.add(match.get().docId());
        }
        return ids;
    }

    private static Query classic(String expression) throws InvalidQueryException {
        return classic(expression, Operator.OR);
    }

    private static Query classic(String expression, Operator operator) throws InvalidQueryException {
        return Queries.classic(expression, "body", ParserOptions.defaults(operator), ClassicOptions.DEFAULTS, ANALYZER);
    }

    private static SearchResults search(ReadOnlyIndex index, Query query, int offset, int limit)
            throws IOException, InvalidQueryException {
        return index.search(query, BM25, Optional.empty(), offset, limit, true, Optional.empty(), ScanProgress.NONE);
    }

    /**
     * Five documents in two segments: "the quick brown fox", "quick fox" (deleted), then "a fox"
     * with the key "a:b c", "the lazy dog and the quick cat" with the binary key 0xff00 and two
     * stored ranks, and "nothing here".
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.addDocument(doc("0", "the quick brown fox"));
            writer.addDocument(doc("1", "quick fox"));
            writer.commit();
            Document fox = doc("2", "a fox");
            fox.add(new StringField("key", "a:b c", Field.Store.NO));
            writer.addDocument(fox);
            Document lazy = doc("3", "the lazy dog and the quick cat");
            lazy.add(new StringField("key", BINARY, Field.Store.NO));
            lazy.add(new StoredField("rank", 7));
            lazy.add(new StoredField("rank", 8));
            writer.addDocument(lazy);
            writer.addDocument(doc("4", "nothing here"));
            writer.deleteDocuments(new Term("id", "1"));
        }
        return dir;
    }

    private static Document doc(String id, String body) {
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new TextField("body", body, Field.Store.YES));
        return doc;
    }
}
