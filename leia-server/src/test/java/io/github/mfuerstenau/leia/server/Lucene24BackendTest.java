package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static io.github.mfuerstenau.leia.server.RealBackend.standard;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.index.ReaderInfo;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitField;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetTermsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.TermEntry;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Lucene 2.4 index read through {@link LeiaBackend} with the relocated Lucene 3.6, next to Lucene
 * 10 in one JVM (ADR 0044). The index, from leia-lucene3's {@code Lucene24IndexWriter}, holds
 * "document number N of the old index" for N = 1..3 in {@code body}, with N as the {@code id} and
 * "bytesN" in the binary {@code raw}; document 1 (id 2) is deleted.
 */
class Lucene24BackendTest {

    private static final String PATH = testIndex("lucene24").toString();

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
    void opensTheIndexWithLucene3() {
        assertThat(opened.path()).isEqualTo(PATH);
        assertThat(opened.mode()).isEqualTo(OpenMode.READER);
        assertThat(opened.numDocs()).hasValue(2);
        assertThat(opened.maxDoc()).hasValue(3);
        assertThat(opened.luceneRuntime()).startsWith("3.6");
        assertThat(opened.features())
                .contains(
                        Feature.DOCUMENTS,
                        Feature.STORED_SCAN,
                        Feature.TERMS,
                        Feature.TOP_TERMS,
                        Feature.EXPORT_TERMS,
                        Feature.CLASSIC_SEARCH,
                        Feature.TERM_SEARCH,
                        Feature.FILTER,
                        Feature.EXPLAIN,
                        Feature.COMMITS,
                        Feature.CHECK_INDEX)
                .doesNotContain(Feature.SEARCH, Feature.POINTS, Feature.AUTO_STOP_WORDS, Feature.CHECK_LEVELS);
        ReaderInfo reader = opened.indexes().getFirst().reader().orElseThrow();
        assertThat(reader.createdVersionMajor()).isEqualTo(2);
        assertThat(reader.minSegmentLuceneVersion()).contains("2.x");
    }

    @Test
    void aLucene10IndexHasEveryFeature() throws Exception {
        OpenIndexResult lucene9 = backend.call(
                Methods.OPEN_INDEX, new OpenIndexParams(testIndex("lucene9").toString()));
        assertThat(lucene9.features()).containsExactlyInAnyOrderElementsOf(Feature.all());
        assertThat(lucene9.luceneRuntime()).startsWith("10.");
    }

    @Test
    void reportsItsStats() throws Exception {
        GetIndexStatsResult stats = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());
        assertThat(List.of(stats.segmentCount(), stats.numDocs(), stats.maxDoc()))
                .containsExactly(1, 2, 3);
        assertThat(stats.indexes().getFirst().commitLuceneVersion()).isEqualTo("2.x");
        assertThat(stats.fields()).extracting(IndexField::name).containsExactly("id", "body", "raw");
        assertThat(stats.fields())
                .extracting(IndexField::indexOptions)
                .containsExactly(
                        IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                        IndexOptions.DOCS_AND_FREQS_AND_POSITIONS,
                        IndexOptions.NONE);
    }

    @Test
    void readsDocuments() throws Exception {
        GetDocumentResult doc = backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(0));
        assertThat(doc.deleted()).isFalse();
        assertThat(doc.fields().get(0).stored()).containsExactly(new StoredValue(StoredType.STRING, "1", false));
        assertThat(doc.fields().get(2).stored()).containsExactly(new StoredValue(StoredType.BINARY, "bytes1", false));
        assertThat(doc.fields().get(1).hasTermVector()).isTrue();
        assertThat(backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(1)).deleted())
                .isTrue();
        backend.fails(Methods.GET_DOCUMENT, new GetDocumentParams(3), ErrorCode.DOC_NOT_FOUND);

        assertThat(backend.call(Methods.GET_STORED_VALUE, new GetStoredValueParams(2, "body", 0))
                        .value()
                        .value())
                .isEqualTo("document number 3 of the old index");
        assertThat(backend.call(Methods.GET_TERM_VECTOR, new GetTermVectorParams(0, "body"))
                        .terms())
                .extracting(TermVectorTerm::term)
                .containsExactly("1", "document", "index", "number", "old");
    }

    @Test
    void readsTermsAndPostings() throws Exception {
        GetTermsResult terms = backend.call(Methods.GET_TERMS, new GetTermsParams("id", Optional.empty(), 2));
        assertThat(terms.terms()).containsExactly(new TermEntry("1", false, 1, 1), new TermEntry("2", false, 1, 0));
        assertThat(terms.next()).contains(new BytesValue("3", false));

        GetPostingsResult postings = backend.call(
                Methods.GET_POSTINGS,
                new GetPostingsParams("body", new BytesValue("number", false), 0, 10, Optional.empty()));
        assertThat(postings.postings())
                .containsExactly(
                        new Posting(0, false, OptionalInt.of(1), Optional.empty()),
                        new Posting(2, false, OptionalInt.of(1), Optional.empty()));
        backend.fails(
                Methods.GET_POSTINGS,
                new GetPostingsParams("body", new BytesValue("nothing", false), 0, 10, Optional.empty()),
                ErrorCode.TERM_NOT_FOUND);
    }

    @Test
    void searchesWithTheClassicParser() throws Exception {
        SearchResults results = backend.search(new StartSearchParams(
                classic("number AND (1 OR 3)"),
                BM25,
                List.of(),
                0,
                10,
                true,
                Optional.of(List.of("id")),
                Optional.empty()));
        assertThat(results.totalHits()).isEqualTo(2);
        assertThat(results.hits()).extracting(Hit::docId).containsExactlyInAnyOrder(0, 2);
        assertThat(results.hits().getFirst().fields())
                .extracting(HitField::name)
                .containsExactly("id");

        backend.fails(Methods.START_SEARCH, params(classic("body:(number")), ErrorCode.INVALID_QUERY);
        backend.fails(
                Methods.START_SEARCH, params(standard("number", QueryOperator.OR, List.of())), ErrorCode.NOT_SUPPORTED);
    }

    @Test
    void searchesATerm() throws Exception {
        RuntimeSearches.searchesATerm(backend, opened.maxDoc().orElseThrow(), "number", true);
    }

    @Test
    void refusesWhatLucene3Lacks() throws Exception {
        backend.fails(Methods.GET_POINT_TREE, new GetPointTreeParams("id"), ErrorCode.NOT_SUPPORTED);
        backend.fails(
                Methods.START_RECONSTRUCT, new StartReconstructParams(0, Optional.empty()), ErrorCode.NOT_SUPPORTED);
        backend.fails(Methods.REOPEN_INDEX, new ReopenIndexParams(), ErrorCode.NOT_SUPPORTED);
    }

    @Test
    void readsItsCommits() throws Exception {
        assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits())
                .singleElement()
                .satisfies(commit -> {
                    assertThat(commit.current()).isTrue();
                    assertThat(commit.luceneVersion()).isEqualTo("2.x");
                    assertThat(commit.delCount()).isEqualTo(1);
                });
    }

    @Test
    void opensWithoutAReader() throws Exception {
        OpenIndexResult withoutReader = backend.call(
                Methods.OPEN_INDEX,
                new OpenIndexParams(PATH, Optional.of(DirectoryImpl.NIO), Optional.of(OpenMode.DIRECTORY_ONLY)));
        assertThat(withoutReader.mode()).isEqualTo(OpenMode.DIRECTORY_ONLY);
        // Lucene 3.6 checks it, at its one level (ADR 0045).
        assertThat(withoutReader.features()).containsExactlyInAnyOrder(Feature.COMMITS, Feature.CHECK_INDEX);
        assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits())
                .singleElement()
                .satisfies(commit -> assertThat(commit.current()).isFalse());
        backend.fails(Methods.GET_DOCUMENT, new GetDocumentParams(0), ErrorCode.NO_READER);
    }

    @Test
    void closesAndOpensAnotherIndex() throws Exception {
        assertThat(backend.call(Methods.CLOSE_INDEX, new CloseIndexParams()).path())
                .contains(PATH);
        backend.fails(Methods.GET_DOCUMENT, new GetDocumentParams(0), ErrorCode.NO_INDEX_OPEN);
        backend.call(Methods.OPEN_INDEX, new OpenIndexParams(PATH, Optional.of(DirectoryImpl.MMAP), Optional.empty()));
        backend.call(
                Methods.OPEN_INDEX, new OpenIndexParams(testIndex("lucene9").toString()));
        // Lucene 10 reads the open index now.
        assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits())
                .isNotEmpty();
    }

    private static StartSearchParams params(io.github.mfuerstenau.leia.protocol.search.SearchQuery query) {
        return new StartSearchParams(query, BM25, List.of(), 0, 10, true, Optional.empty(), Optional.empty());
    }
}
