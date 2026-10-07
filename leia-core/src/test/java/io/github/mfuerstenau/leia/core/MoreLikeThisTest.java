package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoreLikeThisTest {

    private static final Similarity BM25 = Similarities.bm25(1.2f, 0.75f);

    /** Lucene's analyzers are thread-safe; this one lives as long as the tests. */
    private static final Analyzer ANALYZER = new StandardAnalyzer();

    @TempDir
    Path tmp;

    @Test
    void takesTheTermsOfTheTermVectorAndFindsTheSourceFirst() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            SearchResults results = search(index, Queries.moreLikeThis(0, options(List.of("body"), 1, 1), ANALYZER));

            assertThat(results.query())
                    .isEqualTo("like(doc 0, fields [body], minTermFreq 1, minDocFreq 1, maxQueryTerms 25)");
            assertThat(results.rewrittenQuery()).contains("body:apple", "body:banana", "body:cherry");
            // Document 3 has apple and cherry, but it is deleted.
            assertThat(results.hits()).extracting(Hit::docId).containsExactlyInAnyOrder(0, 1, 2);
            assertThat(results.hits().getFirst().docId()).isZero();
        }
    }

    @Test
    void analyzesStoredStringsWithoutATermVector() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // "Red Lamp Red": the standard analyzer lowercases, and only "red" occurs twice.
            SearchResults red = search(index, Queries.moreLikeThis(0, options(List.of("title"), 2, 1), ANALYZER));
            assertThat(red.rewrittenQuery()).isEqualTo("title:red");
            assertThat(red.hits()).extracting(Hit::docId).containsExactly(0, 1);

            // A stored number has no string to analyze, and no term vector: no terms, no hits.
            SearchResults number = search(index, Queries.moreLikeThis(0, options(List.of("n"), 1, 1), ANALYZER));
            assertThat(number.rewrittenQuery()).startsWith("MatchNoDocsQuery");
            assertThat(number.hits()).isEmpty();
        }
    }

    @Test
    void analyzesStoredStringsWithTheCallersAnalyzer() throws Exception {
        var whitespace = new AnalyzerSpec.Custom(
                List.of(), new AnalyzerSpec.Component("whitespace", Map.of()), List.of(), Optional.empty());
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex());
                Analyzer analyzer = Analyzers.build(whitespace)) {
            // "Red Lamp Red" without lowercasing: "Red" is no term of the index.
            SearchResults red = search(index, Queries.moreLikeThis(0, options(List.of("title"), 2, 1), analyzer));
            assertThat(red.rewrittenQuery()).startsWith("MatchNoDocsQuery");
        }
    }

    @Test
    void leavesOutTermsByTheirFrequenciesAndKeepsTheBest() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // Document frequencies count deleted documents: apple 3, banana 3, cherry 2.
            var onlyRare = new MoreLikeThisOptions(List.of("body"), 1, 1, OptionalInt.of(2), 25, false);
            assertThat(search(index, Queries.moreLikeThis(0, onlyRare, ANALYZER))
                            .rewrittenQuery())
                    .isEqualTo("body:cherry");

            // apple scores tf 2 × idf; the rarer cherry has tf 1.
            var best = new MoreLikeThisOptions(List.of("body"), 1, 1, OptionalInt.empty(), 1, false);
            assertThat(search(index, Queries.moreLikeThis(0, best, ANALYZER)).rewrittenQuery())
                    .isEqualTo("body:apple");

            // Lucene's defaults want terms in at least 5 documents.
            SearchResults defaults =
                    search(index, Queries.moreLikeThis(0, MoreLikeThisOptions.defaults(List.of("body")), ANALYZER));
            assertThat(defaults.query())
                    .isEqualTo("like(doc 0, fields [body], minTermFreq 2, minDocFreq 5, maxQueryTerms 25)");
            assertThat(defaults.rewrittenQuery()).startsWith("MatchNoDocsQuery");
        }
    }

    @Test
    void boostsTheTermsByTheirScores() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            var boost = new MoreLikeThisOptions(List.of("body", "title"), 1, 1, OptionalInt.of(3), 25, true);
            SearchResults results = search(index, Queries.moreLikeThis(0, boost, ANALYZER));

            assertThat(results.query())
                    .isEqualTo("like(doc 0, fields [body, title], minTermFreq 1, minDocFreq 1, maxDocFreq 3, "
                            + "maxQueryTerms 25, boost)");
            // Each term is boosted relative to the weakest one, which comes first and keeps boost 1.
            assertThat(results.rewrittenQuery())
                    .startsWith("title:lamp body:banana ")
                    .contains("(body:apple)^2.0", "(title:red)^2.4");
        }
    }

    @Test
    void aDeletedDocumentIsASourceButNoHit() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            SearchResults results = search(index, Queries.moreLikeThis(3, options(List.of("body"), 1, 1), ANALYZER));

            assertThat(results.rewrittenQuery()).contains("body:apple", "body:cherry");
            assertThat(results.hits()).extracting(Hit::docId).containsExactlyInAnyOrder(0, 1);
        }
    }

    @Test
    void explainsTheScoreOfTheSameQuery() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Query query = Queries.moreLikeThis(0, options(List.of("body"), 1, 1), ANALYZER);
            Hit second = search(index, query).hits().get(1);

            Explanation explanation = index.explain(query, BM25, second.docId(), ScanProgress.NONE);

            assertThat(explanation.isMatch()).isTrue();
            assertThat(explanation.getValue().floatValue()).isEqualTo(second.score());
        }
    }

    @Test
    void cancellingStopsBuildingTheQuery() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            AtomicInteger calls = new AtomicInteger();
            ScanProgress cancelled = _ -> {
                if (calls.incrementAndGet() > 1) {
                    throw new CancellationException("cancelled");
                }
            };
            Query query = Queries.moreLikeThis(0, options(List.of("body", "title"), 1, 1), ANALYZER);

            assertThatThrownBy(
                            () -> index.search(query, BM25, Optional.empty(), 0, 10, true, Optional.empty(), cancelled))
                    .isInstanceOf(CancellationException.class);
            calls.set(0);
            assertThatThrownBy(() -> index.explain(query, BM25, 0, cancelled))
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void rejectsOptionsOutOfRange() {
        assertThatThrownBy(() -> options(List.of(), 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> options(List.of("body", "body"), 1, 1)).hasMessageContaining("'body' is listed twice");
        assertThatThrownBy(() -> options(List.of("body"), -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> options(List.of("body"), 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MoreLikeThisOptions(List.of("body"), 1, 1, OptionalInt.of(-1), 25, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MoreLikeThisOptions(List.of("body"), 1, 1, OptionalInt.empty(), 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MoreLikeThisOptions(List.of("body"), 1, 1, OptionalInt.empty(), 1025, false))
                .hasMessageContaining("from 1 to 1024");
        assertThat(new MoreLikeThisOptions(List.of("body"), 0, 0, OptionalInt.of(0), 1024, false))
                .isNotNull();
    }

    private static MoreLikeThisOptions options(List<String> fields, int minTermFreq, int minDocFreq) {
        return new MoreLikeThisOptions(fields, minTermFreq, minDocFreq, OptionalInt.empty(), 25, false);
    }

    private static SearchResults search(ReadOnlyIndex index, Query query) throws IOException, InvalidQueryException {
        return index.search(query, BM25, Optional.empty(), 0, 10, true, Optional.empty(), ScanProgress.NONE);
    }

    /**
     * Five documents in two segments, with {@code body} (term vectors), {@code title} (stored text
     * without term vectors) and {@code n} (a stored number only). Document 3 is deleted.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("index");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            writer.addDocument(doc("0", "apple apple banana cherry", "Red Lamp Red"));
            writer.addDocument(doc("1", "apple banana", "red chair"));
            writer.commit();
            writer.addDocument(doc("2", "banana", "blue lamp"));
            writer.addDocument(doc("3", "cherry cherry apple", "lamp"));
            writer.addDocument(doc("4", "durian", "table"));
            writer.deleteDocuments(new Term("id", "3"));
        }
        return dir;
    }

    private static Document doc(String id, String body, String title) {
        FieldType withVectors = new FieldType(TextField.TYPE_STORED);
        withVectors.setStoreTermVectors(true);
        withVectors.freeze();
        Document doc = new Document();
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new Field("body", body, withVectors));
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new StoredField("n", 7));
        return doc;
    }
}
