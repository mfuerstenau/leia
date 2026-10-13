package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.Queries.Operator;
import io.github.mfuerstenau.leia.core.SearchResults.Hit;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The options of both query parsers, and point fields in the standard parser (ADR 0011). */
class QueryParsersTest {

    private static final Similarity BM25 = Similarities.bm25(1.2f, 0.75f);

    private static final ParserOptions DEFAULTS = ParserOptions.defaults(Operator.OR);

    /** Lucene's analyzers are thread-safe; this one lives as long as the tests. */
    private static final Analyzer ANALYZER = new StandardAnalyzer();

    @TempDir
    Path tmp;

    @Test
    void leadingWildcardsNeedTheOption() throws Exception {
        assertThatThrownBy(() -> classic("*ox", DEFAULTS))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("'*' or '?' not allowed as first character");
        assertThatThrownBy(() -> standard("*ox", DEFAULTS, Map.of())).isInstanceOf(InvalidQueryException.class);

        ParserOptions leading = new ParserOptions(Operator.OR, true, 0, 0, true);
        assertThat(classic("*ox", leading)).hasToString("body:*ox");
        assertThat(standard("*ox", leading, Map.of())).hasToString("body:*ox");
    }

    @Test
    void phrasesWithoutSlopGetTheDefault() throws Exception {
        ParserOptions slop = new ParserOptions(Operator.OR, false, 3, 0, true);

        assertThat(classic("\"quick fox\"", DEFAULTS)).hasToString("body:\"quick fox\"");
        assertThat(classic("\"quick fox\"", slop)).hasToString("body:\"quick fox\"~3");
        assertThat(classic("\"quick fox\"~1", slop)).hasToString("body:\"quick fox\"~1");
        assertThat(standard("\"quick fox\"", slop, Map.of())).hasToString("body:\"quick fox\"~3");
    }

    @Test
    void fuzzyTermsWithoutDistanceGetTheDefaults() throws Exception {
        ParserOptions prefix = new ParserOptions(Operator.OR, false, 0, 2, true);
        ClassicOptions oneEdit = new ClassicOptions(1, false, false);

        assertThat(classic("fox~", DEFAULTS)).hasToString("body:fox~2");
        assertThat(Queries.classic("fox~", "body", prefix, oneEdit, ANALYZER))
                .isInstanceOfSatisfying(FuzzyQuery.class, q -> {
                    assertThat(q.getMaxEdits()).isEqualTo(1);
                    assertThat(q.getPrefixLength()).isEqualTo(2);
                });
        assertThat(Queries.classic("fox~2", "body", prefix, oneEdit, ANALYZER)).hasToString("body:fox~2");
        // The standard parser takes the prefix length, but always allows 2 edits.
        assertThat(standard("fox~", prefix, Map.of())).isInstanceOfSatisfying(FuzzyQuery.class, q -> {
            assertThat(q.getMaxEdits()).isEqualTo(2);
            assertThat(q.getPrefixLength()).isEqualTo(2);
        });
    }

    @Test
    void positionIncrementsKeepTheGapsOfStopWords() throws Exception {
        // StandardAnalyzer has no stop words by default, so the gap shows with a stop filter only;
        // without one, both settings give the same phrase.
        ParserOptions noIncrements = new ParserOptions(Operator.OR, false, 0, 0, false);

        assertThat(classic("\"the fox\"", noIncrements))
                .hasToString(classic("\"the fox\"", DEFAULTS).toString());
    }

    @Test
    void classicSplitsOnWhitespaceAndGeneratesPhrasesOnRequest() throws Exception {
        assertThat(classic("wi-fi", DEFAULTS)).hasToString("body:wi body:fi");
        assertThat(Queries.classic("wi-fi", "body", DEFAULTS, new ClassicOptions(2, true, true), ANALYZER))
                .hasToString("body:\"wi fi\"");
        assertThatThrownBy(() -> new ClassicOptions(2, false, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionsOutOfRangeAreRejected() {
        assertThatThrownBy(() -> new ParserOptions(Operator.OR, false, -1, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParserOptions(Operator.OR, false, 0, -1, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassicOptions(3, false, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassicOptions(-1, false, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStandardParserMatchesRangesOnPoints() throws Exception {
        Map<String, PointType> points = Map.of(
                "quantity", PointType.INT,
                "timestamp", PointType.LONG,
                "rating", PointType.FLOAT,
                "price", PointType.DOUBLE);
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            assertThat(docs(index, standard("quantity:[2 TO 3]", DEFAULTS, points)))
                    .containsExactly(1, 2);
            assertThat(docs(index, standard("quantity:3", DEFAULTS, points))).containsExactly(2);
            assertThat(docs(index, standard("quantity>=2", DEFAULTS, points))).containsExactly(1, 2);
            assertThat(docs(index, standard("quantity<2", DEFAULTS, points))).containsExactly(0);
            assertThat(docs(index, standard("timestamp:[1000 TO 2000]", DEFAULTS, points)))
                    .containsExactly(0, 1);
            assertThat(docs(index, standard("rating:[2.5 TO *]", DEFAULTS, points)))
                    .containsExactly(1, 2);
            assertThat(docs(index, standard("price:[-1.5 TO 9.99]", DEFAULTS, points)))
                    .containsExactly(0, 1);
            assertThat(docs(index, standard("price:[-2 TO 100] AND body:fox", DEFAULTS, points)))
                    .containsExactly(0);
            // Without a type, the range is over text, which a point field doesn't have.
            assertThat(docs(index, standard("quantity:[2 TO 3]", DEFAULTS, Map.of())))
                    .isEmpty();
            assertThat(docs(index, classic("quantity:[2 TO 3]", DEFAULTS))).isEmpty();
        }
    }

    @Test
    void theStandardParserRejectsBadSyntaxAndNumbers() {
        assertThatThrownBy(() -> standard("body:(quick", DEFAULTS, Map.of()))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessage("Cannot parse 'body:(quick': Encountered \"<EOF>\" at column 11.");
        // Comparisons go without a colon: quantity>=2.
        assertThatThrownBy(() -> standard("quantity:>=2", DEFAULTS, Map.of("quantity", PointType.INT)))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessage("Cannot parse 'quantity:>=2': Encountered \">=\" at column 9.");
        assertThatThrownBy(() -> standard("\"open", DEFAULTS, Map.of()))
                .isInstanceOf(InvalidQueryException.class)
                .message()
                .doesNotContain("\n")
                .contains("Lexical error");
        assertThatThrownBy(() -> standard("quantity:[x TO 3]", DEFAULTS, Map.of("quantity", PointType.INT)))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void pointTypesFitFieldsOfTheirWidth() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            var quantity = index.fieldInfo("quantity").orElseThrow();
            var price = index.fieldInfo("price").orElseThrow();
            var body = index.fieldInfo("body").orElseThrow();

            assertThat(PointType.INT.fits(quantity)).isTrue();
            assertThat(PointType.FLOAT.fits(quantity)).isTrue();
            assertThat(PointType.LONG.fits(quantity)).isFalse();
            assertThat(PointType.DOUBLE.fits(price)).isTrue();
            assertThat(PointType.INT.fits(price)).isFalse();
            assertThat(PointType.INT.fits(body)).isFalse();
            assertThat(index.fieldInfo("nothing")).isEmpty();
        }
    }

    @Test
    void rangeBoundsBecomeDatesWithAResolution() throws Exception {
        ParserOptions day = dates(DateTools.Resolution.DAY, Locale.US, "UTC");

        for (Parser parser : Parser.values()) {
            assertThat(parser.parse("published:[1/1/26 TO 1/31/26]", day))
                    .hasToString("published:[20260101 TO 20260131]");
            // An exclusive upper bound is the start of its day, an inclusive one its end.
            assertThat(parser.parse("published:{1/1/26 TO 1/31/26}", day))
                    .hasToString("published:{20260101 TO 20260131}");
            assertThat(parser.parse("published:[* TO 1/31/26]", day)).hasToString("published:[* TO 20260131]");
            assertThat(parser.parse("published:[1/1/26 TO 1/1/26]", dates(DateTools.Resolution.HOUR, Locale.US, "UTC")))
                    .hasToString("published:[2026010100 TO 2026010123]");
            // Without a resolution, the bounds stay text.
            assertThat(parser.parse(
                            "published:[1/1/26 TO 1/31/26]",
                            DEFAULTS.withDates(
                                    Optional.empty(), Optional.of(Locale.US), Optional.of(ZoneId.of("UTC")))))
                    .hasToString("published:[1/1/26 TO 1/31/26]");
        }
    }

    @Test
    void rangeBoundsAreReadInTheLocale() throws Exception {
        ParserOptions german = dates(DateTools.Resolution.DAY, Locale.GERMANY, "UTC");
        ParserOptions american = dates(DateTools.Resolution.DAY, Locale.US, "UTC");

        for (Parser parser : Parser.values()) {
            assertThat(parser.parse("published:[01.01.26 TO 31.01.26]", german))
                    .hasToString("published:[20260101 TO 20260131]");
            assertThat(parser.parse("published:[01.01.26 TO 31.01.26]", american))
                    .hasToString("published:[01.01.26 TO 31.01.26]");
        }
    }

    @Test
    void rangeBoundsAreReadInTheTimeZoneWhateverTheJvmsIs() throws Exception {
        // DateTools writes GMT: midnight in Berlin is the day before, the end of a day in New York the day after.
        for (Parser parser : Parser.values()) {
            assertThat(parser.parse(
                            "published:[1/1/26 TO 1/31/26]",
                            dates(DateTools.Resolution.DAY, Locale.US, "Europe/Berlin")))
                    .hasToString("published:[20251231 TO 20260131]");
            assertThat(parser.parse(
                            "published:[1/1/26 TO 1/31/26]",
                            dates(DateTools.Resolution.DAY, Locale.US, "America/New_York")))
                    .hasToString("published:[20260101 TO 20260201]");
        }
    }

    @Test
    void withoutATimeZoneDatesAreReadAsLuceneReadsThem() throws Exception {
        ParserOptions options =
                DEFAULTS.withDates(Optional.of(DateTools.Resolution.MINUTE), Optional.of(Locale.US), Optional.empty());
        String expression = "published:[1/1/26 TO 1/31/26] OR published:{1/1/26 TO 1/2/26}";

        QueryParser classic = new QueryParser("body", ANALYZER);
        classic.setDateResolution(DateTools.Resolution.MINUTE);
        classic.setLocale(Locale.US);
        assertThat(classic(expression, options)).isEqualTo(classic.parse(expression));
        StandardQueryParser standard = new StandardQueryParser(ANALYZER);
        standard.setDateResolution(DateTools.Resolution.MINUTE);
        standard.setLocale(Locale.US);
        assertThat(standard(expression, options, Map.of())).isEqualTo(standard.parse(expression, "body"));
    }

    @Test
    void aFieldsOwnResolutionWinsOverTheOthers() throws Exception {
        ParserOptions hours = dates(DateTools.Resolution.DAY, Locale.US, "UTC")
                .withFieldDateResolutions(Map.of("updated", DateTools.Resolution.HOUR));
        ParserOptions onlyUpdated = DEFAULTS.withDates(
                        Optional.empty(), Optional.of(Locale.US), Optional.of(ZoneId.of("UTC")))
                .withFieldDateResolutions(Map.of("updated", DateTools.Resolution.HOUR));
        String expression = "+published:[1/1/26 TO 1/31/26] +updated:[1/1/26 TO 1/1/26]";

        for (Parser parser : Parser.values()) {
            assertThat(parser.parse(expression, hours))
                    .hasToString("+published:[20260101 TO 20260131] +updated:[2026010100 TO 2026010123]");
            // Without a resolution for every field, only the field with its own converts.
            assertThat(parser.parse(expression, onlyUpdated))
                    .hasToString("+published:[1/1/26 TO 1/31/26] +updated:[2026010100 TO 2026010123]");
        }
    }

    @Test
    void withoutATimeZoneFieldResolutionsAreReadAsLuceneReadsThem() throws Exception {
        ParserOptions options = DEFAULTS.withDates(
                        Optional.of(DateTools.Resolution.DAY), Optional.of(Locale.US), Optional.empty())
                .withFieldDateResolutions(Map.of("updated", DateTools.Resolution.MINUTE));
        String expression = "published:[1/1/26 TO 1/31/26] OR updated:[1/1/26 TO 1/2/26]";

        QueryParser classic = new QueryParser("body", ANALYZER);
        classic.setDateResolution(DateTools.Resolution.DAY);
        classic.setDateResolution("updated", DateTools.Resolution.MINUTE);
        classic.setLocale(Locale.US);
        assertThat(classic(expression, options)).isEqualTo(classic.parse(expression));
        StandardQueryParser standard = new StandardQueryParser(ANALYZER);
        standard.setDateResolution(DateTools.Resolution.DAY);
        standard.setDateResolutionMap(Map.of("updated", DateTools.Resolution.MINUTE));
        standard.setLocale(Locale.US);
        assertThat(standard(expression, options, Map.of())).isEqualTo(standard.parse(expression, "body"));
    }

    @Test
    void dateRangesMatchDateToolsTerms() throws Exception {
        try (ReadOnlyIndex index = ReadOnlyIndex.open(writeIndex())) {
            ParserOptions day = dates(DateTools.Resolution.DAY, Locale.US, "UTC");
            for (Parser parser : Parser.values()) {
                assertThat(docs(index, parser.parse("published:[1/1/26 TO 1/31/26]", day)))
                        .containsExactly(1);
                assertThat(docs(index, parser.parse("published:[12/31/25 TO 2/1/26]", day)))
                        .containsExactly(0, 1, 2);
            }
        }
    }

    /** Both query parsers, without point fields. */
    private enum Parser {
        CLASSIC,
        STANDARD;

        Query parse(String expression, ParserOptions options) throws InvalidQueryException {
            return switch (this) {
                case CLASSIC -> classic(expression, options);
                case STANDARD -> standard(expression, options, Map.of());
            };
        }
    }

    private static ParserOptions dates(DateTools.Resolution resolution, Locale locale, String zone) {
        return DEFAULTS.withDates(Optional.of(resolution), Optional.of(locale), Optional.of(ZoneId.of(zone)));
    }

    private static Query classic(String expression, ParserOptions options) throws InvalidQueryException {
        return Queries.classic(expression, "body", options, ClassicOptions.DEFAULTS, ANALYZER);
    }

    private static Query standard(String expression, ParserOptions options, Map<String, PointType> points)
            throws InvalidQueryException {
        return Queries.standard(expression, "body", options, points, ANALYZER);
    }

    private static java.util.List<Integer> docs(ReadOnlyIndex index, Query query)
            throws IOException, InvalidQueryException {
        return index
                .search(query, BM25, Optional.empty(), 0, 10, true, Optional.empty(), ScanProgress.NONE)
                .hits()
                .stream()
                .map(Hit::docId)
                .sorted()
                .toList();
    }

    /**
     * Three documents with a point of each type: quantity 1, 2, 3; price -1.5, 9.99, 100.5; …; and a
     * {@code DateTools} day: published 2025-12-31, 2026-01-31, 2026-02-01.
     */
    private Path writeIndex() throws IOException {
        Path dir = tmp.resolve("points");
        try (FSDirectory directory = FSDirectory.open(dir);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(doc("a fox", 1, 1000L, 1.5f, -1.5, "20251231"));
            writer.addDocument(doc("a dog", 2, 2000L, 2.5f, 9.99, "20260131"));
            writer.addDocument(doc("a fox and a dog", 3, 3000L, 3.5f, 100.5, "20260201"));
        }
        return dir;
    }

    private static Document doc(
            String body, int quantity, long timestamp, float rating, double price, String published) {
        Document doc = new Document();
        doc.add(new StringField("published", published, Field.Store.NO));
        doc.add(new TextField("body", body, Field.Store.NO));
        doc.add(new IntPoint("quantity", quantity));
        doc.add(new LongPoint("timestamp", timestamp));
        doc.add(new FloatPoint("rating", rating));
        doc.add(new DoublePoint("price", price));
        return doc;
    }
}
