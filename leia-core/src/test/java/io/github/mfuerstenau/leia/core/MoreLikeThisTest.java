package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import io.github.mfuerstenau.leia.core.SearchResults.LikeTerm;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
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
            var options = options(List.of("body", "title"), 1, 1);
            for (Query query : List.of(
                    Queries.moreLikeThis(0, options, ANALYZER),
                    Queries.moreLikeText("apple banana red lamp", options, ANALYZER))) {
                calls.set(0);
                assertThatThrownBy(() ->
                                index.search(query, BM25, Optional.empty(), 0, 10, true, Optional.empty(), cancelled))
                        .isInstanceOf(CancellationException.class);
                calls.set(0);
                assertThatThrownBy(() -> index.explain(query, BM25, 0, cancelled))
                        .isInstanceOf(CancellationException.class);
            }
        }
    }

    @Test
    void givesTheChosenTermsWithTheirNumbersBestFirst() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            // body has a term vector: apple tf 2, banana 1, cherry 1; five documents have body.
            SearchResults vector = search(index, Queries.moreLikeThis(0, options(List.of("body"), 1, 1), ANALYZER));
            assertThat(vector.moreLikeThisTerms().orElseThrow())
                    .extracting(LikeTerm::term, LikeTerm::tf, LikeTerm::docFreq)
                    .containsExactly(tuple("apple", 2, 3), tuple("cherry", 1, 2), tuple("banana", 1, 3));
            LikeTerm apple = vector.moreLikeThisTerms().orElseThrow().getFirst();
            assertThat(apple.field()).isEqualTo("body");
            assertThat(apple.idf()).isCloseTo((float) (1 + Math.log(6.0 / 4)), within(1e-6f));
            assertThat(apple.score()).isEqualTo(apple.tf() * apple.idf());

            // title has no term vector: its stored "Red Lamp Red" is analyzed.
            SearchResults stored = search(index, Queries.moreLikeThis(0, options(List.of("title"), 1, 1), ANALYZER));
            assertThat(stored.moreLikeThisTerms().orElseThrow())
                    .extracting(LikeTerm::term, LikeTerm::tf)
                    .containsExactly(tuple("red", 2), tuple("lamp", 1));

            // Without terms, the list is empty; other queries have none.
            SearchResults none = search(index, Queries.moreLikeThis(0, options(List.of("n"), 1, 1), ANALYZER));
            assertThat(none.moreLikeThisTerms()).contains(List.of());
            assertThat(search(index, Queries.term("body", new BytesRef("apple")))
                            .moreLikeThisTerms())
                    .isEmpty();
        }
    }

    @Test
    void theNumbersAreThoseLuceneChoseTheTermsBy() throws Exception {
        Path dir = writeIndex();
        try (FSDirectory directory = FSDirectory.open(dir);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            var boost = new MoreLikeThisOptions(List.of("body", "title"), 1, 1, OptionalInt.empty(), 25, true);
            for (Query query : List.of(
                    Queries.moreLikeThis(0, boost, ANALYZER),
                    Queries.moreLikeText("apple cherry cherry red lamp lamp table", boost, ANALYZER))) {
                MoreLikeQuery like = (MoreLikeQuery) query;
                BooleanQuery built = (BooleanQuery) like.build(reader);
                List<LikeTerm> terms = LikeTerms.of(reader, like, built, () -> {});

                // The clauses come weakest first, each boosted by its score over the weakest score.
                List<BooleanClause> clauses = built.clauses();
                assertThat(terms).hasSameSizeAs(clauses).hasSizeGreaterThan(2);
                float weakest = terms.getLast().score();
                for (int i = 0; i < clauses.size(); i++) {
                    BoostQuery clause = (BoostQuery) clauses.get(i).query();
                    LikeTerm term = terms.get(terms.size() - 1 - i);
                    assertThat(((TermQuery) clause.getQuery()).getTerm())
                            .isEqualTo(new Term(term.field(), term.term()));
                    assertThat(clause.getBoost()).isEqualTo(term.score() / weakest);
                }
                assertThat(terms)
                        .isSortedAccordingTo(
                                Comparator.comparing(LikeTerm::score).reversed());
            }
        }
    }

    @Test
    void searchesForDocumentsLikeAText() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            Query query = Queries.moreLikeText(
                    "Apple apple cherry, banana and durian", options(List.of("body"), 1, 1), ANALYZER);
            SearchResults results = search(index, query);

            assertThat(results.query())
                    .isEqualTo("like(text \"Apple apple cherry, banana and durian\", fields [body], minTermFreq 1, "
                            + "minDocFreq 1, maxQueryTerms 25)");
            // "and" is in no document of body.
            assertThat(results.moreLikeThisTerms().orElseThrow())
                    .extracting(LikeTerm::term, LikeTerm::tf)
                    .containsExactly(tuple("apple", 2), tuple("durian", 1), tuple("cherry", 1), tuple("banana", 1));
            assertThat(results.hits()).extracting(Hit::docId).containsExactlyInAnyOrder(0, 1, 2, 4);

            // Each field analyzes the text, and their terms compete for the places.
            var both = new MoreLikeThisOptions(List.of("body", "title"), 1, 1, OptionalInt.empty(), 2, false);
            SearchResults fields = search(index, Queries.moreLikeText("red red durian", both, ANALYZER));
            assertThat(fields.moreLikeThisTerms().orElseThrow())
                    .extracting(LikeTerm::field, LikeTerm::term)
                    .containsExactly(tuple("title", "red"), tuple("body", "durian"));

            // An empty text has no terms, and finds nothing.
            SearchResults empty = search(index, Queries.moreLikeText("", options(List.of("body"), 1, 1), ANALYZER));
            assertThat(empty.moreLikeThisTerms()).contains(List.of());
            assertThat(empty.hits()).isEmpty();

            Hit second = results.hits().get(1);
            Explanation explanation = index.explain(query, BM25, second.docId(), ScanProgress.NONE);
            assertThat(explanation.getValue().floatValue()).isEqualTo(second.score());
        }
    }

    @Test
    void leavesOutStopWordsAndWordsByTheirLength() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            String text = "apple banana cherry durian";
            assertThat(terms(index, text, 0, OptionalInt.empty(), Set.of("apple", "Cherry")))
                    .containsExactly("durian", "cherry", "banana");
            assertThat(terms(index, text, 6, OptionalInt.empty(), Set.of()))
                    .containsExactly("durian", "cherry", "banana");
            assertThat(terms(index, text, 0, OptionalInt.of(5), Set.of())).containsExactly("apple");
            // The term vector's terms are left out by the same rules.
            var options = new MoreLikeThisOptions(
                    List.of("body"), 1, 1, OptionalInt.empty(), 25, false, 0, OptionalInt.empty(), Set.of("apple"));
            SearchResults doc = search(index, Queries.moreLikeThis(0, options, ANALYZER));
            assertThat(doc.moreLikeThisTerms().orElseThrow())
                    .extracting(LikeTerm::term)
                    .containsExactly("cherry", "banana");
            assertThat(doc.query()).endsWith("maxQueryTerms 25, stopWords [apple])");
        }
    }

    @Test
    void cutsALongTextInTheQuery() {
        String text = "a\"b\\" + "x".repeat(50) + "🙂";
        Query query = Queries.moreLikeText(text, options(List.of("body"), 1, 1), ANALYZER);
        assertThat(query.toString()).startsWith("like(text \"a\\\"b\\\\" + "x".repeat(36) + "…\", fields [body]");
        assertThat(Queries.moreLikeText("🙂".repeat(41), options(List.of("body"), 1, 1), ANALYZER)
                        .toString())
                .startsWith("like(text \"" + "🙂".repeat(40) + "…\"");
        assertThat(query).isEqualTo(Queries.moreLikeText(text, options(List.of("body"), 1, 1), ANALYZER));
        assertThat(query).isNotEqualTo(Queries.moreLikeThis(0, options(List.of("body"), 1, 1), ANALYZER));
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
        assertThatThrownBy(() -> words(-1, OptionalInt.empty())).hasMessageContaining("minWordLen must be 0 or more");
        assertThatThrownBy(() -> words(0, OptionalInt.of(0))).hasMessageContaining("maxWordLen must be 1 or more");
        assertThatThrownBy(() -> words(4, OptionalInt.of(3))).hasMessageContaining("at least minWordLen (4)");
        assertThat(words(3, OptionalInt.of(3))).isNotNull();
    }

    private static MoreLikeThisOptions words(int minWordLen, OptionalInt maxWordLen) {
        return new MoreLikeThisOptions(
                List.of("body"), 1, 1, OptionalInt.empty(), 25, false, minWordLen, maxWordLen, Set.of());
    }

    /** The terms that like-{@code text} chooses on body, best first. */
    private static List<String> terms(
            ReadOnlyIndex index, String text, int minWordLen, OptionalInt maxWordLen, Set<String> stopWords)
            throws IOException, InvalidQueryException {
        var options = new MoreLikeThisOptions(
                List.of("body"), 1, 1, OptionalInt.empty(), 25, false, minWordLen, maxWordLen, stopWords);
        return search(index, Queries.moreLikeText(text, options, ANALYZER)).moreLikeThisTerms().orElseThrow().stream()
                .map(LikeTerm::term)
                .toList();
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
