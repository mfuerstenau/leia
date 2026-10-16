package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static io.github.mfuerstenau.leia.server.RealBackend.standard;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.commits.Commit;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitResult;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexCommitStats;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.TermStats;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.LeafPoint;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import io.github.mfuerstenau.leia.protocol.terms.FieldTerms;
import io.github.mfuerstenau.leia.protocol.terms.GetFieldTermScanResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.StartFieldTermScanParams;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import io.github.mfuerstenau.leia.protocol.terms.TopTerm;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Lucene 9 index read through {@link LeiaBackend}, as the UI reads it (AGENTS.md, hard rule 5).
 * The index, from leia-core's {@code LegacyIndexWriter}, holds "document number N" for N = 1..3 in
 * {@code body}, with N as the {@code id}; document 1 (id 2) is deleted.
 */
class Lucene9BackendTest {

    private static final String PATH = testIndex("lucene9").toString();

    private RealBackend backend;
    private OpenIndexResult opened;

    @BeforeEach
    void open() throws Exception {
        backend = RealBackend.start();
        opened = backend.call(Methods.OPEN_INDEX, new OpenIndexParams(PATH));
    }

    @AfterEach
    void close() {
        backend.close();
    }

    @Test
    void opensTheIndex() {
        assertThat(opened.path()).isEqualTo(PATH);
        assertThat(opened.mode()).isEqualTo(OpenMode.READER);
        assertThat(opened.numDocs()).hasValue(2);
        assertThat(opened.maxDoc()).hasValue(3);
        assertThat(opened.skipped()).isEmpty();
        assertThat(opened.indexes()).singleElement().satisfies(entry -> {
            assertThat(entry.path()).isEqualTo(PATH);
            ReaderInfo reader = entry.reader().orElseThrow();
            assertThat(reader.segmentsFile()).isEqualTo("segments_2");
            assertThat(reader.createdVersionMajor()).isEqualTo(9);
            assertThat(reader.minSegmentLuceneVersion())
                    .hasValueSatisfying(v -> assertThat(v).startsWith("9."));
        });
    }

    @Test
    void reportsItsStats() throws Exception {
        GetIndexStatsResult stats = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());

        assertThat(stats.path()).isEqualTo(PATH);
        assertThat(List.of(stats.segmentCount(), stats.numDocs(), stats.maxDoc()))
                .containsExactly(1, 2, 3);
        IndexCommitStats commit = stats.indexes().getFirst();
        assertThat(commit.segmentsFile()).isEqualTo("segments_2");
        assertThat(commit.generation()).isEqualTo(2);
        assertThat(commit.commitLuceneVersion()).startsWith("9.");
        assertThat(commit.userData()).containsEntry("writer", "leia LegacyIndexWriter");
        assertThat(stats.fields())
                .extracting(IndexField::name, IndexField::indexOptions, IndexField::docValuesType)
                .containsExactly(
                        tuple("id", IndexOptions.DOCS, DocValuesType.NONE),
                        tuple("body", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, DocValuesType.NONE),
                        tuple("vector", IndexOptions.NONE, DocValuesType.NONE),
                        tuple("number", IndexOptions.NONE, DocValuesType.NONE));
        assertThat(stats.fields().get(2))
                .extracting(IndexField::vectorDimension, IndexField::vectorEncoding, IndexField::vectorSimilarity)
                .containsExactly(3, Optional.of(VectorEncoding.FLOAT32), Optional.of(VectorSimilarity.EUCLIDEAN));
        // Postings statistics count the deleted document too.
        assertThat(stats.fields().getFirst().terms().map(TermStats::docCount)).hasValue(3);
    }

    @Test
    void searchesTheNearestVectors() throws Exception {
        for (boolean exact : List.of(false, true)) {
            SearchQuery query = new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new KnnQuery(
                            "vector",
                            OptionalInt.empty(),
                            Optional.of(new KnnVector(VectorEncoding.FLOAT32, List.of(3f, 0f, 1f), List.of())),
                            exact ? OptionalInt.empty() : OptionalInt.of(5),
                            exact,
                            Optional.empty())));

            // [N, 0, 1] for N = 1..3, without the deleted 2.
            assertThat(backend.search(query, 10).hits()).extracting(Hit::docId).containsExactly(2, 0);
        }
    }

    @Test
    void readsTheCommit() throws Exception {
        List<Commit> commits = backend.call(Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.empty()))
                .commits();
        // The default deletion policy kept only the last commit.
        assertThat(commits).singleElement().satisfies(commit -> {
            assertThat(commit.generation()).isEqualTo(2);
            assertThat(commit.segmentsFile()).isEqualTo("segments_2");
            assertThat(commit.current()).isTrue();
            assertThat(commit.luceneVersion()).startsWith("9.");
            assertThat(commit.userData()).containsEntry("writer", "leia LegacyIndexWriter");
        });

        GetCommitResult details = backend.call(Methods.GET_COMMIT, new GetCommitParams(2, OptionalInt.empty()));

        assertThat(details.commit()).isEqualTo(commits.getFirst());
        assertThat(details.segments().stream().mapToInt(Segment::maxDoc).sum()).isEqualTo(3);
        assertThat(details.segments().stream().mapToInt(Segment::delCount).sum())
                .isEqualTo(1);
        assertThat(details.segments())
                .allSatisfy(segment -> assertThat(segment.codec())
                        .hasValueSatisfying(codec -> assertThat(codec).startsWith("Lucene9")));
        // Read through the backward codecs (hard rule 5).
        assertThat(details.segments())
                .allSatisfy(segment -> assertThat(segment.fieldFormats())
                        .extracting(FieldFormats::field, FieldFormats::postings, FieldFormats::knnVectors)
                        .containsExactly(
                                tuple("body", Optional.of("Lucene912"), Optional.empty()),
                                tuple("id", Optional.of("Lucene912"), Optional.empty()),
                                tuple("number", Optional.empty(), Optional.empty()),
                                tuple("vector", Optional.empty(), Optional.of("Lucene99HnswVectorsFormat"))));
        assertThat(details.files()).anySatisfy(file -> {
            assertThat(file.name()).isEqualTo("segments_2");
            assertThat(file.segment()).isEmpty();
            assertThat(file.size()).isPresent();
        });
        backend.fails(Methods.GET_COMMIT, new GetCommitParams(1, OptionalInt.empty()), ErrorCode.COMMIT_NOT_FOUND);
    }

    @Test
    void scansTheTermsOfAField() throws Exception {
        FieldTerms terms = backend.job(
                Methods.START_FIELD_TERM_SCAN,
                new StartFieldTermScanParams("body", 10),
                Methods.GET_FIELD_TERM_SCAN,
                GetFieldTermScanResult::terms);

        assertThat(terms.uniqueTerms()).isEqualTo(5);
        assertThat(terms.topTerms())
                .extracting(TopTerm::term, TopTerm::docFreq)
                .startsWith(tuple("document", 3), tuple("number", 3));
    }

    @Test
    void findsAutoStopWords() throws Exception {
        // 2 live documents: half of them is 1, and the deleted one counts, as in Lucene (ADR 0039).
        AutoStopWords stopWords = backend.job(
                Methods.START_AUTO_STOP_WORDS,
                new StartAutoStopWordsParams(List.of(), OptionalInt.empty(), OptionalDouble.of(0.5)),
                Methods.GET_AUTO_STOP_WORDS,
                GetAutoStopWordsResult::stopWords);

        assertThat(stopWords)
                .isEqualTo(new AutoStopWords(
                        1,
                        List.of(
                                new FieldStopWords("body", List.of("document", "number")),
                                new FieldStopWords("id", List.of()))));
    }

    @Test
    void readsADeletedDocument() throws Exception {
        GetDocumentResult document = backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(1));

        StoredValue body = new StoredValue(StoredType.STRING, "document number 2", false);
        assertThat(document)
                .isEqualTo(new GetDocumentResult(
                        1,
                        true,
                        List.of(
                                new DocumentField(
                                        "id",
                                        OptionalLong.empty(),
                                        List.of(new StoredValue(StoredType.STRING, "2", false)),
                                        Optional.empty(),
                                        false,
                                        Optional.empty()),
                                new DocumentField(
                                        "body",
                                        OptionalLong.of(3),
                                        List.of(body),
                                        Optional.empty(),
                                        false,
                                        Optional.empty()),
                                new DocumentField(
                                        "vector",
                                        OptionalLong.empty(),
                                        List.of(),
                                        Optional.empty(),
                                        false,
                                        Optional.of(
                                                new KnnVector(VectorEncoding.FLOAT32, List.of(2f, 0f, 1f), List.of()))),
                                // A point only, which getDocument doesn't read (ADR 0041).
                                new DocumentField(
                                        "number",
                                        OptionalLong.empty(),
                                        List.of(),
                                        Optional.empty(),
                                        false,
                                        Optional.empty()))));
        assertThat(backend.call(Methods.GET_TERM_VECTOR, new GetTermVectorParams(1, "body"))
                        .terms())
                .isEmpty();
        assertThat(backend.call(Methods.GET_STORED_VALUE, new GetStoredValueParams(1, "body", 0))
                        .value())
                .isEqualTo(body);
        backend.fails(Methods.GET_STORED_VALUE, new GetStoredValueParams(1, "body", 1), ErrorCode.VALUE_NOT_FOUND);
        backend.fails(Methods.GET_DOCUMENT, new GetDocumentParams(3), ErrorCode.DOC_NOT_FOUND);
    }

    @Test
    void browsesTheTermsAndPostings() throws Exception {
        GetTermsResult first = backend.call(Methods.GET_TERMS, new GetTermsParams("body", Optional.of(text("2")), 2));

        assertThat(first.terms()).containsExactly(new TermEntry("2", false, 1, 1), new TermEntry("3", false, 1, 1));
        assertThat(first.next()).hasValue(text("document"));

        GetPostingsResult postings = backend.call(
                Methods.GET_POSTINGS, new GetPostingsParams("body", text("number"), 1, 10, Optional.empty()));

        assertThat(postings)
                .isEqualTo(new GetPostingsResult(
                        3,
                        3,
                        List.of(
                                new Posting(1, true, OptionalInt.of(1), Optional.empty()),
                                new Posting(2, false, OptionalInt.of(1), Optional.empty())),
                        OptionalInt.empty()));
        // Offsets are not indexed.
        assertThat(backend.call(Methods.GET_POSITIONS, new GetPositionsParams(2, "body", text("number")))
                        .positions())
                .containsExactly(new TermPosition(
                        OptionalInt.of(1), OptionalInt.empty(), OptionalInt.empty(), Optional.empty()));
        backend.fails(
                Methods.GET_POSTINGS,
                new GetPostingsParams("body", text("nothing"), 0, 10, Optional.empty()),
                ErrorCode.TERM_NOT_FOUND);
    }

    @Test
    void searchesAndExplains() throws Exception {
        SearchQuery query = classic("number AND (1 OR 2 OR 3)");
        Similarity tfIdf = new Similarity(Optional.empty(), Optional.of(new ClassicSimilarity()), Optional.empty());

        SearchResults results = backend.search(new StartSearchParams(
                query, tfIdf, List.of(), 0, 10, true, Optional.of(List.of("id")), Optional.empty()));

        assertThat(results.query()).isEqualTo("+body:number +(body:1 body:2 body:3)");
        assertThat(results.totalHits()).isEqualTo(2);
        assertThat(results.totalHitsRelation()).isEqualTo(TotalHitsRelation.EQUAL_TO);
        assertThat(results.hits())
                .extracting(
                        Hit::docId,
                        hit -> hit.fields().getFirst().values().getFirst().value())
                .containsExactly(tuple(0, "1"), tuple(2, "3"));
        assertThat(results.hits())
                .allSatisfy(hit -> assertThat(hit.sortValues()).isEmpty());

        Explanation explanation = backend.explain(query, tfIdf, 2);

        assertThat(explanation.match()).isTrue();
        assertThat(explanation.details().getFirst().description()).contains("ClassicSimilarity");
        assertThat(Float.parseFloat(explanation.value()))
                .isEqualTo(results.hits().get(1).score());

        backend.fails(Methods.START_SEARCH, params(classic("body:(number")), ErrorCode.INVALID_QUERY);
    }

    @Test
    void searchesWithTheStandardParserAndSorts() throws Exception {
        SearchResults results = backend.search(standard("number (1 OR 3)", QueryOperator.AND, List.of()), 10);

        assertThat(results.query()).isEqualTo("+body:number +(body:1 body:3)");
        assertThat(results.totalHits()).isEqualTo(2);
        // body has no points, so it can't be read as ints.
        backend.fails(
                Methods.START_SEARCH,
                params(standard("number (1 OR 3)", QueryOperator.AND, List.of(new PointField("body", PointType.INT)))),
                ErrorCode.INVALID_PARAMS);
        // The index has no doc values to sort by.
        SortKey byId = new SortKey(
                Optional.of(new FieldSort("id", SortType.STRING, false, SortSelector.MIN, SortMissing.DEFAULT)),
                Optional.empty(),
                Optional.empty());
        assertThat(backend.fails(
                                Methods.START_SEARCH,
                                new StartSearchParams(
                                        classic("number"),
                                        BM25,
                                        List.of(byId),
                                        0,
                                        10,
                                        false,
                                        Optional.empty(),
                                        Optional.empty()),
                                ErrorCode.INVALID_PARAMS)
                        .getMessage())
                .contains("no doc values");

        // The document id and the score need no doc values (ADR 0025).
        SearchResults sorted = backend.search(new StartSearchParams(
                classic("number"),
                BM25,
                List.of(
                        new SortKey(Optional.empty(), Optional.empty(), Optional.of(new DocSort(true))),
                        new SortKey(Optional.empty(), Optional.of(new ScoreSort(false)), Optional.empty())),
                0,
                10,
                true,
                Optional.empty(),
                Optional.empty()));

        assertThat(sorted.hits()).extracting(Hit::docId).containsExactly(2, 0);
        Hit first = sorted.hits().getFirst();
        assertThat(first.sortValues().get(0).number()).hasValue("2");
        assertThat(first.sortValues().get(1).number().map(Float::parseFloat)).hasValue(first.score());
    }

    @Test
    void rebuildsTheIndexedTermsOfADeletedDocument() throws Exception {
        ReconstructResult result = backend.reconstruct(1, Optional.of(List.of("body", "id")));

        assertThat(result.fields()).hasSize(2);
        ReconstructedField body = result.fields().get(0);
        assertThat(body.source()).isEqualTo(ReconstructSource.POSTINGS);
        assertThat(body.indexOptions()).isEqualTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        assertThat(body.terms())
                .extracting(
                        term -> term.term(),
                        term -> term.positions().stream()
                                .map(p -> p.position().orElseThrow())
                                .toList())
                .containsExactly(tuple("2", List.of(2)), tuple("document", List.of(0)), tuple("number", List.of(1)));
        ReconstructedField id = result.fields().get(1);
        assertThat(id.indexOptions()).isEqualTo(IndexOptions.DOCS);
        assertThat(id.terms())
                .singleElement()
                .satisfies(term -> assertThat(term.term()).isEqualTo("2"));
        backend.fails(
                Methods.START_RECONSTRUCT,
                new StartReconstructParams(0, Optional.of(List.of("nope"))),
                ErrorCode.FIELD_NOT_FOUND);
    }

    @Test
    void readsThePointTree() throws Exception {
        GetPointTreeResult tree = backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams("number"));

        assertThat(tree.size()).isEqualTo(3);
        assertThat(tree.min()).contains(new PointValue(List.of("8000000a")));
        assertThat(tree.max()).contains(new PointValue(List.of("8000001e")));
        GetPointCellResult root = backend.call(Methods.GET_POINT_CELL, new GetPointCellParams("number", 0, List.of()));
        assertThat(root.points())
                .extracting(LeafPoint::docId, LeafPoint::deleted)
                .containsExactly(tuple(0, false), tuple(1, true), tuple(2, false));
    }

    @Test
    void rebuildsThePointsOfADeletedDocument() throws Exception {
        ReconstructedField number =
                backend.reconstruct(1, Optional.of(List.of("number"))).fields().getFirst();

        // 20 as a sortable int.
        assertThat(number.indexOptions()).isEqualTo(IndexOptions.NONE);
        assertThat(number.points()).containsExactly(new PointValue(List.of("80000014")));
    }

    private static StartSearchParams params(SearchQuery query) {
        return new StartSearchParams(query, BM25, List.of(), 0, 10, false, Optional.empty(), Optional.empty());
    }

    private static BytesValue text(String value) {
        return new BytesValue(value, false);
    }
}
