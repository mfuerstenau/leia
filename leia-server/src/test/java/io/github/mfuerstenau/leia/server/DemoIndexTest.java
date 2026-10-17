package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.STANDARD;
import static io.github.mfuerstenau.leia.server.RealBackend.classic;
import static io.github.mfuerstenau.leia.server.RealBackend.standard;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CollationStrength;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.protocol.analysis.Token;
import io.github.mfuerstenau.leia.protocol.commits.FieldFormats;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsResult;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueParams;
import io.github.mfuerstenau.leia.protocol.document.GetTermVectorParams;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.filter.FilterMatch;
import io.github.mfuerstenau.leia.protocol.filter.FilterPattern;
import io.github.mfuerstenau.leia.protocol.filter.FilterResult;
import io.github.mfuerstenau.leia.protocol.filter.StartFilterParams;
import io.github.mfuerstenau.leia.protocol.filter.StepFilterParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReaderSegment;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.ReopenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.index.VectorSimilarity;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.PointCell;
import io.github.mfuerstenau.leia.protocol.points.PointSegment;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import io.github.mfuerstenau.leia.protocol.search.BooleanSimilarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.DocumentHighlight;
import io.github.mfuerstenau.leia.protocol.search.Explanation;
import io.github.mfuerstenau.leia.protocol.search.FieldDateResolution;
import io.github.mfuerstenau.leia.protocol.search.FieldMatches;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.HighlightOptions;
import io.github.mfuerstenau.leia.protocol.search.HighlightPassage;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.HitHighlight;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.LikeTerm;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.protocol.search.OffsetSource;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
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
import io.github.mfuerstenau.leia.protocol.search.StartHighlightParams;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.StartStoredScanParams;
import io.github.mfuerstenau.leia.protocol.search.StoredScanHit;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import io.github.mfuerstenau.leia.protocol.terms.PostingsFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.IntPoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code docs/demo-index.md} says about the demo index, read through {@link LeiaBackend} as
 * the UI reads it, so that the document stays true. Each nested class is one section of it; what
 * only the screens show (marks, highlights, the viewer) is for trying by hand.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoIndexTest {

    private static final Path DEMO = testIndex("demo");

    private RealBackend backend;

    @BeforeAll
    void open() throws Exception {
        backend = RealBackend.start();
        backend.call(Methods.OPEN_INDEX, new OpenIndexParams(DEMO.toString()));
    }

    @AfterAll
    void close() {
        backend.close();
    }

    /** The text of each span of {@code passage}. */
    private static List<String> marked(HighlightPassage passage) {
        byte[] text = passage.snippet().text().getBytes(StandardCharsets.UTF_8);
        int start = passage.snippet().start();
        return passage.spans().stream()
                .map(span -> new String(
                        Arrays.copyOfRange(text, span.start() - start, span.end() - start), StandardCharsets.UTF_8))
                .toList();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Index {

        @Test
        void hasThreeSegmentsAndThirteenDeletedDocuments() throws Exception {
            GetIndexStatsResult stats = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());

            assertThat(List.of(stats.segmentCount(), stats.numDocs(), stats.maxDoc()))
                    .containsExactly(3, 487, 500);
            List<Segment> segments = backend.call(Methods.GET_COMMIT, new GetCommitParams(4, OptionalInt.empty()))
                    .segments();
            assertThat(segments)
                    .extracting(Segment::name, Segment::docBase, Segment::maxDoc, Segment::compound)
                    .containsExactly(
                            tuple("_0", 0, 200, true), tuple("_1", 200, 200, true), tuple("_2", 400, 100, false));
            // The open reader has the same segments (ADR 0051).
            assertThat(stats.indexes().getFirst().segments())
                    .extracting(ReaderSegment::name, ReaderSegment::docBase, ReaderSegment::maxDoc)
                    .containsExactly(tuple("_0", 0, 200), tuple("_1", 200, 200), tuple("_2", 400, 100));
            assertThat(stats.indexes().getFirst().segments().stream()
                            .mapToInt(ReaderSegment::numDocs)
                            .sum())
                    .isEqualTo(487);
            // The same formats per field, in a compound segment and a plain one.
            for (Segment segment : List.of(segments.get(0), segments.get(2))) {
                assertThat(segment.fieldInfosError()).as(segment.name()).isEmpty();
                assertThat(segment.fieldFormats())
                        .as(segment.name())
                        .filteredOn(field -> List.of("category", "checksum", "embedding", "fingerprint", "_source")
                                .contains(field.field()))
                        .containsExactly(
                                new FieldFormats("_source", Optional.empty(), Optional.empty(), Optional.empty()),
                                new FieldFormats(
                                        "category",
                                        Optional.of("Lucene104"),
                                        Optional.of("Lucene90"),
                                        Optional.empty()),
                                new FieldFormats(
                                        "checksum", Optional.empty(), Optional.of("Lucene90"), Optional.empty()),
                                new FieldFormats(
                                        "embedding",
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of("Lucene99HnswVectorsFormat")),
                                new FieldFormats(
                                        "fingerprint",
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of("Lucene99HnswVectorsFormat")));
            }
            List<Integer> deleted = new ArrayList<>();
            for (int doc = 37; doc < 500; doc += 37) {
                deleted.add(doc);
            }
            assertThat(deleted)
                    .hasSize(13)
                    .allSatisfy(doc -> assertThat(document(doc).deleted()).isTrue());
        }

        @Test
        void keepsFourCommitsWithTheUserDataInTheLast() throws Exception {
            GetCommitsResult commits = backend.call(Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.empty()));

            // Newest first.
            assertThat(commits.commits())
                    .extracting(commit -> commit.segmentsFile())
                    .containsExactly("segments_4", "segments_3", "segments_2", "segments_1");
            assertThat(commits.commits().getFirst().userData()).containsOnlyKeys("writer", "source", "docs");
            assertThat(commits.commits().getFirst().delCount()).isEqualTo(13);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Documents {

        @Test
        void documentZeroHasOnlyAnId() throws Exception {
            assertThat(document(0).fields())
                    .filteredOn(field ->
                            !field.stored().isEmpty() || field.docValues().isPresent())
                    .extracting(DocumentField::name)
                    .containsExactly("id");
        }

        @Test
        void documentFortyTwoHasValuesOverTheCut() throws Exception {
            GetDocumentResult document = document(42);

            StoredValue source = stored(document, "_source");
            assertThat(source.fullLength().orElseThrow()).isBetween(100_000L, 115_000L);
            StoredValue whole = backend.call(Methods.GET_STORED_VALUE, new GetStoredValueParams(42, "_source", 0))
                    .value();
            assertThat(whole.fullLength()).isEmpty();
            assertThat(whole.value()).contains("\"history\"");
            assertThat(whole.value().split("\"event\":", -1)).hasSize(3001);

            StoredValue blob = backend.call(Methods.GET_STORED_VALUE, new GetStoredValueParams(42, "blob", 0))
                    .value();
            assertThat(blob.binary()).isTrue();
            // In hex.
            assertThat(blob.value()).hasSize(200_000);
            assertThat(stored(document, "blob").fullLength()).hasValue(100_000);
        }

        @Test
        void documentSevenHasTheOddText() throws Exception {
            assertThat(stored(document(7), "odd_text").value()).contains("\t", "\u0007", "\u0000", "\r\n");
            assertThat(document(8).fields())
                    .filteredOn(field -> field.name().equals("odd_text"))
                    .allSatisfy(field -> assertThat(field.stored()).isEmpty());
        }

        @Test
        void documentSeventyFourIsDeletedAndHasDeletedOnly() throws Exception {
            GetDocumentResult document = document(74);

            assertThat(document.deleted()).isTrue();
            assertThat(stored(document, "deleted_only").value()).isEqualTo("only in a deleted document");
        }

        @Test
        void rebuildsTheIndexedTermsFromThePostingsOrTheTermVector() throws Exception {
            Map<String, ReconstructedField> fields = rebuilt(1, List.of("title", "body", "labels", "keywords"));

            assertThat(fields.get("title").source()).isEqualTo(ReconstructSource.POSTINGS);
            assertThat(fields.get("title").terms())
                    .allSatisfy(term -> assertThat(term.positions()).isNotEmpty());
            assertThat(fields.get("body").source()).isEqualTo(ReconstructSource.TERM_VECTOR);
            assertThat(fields.get("labels").indexOptions()).isEqualTo(IndexOptions.DOCS_AND_FREQS);
            assertThat(fields.get("labels").source()).isEqualTo(ReconstructSource.POSTINGS);
            assertThat(fields.get("keywords").indexOptions()).isEqualTo(IndexOptions.DOCS);
            assertThat(fields.get("keywords").source()).isEqualTo(ReconstructSource.POSTINGS);
            // Neither of them is stored.
            assertThat(document(1).fields())
                    .filteredOn(field -> List.of("labels", "keywords").contains(field.name()))
                    .allSatisfy(field -> assertThat(field.stored()).isEmpty());

            assertThat(rebuilt(1, List.of("binary_key")).get("binary_key").terms())
                    .singleElement()
                    .satisfies(term -> assertThat(term.binary()).isTrue());
            assertThat(rebuilt(74, List.of("deleted_only")).get("deleted_only").terms())
                    .extracting(TermVectorTerm::term)
                    .contains("deleted", "document");
        }

        @Test
        void rebuildsThePointsOfAProductAsItsStoredValues() throws Exception {
            Map<String, ReconstructedField> fields = rebuilt(1, List.of("quantity", "price", "location"));
            GetDocumentResult document = document(1);

            assertThat(fields.get("quantity").indexOptions()).isEqualTo(IndexOptions.NONE);
            assertThat(fields.get("quantity").terms()).isEmpty();
            assertThat(fields.get("quantity").points())
                    .singleElement()
                    .satisfies(point -> assertThat(IntPoint.decodeDimension(dimension(point, 0), 0))
                            .isEqualTo(Integer.parseInt(
                                    stored(document, "quantity").value())));
            assertThat(fields.get("price").points())
                    .singleElement()
                    .satisfies(point -> assertThat(DoublePoint.decodeDimension(dimension(point, 0), 0))
                            .isEqualTo(
                                    Double.parseDouble(stored(document, "price").value())));
            // Latitude and longitude, 4 bytes each.
            assertThat(fields.get("location").points())
                    .singleElement()
                    .satisfies(point -> assertThat(point.dimensions())
                            .hasSize(2)
                            .allSatisfy(dimension -> assertThat(dimension).hasSize(8)));
        }

        private static byte[] dimension(PointValue point, int dimension) {
            return HexFormat.of().parseHex(point.dimensions().get(dimension));
        }

        @Test
        void rebuildsTheFiveThousandWordsOfDocumentFortyTwoFromItsTermVector() throws Exception {
            ReconstructedField body = rebuilt(42, List.of("body")).get("body");

            assertThat(body.source()).isEqualTo(ReconstructSource.TERM_VECTOR);
            // 5000 words, and a few more tokens, since some words are two.
            assertThat(body.terms().stream()
                            .mapToLong(term -> term.positions().size())
                            .sum())
                    .isGreaterThanOrEqualTo(5000);
            // 東京 is two terms to the standard tokenizer.
            assertThat(body.terms()).extracting(TermVectorTerm::term).contains("東", "京");
            assertThat(body.terms())
                    .isEqualTo(backend.call(Methods.GET_TERM_VECTOR, new GetTermVectorParams(42, "body"))
                            .terms());
        }

        private Map<String, ReconstructedField> rebuilt(int docId, List<String> fields) throws Exception {
            return backend.reconstruct(docId, Optional.of(fields)).fields().stream()
                    .collect(java.util.stream.Collectors.toMap(ReconstructedField::field, field -> field));
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Searches {

        @Test
        void lampsWithQuickWords() throws Exception {
            SearchQuery query = classic("title:lamp AND body:qu*");

            assertThat(backend.search(query, 10).totalHits()).isEqualTo(10);
            Explanation explanation = backend.explain(query, BM25, 223);
            assertThat(explanation.match()).isTrue();
            assertThat(flatten(explanation))
                    .anySatisfy(detail -> assertThat(detail.description()).contains("body:qu"));
        }

        @Test
        void aRangeOnCollationKeysNeedsTheirAnalyzer() throws Exception {
            // German collation keys at primary strength: a to m, accents and case aside (ADR 0039).
            Analyzer collation = new Analyzer(
                    Optional.empty(),
                    Optional.of(new PresetAnalyzer(
                            "org.apache.lucene.collation.CollationKeyAnalyzer",
                            Optional.empty(),
                            Optional.empty(),
                            OptionalInt.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(new Collation("de", Optional.of(CollationStrength.PRIMARY), Optional.empty())),
                            Optional.empty())),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    List.of());
            Analyzer perField = new Analyzer(
                    STANDARD.standard(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(new FieldAnalyzer("title_key", collation)),
                    List.of());
            String range = "title_key:[a TO m]";

            long collated = backend.search(classic(range, perField), 10).totalHits();
            // docs/demo-index.md quotes it.
            assertThat(collated).isEqualTo(206).isEqualTo(liveTitlesFromAToM());
            assertThat(backend.search(classic(range), 10).totalHits()).isNotEqualTo(collated);
        }

        @Test
        void autoStopWordsOfTheBody() throws Exception {
            // docs/demo-index.md quotes these (ADR 0039).
            AutoStopWords found = backend.job(
                    Methods.START_AUTO_STOP_WORDS,
                    new StartAutoStopWordsParams(List.of("body"), OptionalInt.empty(), OptionalDouble.of(0.4)),
                    Methods.GET_AUTO_STOP_WORDS,
                    GetAutoStopWordsResult::stopWords);

            assertThat(found.maxDocFreq()).isEqualTo(194);
            assertThat(found.fields())
                    .singleElement()
                    .satisfies(body -> assertThat(body.words())
                            .containsExactly(
                                    "a", "and", "brown", "for", "fox", "green", "in", "is", "lazy", "of", "on", "quick",
                                    "red", "the", "to", "with"));
        }

        /** The live documents whose stored title is from a to m by the German collator at primary strength. */
        private long liveTitlesFromAToM() throws Exception {
            Collator german = Collator.getInstance(Locale.GERMAN);
            german.setStrength(Collator.PRIMARY);
            long count = 0;
            for (int docId = 0; docId < 500; docId++) {
                GetDocumentResult document = document(docId);
                if (document.deleted()) {
                    continue;
                }
                Optional<String> title = document.fields().stream()
                        .filter(field -> field.name().equals("title"))
                        .flatMap(field -> field.stored().stream())
                        .map(StoredValue::value)
                        .findFirst();
                if (title.isPresent()
                        && german.compare(title.get(), "a") >= 0
                        && german.compare(title.get(), "m") <= 0) {
                    count++;
                }
            }
            return count;
        }

        @Test
        void everyLiveDocument() throws Exception {
            assertThat(backend.search(classic("*:*"), 100).totalHits()).isEqualTo(487);
        }

        @Test
        void syntaxErrorsAndLeadingWildcards() throws Exception {
            backend.fails(Methods.START_SEARCH, params(classic("title:(lamp")), ErrorCode.INVALID_QUERY);
            backend.fails(Methods.START_SEARCH, params(classic("body:*a*")), ErrorCode.INVALID_QUERY);
            ParserOptions leading = new ParserOptions(
                    true, 0, 0, true, Optional.empty(), List.of(), Optional.empty(), Optional.empty());
            assertThat(backend.search(classic("body:*a*", leading), 10).totalHits())
                    .isPositive();
        }

        @Test
        void moreLikeDocument223() throws Exception {
            SearchResults like = backend.search(
                    moreLikeThis(OptionalInt.of(223), Optional.empty(), 2, 5, OptionalInt.empty(), false, 0, List.of()),
                    10);

            assertThat(like.totalHits()).isEqualTo(485);
            assertThat(like.hits().getFirst().docId()).isEqualTo(223);

            SearchResults boosted = backend.search(
                    moreLikeThis(OptionalInt.of(223), Optional.empty(), 1, 5, OptionalInt.of(50), true, 0, List.of()),
                    10);
            assertThat(boosted.rewrittenQuery()).contains("^");

            SearchResults deleted = backend.search(
                    moreLikeThis(OptionalInt.of(37), Optional.empty(), 1, 1, OptionalInt.empty(), false, 0, List.of()),
                    500);
            assertThat(deleted.totalHits()).isPositive();
            assertThat(deleted.hits()).extracting(Hit::docId).doesNotContain(37);
            assertThat(backend.search(
                                    moreLikeThis(
                                            OptionalInt.of(0),
                                            Optional.empty(),
                                            2,
                                            5,
                                            OptionalInt.empty(),
                                            false,
                                            0,
                                            List.of()),
                                    10)
                            .totalHits())
                    .isZero();
        }

        @Test
        void pointsWithTheStandardParser() throws Exception {
            String lampsFrom10To20 = "title:lamp AND price:[10 TO 20]";

            assertThat(backend.search(
                                    standard(
                                            lampsFrom10To20,
                                            QueryOperator.OR,
                                            List.of(new PointField("price", PointType.LONG))),
                                    10)
                            .totalHits())
                    .isZero();
            SearchResults asDoubles = backend.search(
                    standard(lampsFrom10To20, QueryOperator.OR, List.of(new PointField("price", PointType.DOUBLE))),
                    10);
            assertThat(asDoubles.hits()).extracting(Hit::docId).containsExactly(196);
            assertThat(backend.search(
                                    standard(
                                            "quantity>=50",
                                            QueryOperator.OR,
                                            List.of(new PointField("quantity", PointType.INT))),
                                    10)
                            .totalHits())
                    .isPositive();
        }

        @Test
        void lampsSortedByPriceThenTags() throws Exception {
            SearchResults byPrice = backend.search(sorted(
                    classic("title:lamp"),
                    field("price", SortType.DOUBLE, true, SortSelector.MIN, SortMissing.DEFAULT),
                    field("tags", SortType.STRING, false, SortSelector.MIN, SortMissing.DEFAULT)));

            Hit first = byPrice.hits().getFirst();
            assertThat(first.docId()).isEqualTo(51);
            assertThat(first.sortValues().getFirst().number()).hasValue("495.49");
            // Hits without tags have no value to show.
            assertThat(byPrice.hits())
                    .anySatisfy(
                            hit -> assertThat(hit.sortValues().get(1).term()).isEmpty());

            // Read as LONG, or even INT, it is no error.
            for (SortType type : List.of(SortType.LONG, SortType.INT)) {
                assertThat(backend.search(sorted(
                                        classic("title:lamp"),
                                        field("price", type, true, SortSelector.MIN, SortMissing.DEFAULT)))
                                .totalHits())
                        .isEqualTo(byPrice.totalHits());
            }
        }

        @Test
        void everyDocumentSortedByItsLargestScoreThenId() throws Exception {
            SearchResults byScores = backend.search(sorted(
                    classic("*:*"),
                    field("scores", SortType.LONG, true, SortSelector.MAX, SortMissing.DEFAULT),
                    new SortKey(Optional.empty(), Optional.empty(), Optional.of(new DocSort(false)))));

            assertThat(byScores.hits().getFirst().docId()).isEqualTo(64);
            assertThat(byScores.hits().getFirst().sortValues().getFirst().number())
                    .hasValue("100");
        }

        @Test
        void documentsWithoutTagsFirstOrLast() throws Exception {
            for (boolean reverse : List.of(false, true)) {
                List<Hit> first = backend.search(sorted(
                                classic("*:*"),
                                500,
                                field("tags", SortType.STRING, reverse, SortSelector.MIN, SortMissing.FIRST)))
                        .hits();
                List<Hit> last = backend.search(sorted(
                                classic("*:*"),
                                500,
                                field("tags", SortType.STRING, reverse, SortSelector.MIN, SortMissing.LAST)))
                        .hits();
                int untagged = (int) first.stream()
                        .filter(hit -> hit.sortValues().getFirst().term().isEmpty())
                        .count();

                assertThat(untagged).as("reverse %s", reverse).isBetween(80, 120);
                assertThat(first.subList(0, untagged))
                        .allSatisfy(hit ->
                                assertThat(hit.sortValues().getFirst().term()).isEmpty());
                assertThat(last.subList(last.size() - untagged, last.size()))
                        .allSatisfy(hit ->
                                assertThat(hit.sortValues().getFirst().term()).isEmpty());
            }
        }

        @Test
        void dateRanges() throws Exception {
            SearchResults january = backend.search(classic("published:[1/1/26 TO 1/31/26]", dates("en-US", "UTC")), 10);

            assertThat(january.query()).isEqualTo("published:[20260101 TO 20260131]");
            assertThat(january.totalHits()).isEqualTo(49);
            SearchResults timestamps = backend.search(
                    standard(
                            "timestamp:[1767225600000 TO 1769903999999]",
                            QueryOperator.OR,
                            List.of(new PointField("timestamp", PointType.LONG))),
                    10);
            assertThat(timestamps.totalHits()).isEqualTo(49);
            // Without a resolution, the bounds stay text, which no day matches.
            assertThat(backend.search(classic("published:[1/1/26 TO 1/31/26]"), 10)
                            .totalHits())
                    .isZero();
            assertThat(backend.search(classic("published:[01.01.26 TO 31.01.26]", dates("de-DE", "UTC")), 10)
                            .totalHits())
                    .isEqualTo(49);

            assertThat(backend.search(classic("published:[2/1/26 TO 2/28/26]", dates("en-US", "UTC")), 10)
                            .totalHits())
                    .isEqualTo(44);
            // In Berlin, midnight is the day before in UTC.
            SearchResults inBerlin =
                    backend.search(classic("published:[2/1/26 TO 2/28/26]", dates("en-US", "Europe/Berlin")), 10);
            assertThat(inBerlin.query()).startsWith("published:[20260131");
            assertThat(inBerlin.totalHits()).isEqualTo(46);
        }

        @Test
        void dateRangesWithAResolutionPerField() throws Exception {
            ParserOptions hours = new ParserOptions(
                    false,
                    0,
                    0,
                    true,
                    Optional.of(DateResolution.DAY),
                    List.of(new FieldDateResolution("updated", DateResolution.HOUR)),
                    Optional.of("en-US"),
                    Optional.of("UTC"));

            SearchResults both =
                    backend.search(classic("+published:[1/1/26 TO 1/31/26] +updated:[1/10/26 TO 1/20/26]", hours), 10);

            assertThat(both.query()).isEqualTo("+published:[20260101 TO 20260131] +updated:[2026011000 TO 2026012023]");
            assertThat(both.totalHits()).isEqualTo(12);
            SearchResults timestamps = backend.search(
                    standard(
                            "timestamp:[1768003200000 TO 1768953599999]",
                            QueryOperator.OR,
                            List.of(new PointField("timestamp", PointType.LONG))),
                    10);
            assertThat(timestamps.totalHits()).isEqualTo(12);
            // At DAY, the upper bound 20260120 sorts before every hour of that day.
            SearchResults days = backend.search(
                    classic("+published:[1/1/26 TO 1/31/26] +updated:[1/10/26 TO 1/20/26]", dates("en-US", "UTC")), 10);
            assertThat(days.query()).endsWith("+updated:[20260110 TO 20260120]");
            assertThat(days.totalHits()).isEqualTo(10);
        }

        @Test
        void lampsWorstFirst() throws Exception {
            List<Hit> hits = backend.search(sorted(
                            classic("title:lamp"),
                            new SortKey(Optional.empty(), Optional.of(new ScoreSort(true)), Optional.empty())))
                    .hits();

            assertThat(hits).extracting(Hit::score).isSortedAccordingTo(Float::compare);
        }

        @Test
        void lampsWithClassicAndBooleanScores() throws Exception {
            Similarity classic =
                    new Similarity(Optional.empty(), Optional.of(new ClassicSimilarity()), Optional.empty());
            SearchResults tfIdf = backend.search(new StartSearchParams(
                    classic("title:lamp"), classic, List.of(), 0, 100, true, Optional.of(List.of()), Optional.empty()));

            Hit doc51 = tfIdf.hits().stream()
                    .filter(hit -> hit.docId() == 51)
                    .findFirst()
                    .orElseThrow();
            assertThat(String.format(java.util.Locale.ROOT, "%.4f", doc51.score()))
                    .isEqualTo("2.4358");
            assertThat(Float.parseFloat(
                            backend.explain(classic("title:lamp"), classic, 51).value()))
                    .isEqualTo(doc51.score());

            Similarity bool = new Similarity(Optional.empty(), Optional.empty(), Optional.of(new BooleanSimilarity()));
            assertThat(backend.search(new StartSearchParams(
                                    classic("title:lamp"),
                                    bool,
                                    List.of(),
                                    0,
                                    100,
                                    true,
                                    Optional.of(List.of()),
                                    Optional.empty()))
                            .hits())
                    .extracting(Hit::score)
                    .containsOnly(1f);
        }

        @Test
        void highlightsLampsWithTheOffsetsEachFieldHas() throws Exception {
            SearchResults results = backend.search(new StartSearchParams(
                    classic("lamp title:lamp tagged:lamp"),
                    BM25,
                    List.of(),
                    0,
                    100,
                    false,
                    Optional.of(List.of("title", "body", "tagged")),
                    Optional.of(new HighlightOptions(3, true, STANDARD))));

            List<HitHighlight> highlights = results.hits().stream()
                    .flatMap(hit -> hit.highlights().stream())
                    .toList();
            // body has offsets in its postings, title none, and tagged term vectors without offsets.
            assertThat(highlights)
                    .extracting(HitHighlight::field, HitHighlight::offsetSource)
                    .contains(
                            tuple("body", OffsetSource.POSTINGS),
                            tuple("title", OffsetSource.ANALYSIS),
                            tuple("tagged", OffsetSource.ANALYSIS))
                    .doesNotContain(tuple("tagged", OffsetSource.TERM_VECTORS));
            assertThat(highlights)
                    .flatExtracting(HitHighlight::passages)
                    .allSatisfy(passage -> assertThat(marked(passage))
                            .isNotEmpty()
                            .allSatisfy(text -> assertThat(text).isEqualToIgnoringCase("lamp")));

            int docId = results.hits().stream()
                    .filter(hit ->
                            hit.highlights().stream().anyMatch(h -> h.field().equals("body")))
                    .findFirst()
                    .orElseThrow()
                    .docId();
            DocumentHighlight document = backend.highlight(new StartHighlightParams(
                    classic("lam*"), docId, Optional.empty(), new HighlightOptions(1, true, STANDARD)));
            assertThat(document.fields()).extracting(FieldMatches::field).contains("title", "body");
            FieldMatches body = document.fields().stream()
                    .filter(field -> field.field().equals("body"))
                    .findFirst()
                    .orElseThrow();
            // A wildcard needs the term vector to find the terms it matches.
            assertThat(body.offsetSource()).isEqualTo(OffsetSource.POSTINGS_WITH_TERM_VECTORS);
            assertThat(body.values())
                    .isNotEmpty()
                    .allSatisfy(value -> assertThat(value.matchCount())
                            .isPositive()
                            .isEqualTo(value.spans().size()));
        }

        @Test
        void theSecondPageOfThe() throws Exception {
            SearchResults second = backend.search(new StartSearchParams(
                    classic("body:the"), BM25, List.of(), 100, 100, true, Optional.of(List.of()), Optional.empty()));

            assertThat(second.totalHits()).isEqualTo(471);
            assertThat(second.hits().getFirst().docId()).isEqualTo(418);
        }

        @Test
        void docValues() throws Exception {
            assertThat(backend.search(docValues("quantity", DocValuesNumber.LONG, range("-5", "5", true, true)), 1000)
                            .totalHits())
                    .isEqualTo(9)
                    .isEqualTo(backend.search(
                                    standard(
                                            "quantity:[-5 TO 5]",
                                            QueryOperator.OR,
                                            List.of(new PointField("quantity", PointType.INT))),
                                    1000)
                            .totalHits());
            SearchResults cheap =
                    backend.search(docValues("price", DocValuesNumber.DOUBLE, range(null, "10", false, true)), 1000);
            assertThat(cheap.totalHits())
                    .isEqualTo(12)
                    .isEqualTo(backend.search(
                                    standard(
                                            "price:[* TO 10]",
                                            QueryOperator.OR,
                                            List.of(new PointField("price", PointType.DOUBLE))),
                                    1000)
                            .totalHits());
            assertThat(backend.search(values("category", "kitchen"), 1000).totalHits())
                    .isEqualTo(80)
                    .isEqualTo(backend.search(classic("category:kitchen"), 1000).totalHits());
            assertThat(backend.search(values("tags", "eco", "gift"), 1000).totalHits())
                    .isEqualTo(101);
            assertThat(backend.search(docValues("tags", null, range("a", "c", true, false)), 1000)
                            .totalHits())
                    .isEqualTo(92);

            BytesValue checksum = document(5).fields().stream()
                    .filter(field -> field.name().equals("checksum"))
                    .findFirst()
                    .orElseThrow()
                    .docValues()
                    .orElseThrow()
                    .values()
                    .getFirst();
            SearchResults same =
                    backend.search(docValues("checksum", null, Optional.of(List.of(checksum)), Optional.empty()), 1000);
            assertThat(same.hits()).extracting(Hit::docId).containsExactly(5);
        }

        @Test
        void docValuesByAPrefix() throws Exception {
            // office and outdoor
            assertThat(backend.search(prefix("category", text("o")), 1000).totalHits())
                    .isEqualTo(158)
                    .isEqualTo(backend.search(classic("category:o*"), 1000).totalHits());
            // limited and local
            assertThat(backend.search(prefix("tags", text("l")), 1000).totalHits())
                    .isEqualTo(179)
                    .isEqualTo(backend.search(classic("tags:l*"), 1000).totalHits());
            // The empty prefix matches every document with a value.
            assertThat(backend.search(prefix("tags", text("")), 1000).totalHits())
                    .isEqualTo(385)
                    .isEqualTo(backend.search(docValues("tags", null, range(null, null, true, true)), 1000)
                            .totalHits());
            SearchResults hex = backend.search(prefix("checksum", new BytesValue("80", true)), 1000);
            assertThat(hex.totalHits())
                    .isEqualTo(1)
                    .isEqualTo(backend.search(
                                    docValues(
                                            "checksum",
                                            null,
                                            Optional.of(new DocValuesRange(
                                                    Optional.of(new BytesValue("80", true)),
                                                    Optional.of(new BytesValue("81", true)),
                                                    true,
                                                    false))),
                                    1000)
                            .totalHits());
            assertThat(hex.query()).isEqualTo("checksum:[[80] TO [81]}");
        }

        private static StartSearchParams params(SearchQuery query) {
            return new StartSearchParams(query, BM25, List.of(), 0, 10, false, Optional.empty(), Optional.empty());
        }

        private static StartSearchParams sorted(SearchQuery query, SortKey... sort) {
            return sorted(query, 100, sort);
        }

        private static StartSearchParams sorted(SearchQuery query, int limit, SortKey... sort) {
            return new StartSearchParams(
                    query, BM25, List.of(sort), 0, limit, true, Optional.of(List.of()), Optional.empty());
        }

        private static SortKey field(
                String field, SortType type, boolean reverse, SortSelector selector, SortMissing missing) {
            return new SortKey(
                    Optional.of(new FieldSort(field, type, reverse, selector, missing)),
                    Optional.empty(),
                    Optional.empty());
        }

        private static ParserOptions dates(String locale, String timeZone) {
            return new ParserOptions(
                    false,
                    0,
                    0,
                    true,
                    Optional.of(DateResolution.DAY),
                    List.of(),
                    Optional.of(locale),
                    Optional.of(timeZone));
        }

        private static SearchQuery docValues(String field, DocValuesNumber number, Optional<DocValuesRange> range) {
            return docValues(field, number, Optional.empty(), range);
        }

        private static SearchQuery values(String field, String... values) {
            return docValues(
                    field,
                    null,
                    Optional.of(Stream.of(values).map(DemoIndexTest::text).toList()),
                    Optional.empty());
        }

        private static SearchQuery docValues(
                String field,
                DocValuesNumber number,
                Optional<List<BytesValue>> values,
                Optional<DocValuesRange> range) {
            return new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(
                            new DocValuesQuery(field, Optional.ofNullable(number), values, range, Optional.empty())),
                    Optional.empty());
        }

        private static SearchQuery prefix(String field, BytesValue prefix) {
            return new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new DocValuesQuery(
                            field, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(prefix))),
                    Optional.empty());
        }

        private static Optional<DocValuesRange> range(
                String lower, String upper, boolean includeLower, boolean includeUpper) {
            return Optional.of(new DocValuesRange(
                    Optional.ofNullable(lower).map(DemoIndexTest::text),
                    Optional.ofNullable(upper).map(DemoIndexTest::text),
                    includeLower,
                    includeUpper));
        }

        private static List<Explanation> flatten(Explanation explanation) {
            List<Explanation> all = new ArrayList<>(List.of(explanation));
            explanation.details().forEach(detail -> all.addAll(flatten(detail)));
            return all;
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Points {

        @Test
        void priceHasATreeOfSeveralLevelsInEachSegment() throws Exception {
            GetPointTreeResult tree = backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams("price"));

            // Document 0 has no price; deleted documents keep their points.
            assertThat(tree.size()).isEqualTo(499);
            assertThat(List.of(tree.dimensions(), tree.indexDimensions(), tree.bytesPerDimension()))
                    .containsExactly(1, 1, 8);
            assertThat(tree.segments())
                    .extracting(PointSegment::segment, PointSegment::name, PointSegment::size)
                    .containsExactly(
                            tuple(0, Optional.of("_0"), 199L),
                            tuple(1, Optional.of("_1"), 200L),
                            tuple(2, Optional.of("_2"), 100L));

            GetPointCellResult root =
                    backend.call(Methods.GET_POINT_CELL, new GetPointCellParams("price", 0, List.of()));
            assertThat(root.cell().leaf()).isFalse();
            assertThat(root.children().stream().mapToLong(PointCell::size).sum())
                    .isEqualTo(199);

            // Leaves of at most 16 points (the demo writer's), down the first children.
            List<Integer> path = new ArrayList<>();
            GetPointCellResult cell = root;
            while (!cell.cell().leaf()) {
                path.add(0);
                cell = backend.call(Methods.GET_POINT_CELL, new GetPointCellParams("price", 0, path));
            }
            assertThat(path.size()).isGreaterThan(2);
            assertThat(cell.points()).hasSizeBetween(1, 16);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Vectors {

        @Test
        void fieldsKnowTheirEncodingAndSimilarity() throws Exception {
            GetIndexStatsResult stats = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());

            assertThat(stats.fields())
                    .filteredOn(field -> field.vectorDimension() > 0)
                    .extracting(
                            IndexField::name,
                            IndexField::vectorDimension,
                            IndexField::vectorEncoding,
                            IndexField::vectorSimilarity)
                    .containsExactly(
                            tuple(
                                    "embedding",
                                    8,
                                    Optional.of(VectorEncoding.FLOAT32),
                                    Optional.of(VectorSimilarity.COSINE)),
                            tuple(
                                    "fingerprint",
                                    16,
                                    Optional.of(VectorEncoding.BYTE),
                                    Optional.of(VectorSimilarity.EUCLIDEAN)));
            assertThat(stats.fields())
                    .filteredOn(field -> field.vectorDimension() == 0)
                    .allSatisfy(field -> {
                        assertThat(field.vectorEncoding()).isEmpty();
                        assertThat(field.vectorSimilarity()).isEmpty();
                    });
        }

        @Test
        void documentFortyTwoHasBothVectors() throws Exception {
            GetDocumentResult document = document(42);

            KnnVector embedding = vector(document, "embedding").orElseThrow();
            assertThat(embedding.encoding()).isEqualTo(VectorEncoding.FLOAT32);
            assertThat(embedding.floats()).hasSize(8);
            // The first component is shifted by 2, so it is never below 1.
            assertThat(embedding.floats().getFirst()).isGreaterThanOrEqualTo(1f);
            KnnVector fingerprint = vector(document, "fingerprint").orElseThrow();
            assertThat(fingerprint.encoding()).isEqualTo(VectorEncoding.BYTE);
            assertThat(fingerprint.bytes()).hasSize(16);
            assertThat(vector(document(0), "embedding")).isEmpty();
            // A deleted document keeps its vector.
            assertThat(vector(document(74), "embedding")).isPresent();
        }

        @Test
        void theNearestToDocumentFortyTwo() throws Exception {
            SearchResults approximate = backend.search(knn("embedding", 42, OptionalInt.of(10), false), 100);
            SearchResults exact = backend.search(knn("embedding", 42, OptionalInt.empty(), true), 10);

            assertThat(approximate.totalHits()).isEqualTo(10);
            assertThat(approximate.hits().getFirst().docId()).isEqualTo(42);
            assertThat(approximate.hits().getFirst().score()).isEqualTo(1f);
            assertThat(exact.hits().getFirst().docId()).isEqualTo(42);
            // Every live document but 0, which has no vectors.
            assertThat(exact.totalHits()).isEqualTo(486);
            assertThat(approximate.hits())
                    .extracting(Hit::docId)
                    .containsExactlyElementsOf(
                            exact.hits().stream().map(Hit::docId).toList());
        }

        @Test
        void theNearestFingerprintsToDocumentFortyTwo() throws Exception {
            SearchResults exact = backend.search(knn("fingerprint", 42, OptionalInt.empty(), true), 3);

            assertThat(exact.hits().getFirst().docId()).isEqualTo(42);
            assertThat(exact.hits().getFirst().score()).isEqualTo(1f);
        }

        private static Optional<KnnVector> vector(GetDocumentResult document, String field) {
            return document.fields().stream()
                    .filter(f -> f.name().equals(field))
                    .findFirst()
                    .orElseThrow()
                    .vector();
        }

        private static SearchQuery knn(String field, int docId, OptionalInt k, boolean exact) {
            return new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(
                            new KnnQuery(field, OptionalInt.of(docId), Optional.empty(), k, exact, Optional.empty())));
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Filters {

        @Test
        void lampsWithQuickWords() throws Exception {
            FilterResult filter = queryFilter("title:lamp AND body:qu*", false);

            assertThat(filter.matchCount()).isEqualTo(10);
            assertThat(step(filter, 94, true)).isEqualTo(new FilterMatch(137, 1));
            assertThat(step(filter, 138, true)).isEqualTo(new FilterMatch(156, 2));
            assertThat(step(filter, 93, true)).isEqualTo(new FilterMatch(93, 0));
            assertThat(stepTo(filter, 92, false)).isEmpty();
            assertThat(step(filter, 499, false)).isEqualTo(new FilterMatch(489, 9));
        }

        @Test
        void everyDocumentWithOrWithoutTheDeleted() throws Exception {
            assertThat(queryFilter("*:*", false).matchCount()).isEqualTo(487);
            FilterResult all = queryFilter("*:*", true);

            assertThat(all.matchCount()).isEqualTo(500);
            assertThat(step(all, 37, true)).isEqualTo(new FilterMatch(37, 37));
        }

        @Test
        void lampsAndTheirQuickPostings() throws Exception {
            FilterResult lamps = queryFilter("title:lamp", false);

            assertThat(lamps.matchCount()).isEqualTo(19);
            assertThat(step(lamps, 441, true).docId()).isEqualTo(466);

            GetPostingsResult quick = postings(Optional.of(new PostingsFilter(lamps.filterId(), false)));
            assertThat(quick.docFreq()).isEqualTo(249);
            GetPostingsResult onlyLamps = postings(Optional.of(new PostingsFilter(lamps.filterId(), true)));
            assertThat(onlyLamps.postings())
                    .extracting(Posting::docId)
                    .hasSize(10)
                    .startsWith(93)
                    .endsWith(489);
            assertThat(onlyLamps.postings())
                    .allSatisfy(posting -> assertThat(posting.inFilter()).hasValue(true));
        }

        private GetPostingsResult postings(Optional<PostingsFilter> filter) throws Exception {
            return backend.call(Methods.GET_POSTINGS, new GetPostingsParams("body", text("quick"), 0, 1000, filter));
        }

        private FilterResult queryFilter(String expression, boolean includeDeleted) throws Exception {
            return backend.filter(
                    new StartFilterParams(Optional.of(classic(expression)), Optional.empty(), includeDeleted));
        }

        private FilterMatch step(FilterResult filter, int fromDoc, boolean forward) throws Exception {
            return stepTo(filter, fromDoc, forward).orElseThrow();
        }

        private Optional<FilterMatch> stepTo(FilterResult filter, int fromDoc, boolean forward) throws Exception {
            return backend.call(Methods.STEP_FILTER, new StepFilterParams(filter.filterId(), fromDoc, forward))
                    .match();
        }
    }

    @Nested
    class Reopen {

        @TempDir
        Path tmp;

        @Test
        void aRegexFilterIsBuiltAgainAfterAReopen() throws Exception {
            Path copy = tmp.resolve("demo");
            Files.createDirectories(copy);
            try (Stream<Path> files = Files.list(DEMO)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.equals("segments_4") && !name.equals("write.lock")) {
                        Files.copy(file, copy.resolve(name));
                    }
                }
            }
            try (RealBackend before = RealBackend.start()) {
                before.call(Methods.OPEN_INDEX, new OpenIndexParams(copy.toString()));
                StartFilterParams regex = new StartFilterParams(
                        Optional.empty(), Optional.of(new FilterPattern("qu\\w+", false, Optional.empty())), false);
                assertThat(before.filter(regex).matchCount()).isEqualTo(499);

                Files.copy(DEMO.resolve("segments_4"), copy.resolve("segments_4"));
                ReopenIndexResult reopened = before.call(Methods.REOPEN_INDEX, new ReopenIndexParams());

                assertThat(reopened.changed()).isTrue();
                assertThat(reopened.indexes().getFirst().reader().orElseThrow().segmentsFile())
                        .isEqualTo("segments_4");
                assertThat(before.filter(regex).matchCount()).isEqualTo(486);
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Analysis {

        private static final String ROUTERS = "<b>The</b> Wi-Fi routers are running FASTER";

        @TempDir
        Path config;

        @Test
        void theStandardAnalyzerKeepsTheTags() throws Exception {
            assertThat(terms(analyze(STANDARD, ROUTERS)))
                    .containsExactly("b", "the", "b", "wi", "fi", "routers", "are", "running", "faster");
        }

        @Test
        void htmlStripRemovesTheTagsAndKeepsTheOffsets() throws Exception {
            List<Token> tokens =
                    analyze(custom(List.of(component("htmlStrip")), List.of(component("lowercase"))), ROUTERS);

            assertThat(terms(tokens)).containsExactly("the", "wi", "fi", "routers", "are", "running", "faster");
            assertThat(tokens.getFirst().startOffset()).isEqualTo(3);
            assertThat(tokens.getFirst().endOffset()).isEqualTo(10);
        }

        @Test
        void porterStemAndStop() throws Exception {
            assertThat(terms(analyze(
                            custom(
                                    List.of(component("htmlStrip")),
                                    List.of(component("lowercase"), component("porterStem"))),
                            ROUTERS)))
                    .contains("router", "run");
            List<Token> stopped = analyze(
                    custom(List.of(component("htmlStrip")), List.of(component("lowercase"), component("stop"))),
                    ROUTERS);
            assertThat(terms(stopped)).doesNotContain("the", "are");
            assertThat(stopped.getFirst().positionIncrement()).isEqualTo(2);

            RpcException missing = backend.fails(
                    Methods.ANALYZE,
                    new AnalyzeParams(
                            custom(List.of(), List.of(new AnalysisComponent("stop", Map.of("words", "nope.txt")))),
                            ROUTERS,
                            Optional.empty()),
                    ErrorCode.INVALID_ANALYZER);
            assertThat(missing.getMessage()).contains("nope.txt");
        }

        @Test
        void synonymsStackOnTheirFirstWord() throws Exception {
            Files.writeString(config.resolve("syn.txt"), "wifi, wireless network\n");
            Analyzer synonyms = new Analyzer(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new CustomAnalyzer(
                            List.of(),
                            component("standard"),
                            List.of(
                                    component("lowercase"),
                                    new AnalysisComponent("synonymGraph", Map.of("synonyms", "syn.txt"))),
                            Optional.of(config.toString()))),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    List.of());

            List<Token> tokens = analyze(synonyms, "fast wifi router");

            assertThat(tokens)
                    .extracting(Token::term, Token::position, Token::positionLength)
                    .containsExactlyInAnyOrder(
                            tuple("fast", 0, 1),
                            tuple("wireless", 1, 1),
                            tuple("wifi", 1, 2),
                            tuple("network", 2, 1),
                            tuple("router", 3, 1));
        }

        @Test
        void moreLikeAText() throws Exception {
            String wallet = "A vintage leather wallet with a wooden table";

            SearchResults defaults = backend.search(
                    moreLikeThis(
                            OptionalInt.empty(), Optional.of(wallet), 2, 5, OptionalInt.empty(), false, 0, List.of()),
                    10);
            assertThat(defaults.moreLikeThisTerms().orElseThrow())
                    .hasSize(4)
                    .allSatisfy(term -> assertThat(term.term()).isEqualTo("a"));

            SearchResults rarest = backend.search(
                    moreLikeThis(
                            OptionalInt.empty(),
                            Optional.of(wallet),
                            1,
                            5,
                            OptionalInt.empty(),
                            false,
                            0,
                            List.of("a", "with")),
                    10);
            List<LikeTerm> terms = rarest.moreLikeThisTerms().orElseThrow();
            assertThat(terms)
                    .extracting(LikeTerm::term)
                    .containsOnly("vintage", "wooden", "wallet", "table", "leather");
            assertThat(terms.subList(0, 5)).extracting(LikeTerm::field).containsOnly("title");
            assertThat(terms.subList(0, 5))
                    .extracting(LikeTerm::term)
                    .containsExactlyInAnyOrder("vintage", "wooden", "wallet", "table", "leather");

            SearchResults longWords = backend.search(
                    moreLikeThis(
                            OptionalInt.empty(),
                            Optional.of(wallet),
                            1,
                            5,
                            OptionalInt.empty(),
                            false,
                            7,
                            List.of("a", "with")),
                    10);
            assertThat(longWords.moreLikeThisTerms().orElseThrow())
                    .extracting(LikeTerm::term)
                    .containsOnly("vintage", "leather");
        }

        @Test
        void regexOverStoredValues() throws Exception {
            RealBackend.StoredScan skus =
                    backend.storedScan(new StartStoredScanParams("sku\\d{4}", false, Optional.empty(), false));
            assertThat(skus.result().matchedValues()).isEqualTo(980);
            assertThat(skus.result().matchedDocs()).isEqualTo(486);
            assertThat(skus.hits()).extracting(StoredScanHit::field).containsOnly("body", "tagged", "note");
            // The spans are byte offsets into the whole value.
            StoredScanHit hit = skus.hits().getFirst();
            String value = backend.call(
                            Methods.GET_STORED_VALUE,
                            new GetStoredValueParams(hit.docId(), hit.field(), hit.valueIndex()))
                    .value()
                    .value();
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(new String(
                            bytes,
                            hit.spans().getFirst().start(),
                            hit.spans().getFirst().end()
                                    - hit.spans().getFirst().start(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .matches("sku\\d{4}");

            RealBackend.StoredScan events =
                    backend.storedScan(new StartStoredScanParams("\"event\":", false, Optional.empty(), false));
            assertThat(events.hits())
                    .extracting(StoredScanHit::docId, StoredScanHit::field, StoredScanHit::matchCount)
                    .containsExactly(tuple(42, "_source", 3000));
            assertThat(events.hits().getFirst().spans()).hasSize(100);

            RealBackend.StoredScan deleted =
                    backend.storedScan(new StartStoredScanParams("DELETED", true, Optional.empty(), true));
            assertThat(deleted.hits())
                    .extracting(StoredScanHit::docId, StoredScanHit::field, StoredScanHit::deleted)
                    .containsExactly(tuple(74, "deleted_only", true));
            assertThat(backend.storedScan(
                                    new StartStoredScanParams(".", false, Optional.of(List.of("deleted_only")), false))
                            .hits())
                    .isEmpty();

            RealBackend.StoredScan slow = backend.storedScan(
                    new StartStoredScanParams("(.*.){30}x", false, Optional.of(List.of("odd_text")), false));
            assertThat(slow.result().timedOut()).extracting(ref -> ref.docId()).containsExactly(7);

            backend.fails(
                    Methods.START_STORED_SCAN,
                    new StartStoredScanParams("(sku", false, Optional.empty(), false),
                    ErrorCode.INVALID_QUERY);
        }

        private List<Token> analyze(Analyzer analyzer, String text) throws Exception {
            return backend.call(Methods.ANALYZE, new AnalyzeParams(analyzer, text, Optional.empty()))
                    .stages()
                    .getLast()
                    .tokens();
        }

        private static List<String> terms(List<Token> tokens) {
            return tokens.stream().map(Token::term).toList();
        }

        private static Analyzer custom(List<AnalysisComponent> charFilters, List<AnalysisComponent> tokenFilters) {
            return new Analyzer(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new CustomAnalyzer(charFilters, component("standard"), tokenFilters, Optional.empty())),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    List.of());
        }

        private static AnalysisComponent component(String name) {
            return new AnalysisComponent(name, Map.of());
        }
    }

    /**
     * More-Like-This in the fields that the search screen starts with: those with positions (see
     * {@code LikeSettings.of}).
     */
    private SearchQuery moreLikeThis(
            OptionalInt docId,
            Optional<String> text,
            int minTermFreq,
            int minDocFreq,
            OptionalInt maxDocFreq,
            boolean boost,
            int minWordLen,
            List<String> stopWords)
            throws Exception {
        List<String> fields = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams()).fields().stream()
                .filter(field -> field.indexOptions() == IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
                        || field.indexOptions() == IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)
                .map(IndexField::name)
                .toList();
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new MoreLikeThisQuery(
                        docId,
                        text,
                        fields,
                        minTermFreq,
                        minDocFreq,
                        maxDocFreq,
                        25,
                        boost,
                        STANDARD,
                        minWordLen,
                        OptionalInt.empty(),
                        stopWords)),
                Optional.empty(),
                Optional.empty());
    }

    private GetDocumentResult document(int docId) throws Exception {
        return backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(docId));
    }

    private static StoredValue stored(GetDocumentResult document, String field) {
        return document.fields().stream()
                .filter(f -> f.name().equals(field))
                .findFirst()
                .orElseThrow()
                .stored()
                .getFirst();
    }

    private static BytesValue text(String text) {
        return new BytesValue(text, false);
    }
}
