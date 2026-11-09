package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.KnnVector;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.StandardQuery;
import io.github.mfuerstenau.leia.protocol.search.TermQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * The query of {@code search} and {@code explain}, made from their shared arguments: a query in
 * Lucene's syntax, a term, doc values or the nearest vectors, with the parser and similarity the search screen starts
 * with (ADR 0100, ADR 0104, ADR 0107, ADR 0109, ADR 0111).
 */
final class Queries {

    private Queries() {}

    /** The arguments that make a query, which {@code search} and {@code explain} share. */
    interface Arguments {
        @Nullable
        String query();

        @Nullable
        Term term();

        @Nullable
        DocValues docValues();

        @Nullable
        Knn knn();

        @Nullable
        List<PointFieldType> pointTypes();

        @Nullable
        String defaultField();

        @Nullable
        String analyzer();

        @Nullable
        List<FieldAnalyzer> fieldAnalyzers();

        @Nullable
        QueryOperator defaultOperator();
    }

    /** Lucene's defaults of the classic parser, as the search screen starts with them. */
    static final ParserOptions PARSER_DEFAULTS =
            new ParserOptions(false, 0, 0, true, Optional.empty(), List.of(), Optional.empty(), Optional.empty());

    static final int FUZZY_MAX_EDITS = 2;

    static final Similarity BM25 =
            new Similarity(Optional.of(new Bm25Similarity(1.2f, 0.75f)), Optional.empty(), Optional.empty());

    record FieldAnalyzer(String field, String analyzer) {}

    record PointFieldType(String field, PointType type) {}

    record DocValues(
            String field,

            @Description("How a numeric field's values are encoded; LONG by default. DOUBLE and FLOAT are raw bits,"
                    + " SORTABLE_DOUBLE and SORTABLE_FLOAT as Lucene's DoubleField and FloatField write them;"
                    + " get_document's asStored says which. Absent for a field of bytes")
            @Nullable
            DocValuesNumber number,

            @Description("Match documents with a value equal to one of these: decimal text for a number. Give"
                    + " exactly one of values, range and prefix")
            @Nullable
            List<String> values,

            @Description("Match documents with a value in this range. Give exactly one of values, range and prefix")
            @Nullable
            Range range,

            @Description("Match documents with a value that starts with this, for a field of bytes. Give exactly one"
                    + " of values, range and prefix")
            @Nullable
            String prefix,

            @Description("Whether values, the bounds and prefix are bytes in hex; false by default") @Nullable
            Boolean binary) {}

    record Range(
            @Description("The lower bound; absent for none") @Nullable
            String lower,

            @Description("The upper bound; absent for none") @Nullable
            String upper,

            @Description("Whether the lower bound matches; true by default") @Nullable
            Boolean includeLower,

            @Description("Whether the upper bound matches; true by default") @Nullable
            Boolean includeUpper) {}

    record Knn(
            @Description("A field with KNN vectors; index_info shows vectorDimension, vectorEncoding and"
                    + " vectorSimilarity")
            String field,

            @Description("The target vector. For a BYTE field the values are whole numbers from -128 to 127. Give"
                    + " exactly one of vector and docId")
            @Nullable
            List<Double> vector,

            @Description("A document whose vector in field is the target, such as a hit's docId; it may be deleted."
                    + " Give exactly one of vector and docId")
            @Nullable
            Integer docId,

            @Description("How many nearest documents to find, from 1 to " + KnnQuery.MAX_K + "; in search offset +"
                    + " limit by default, so a page is the k nearest and a higher k lets later pages follow; in"
                    + " explain the most, so pass the search's k to explain a hit as ranked. Not with exact")
            @Nullable
            Integer k,

            @Description("Whether to score every document with a vector instead of searching the graph, and so"
                    + " find all of them as hits; false by default")
            @Nullable
            Boolean exact,

            @Description("Search only documents that match this query in Lucene's syntax, parsed with defaultField,"
                    + " analyzer, fieldAnalyzers and defaultOperator; ranges on point fields don't work")
            @Nullable
            String filter) {}

    record Term(
            String field,

            @Description("The term as text, or in hex with binary")
            String value,

            @Description("Whether value is the term's bytes in hex; false by default") @Nullable
            Boolean binary) {}

    /** The index's fields, fetched by the first who asks. */
    static final class Stats {
        private final Call call;
        private @Nullable GetIndexStatsResult stats;

        Stats(Call call) {
            this.call = call;
        }

        List<IndexField> fields() throws ToolException, Jobs.Cancelled {
            GetIndexStatsResult result = stats;
            if (result == null) {
                result = call.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());
                stats = result;
            }
            return result.fields();
        }

        Optional<DocValuesType> docValuesType(String field) throws ToolException, Jobs.Cancelled {
            return fields().stream()
                    .filter(candidate -> candidate.name().equals(field))
                    .map(IndexField::docValuesType)
                    .findFirst();
        }

        Optional<IndexOptions> indexOptions(String field) throws ToolException, Jobs.Cancelled {
            return fields().stream()
                    .filter(candidate -> candidate.name().equals(field))
                    .map(IndexField::indexOptions)
                    .findFirst();
        }
    }

    /**
     * The query of {@code arguments}.
     *
     * @param defaultK the {@code k} of a KNN query that gives none
     */
    static SearchQuery query(Arguments arguments, Call call, Stats stats, int defaultK)
            throws ToolException, Jobs.Cancelled {
        String text = arguments.query();
        Term term = arguments.term();
        DocValues docValues = arguments.docValues();
        Knn knn = arguments.knn();
        int given =
                (text != null ? 1 : 0) + (term != null ? 1 : 0) + (docValues != null ? 1 : 0) + (knn != null ? 1 : 0);
        if (given != 1) {
            throw ToolError.invalid("Give exactly one of query, term, docValues and knn");
        }
        if (arguments.pointTypes() != null && text == null) {
            throw ToolError.invalid("pointTypes goes with query only");
        }
        if ((term != null || docValues != null)
                && (arguments.analyzer() != null || arguments.fieldAnalyzers() != null)) {
            throw ToolError.invalid(
                    "analyzer and fieldAnalyzers go with query only: a term or doc values aren't analyzed");
        }
        if (knn != null) {
            return knn(knn, arguments, call, stats, defaultK);
        }
        if (term != null) {
            return new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new TermQuery(term.field(), new BytesValue(term.value(), Given.isTrue(term.binary())))),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }
        if (docValues != null) {
            return new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(docValues(docValues, stats)),
                    Optional.empty());
        }
        if (text == null) {
            throw new IllegalStateException("exactly one query was given, and it isn't a term, doc values or knn");
        }
        String defaultField = arguments.defaultField();
        if (defaultField == null) {
            throw ToolError.invalid("A query needs a defaultField: the field of terms that name none");
        }
        QueryOperator operator = operator(arguments);
        Analyzer analyzer = analyzer(arguments, call);
        Optional<OpenIndexResult> opened = call.session().opened();
        if (opened.isPresent() && opened.get().features().contains(Feature.STANDARD_SEARCH)) {
            StandardQuery standard = new StandardQuery(
                    text, defaultField, operator, PARSER_DEFAULTS, points(arguments, opened.get(), stats), analyzer);
            return new SearchQuery(
                    Optional.empty(),
                    Optional.of(standard),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }
        if (arguments.pointTypes() != null) {
            throw ToolError.invalid("pointTypes needs the standard parser, which the Lucene of this index lacks");
        }
        ClassicQuery classic = new ClassicQuery(
                text, defaultField, operator, PARSER_DEFAULTS, FUZZY_MAX_EDITS, false, false, analyzer);
        return new SearchQuery(
                Optional.of(classic),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static SearchQuery knn(Knn knn, Arguments arguments, Call call, Stats stats, int defaultK)
            throws ToolException, Jobs.Cancelled {
        Optional<OpenIndexResult> opened = call.session().opened();
        if (opened.isPresent() && !opened.get().features().contains(Feature.KNN_VECTORS)) {
            throw ToolError.notSupported("knn needs the Lucene 9 or 10 runtime, which reads KNN vectors");
        }
        boolean exact = Given.isTrue(knn.exact());
        Integer k = knn.k();
        if (exact && k != null) {
            throw ToolError.invalid("k doesn't go with exact: every document with a vector is a hit");
        }
        String filterText = knn.filter();
        if (filterText == null && (arguments.analyzer() != null || arguments.fieldAnalyzers() != null)) {
            throw ToolError.invalid("analyzer and fieldAnalyzers go with a knn filter only");
        }
        Optional<ClassicQuery> filter = Optional.empty();
        if (filterText != null) {
            String defaultField = arguments.defaultField();
            if (defaultField == null) {
                throw ToolError.invalid("A knn filter needs a defaultField: the field of terms that name none");
            }
            filter = Optional.of(new ClassicQuery(
                    filterText,
                    defaultField,
                    operator(arguments),
                    PARSER_DEFAULTS,
                    FUZZY_MAX_EDITS,
                    false,
                    false,
                    analyzer(arguments, call)));
        }
        List<Double> components = knn.vector();
        Optional<KnnVector> vector =
                components != null ? Optional.of(vector(knn.field(), components, stats)) : Optional.empty();
        return new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new KnnQuery(
                        knn.field(),
                        Optionals.optionalInt(knn.docId()),
                        vector,
                        exact ? OptionalInt.empty() : OptionalInt.of(k != null ? k : defaultK),
                        exact,
                        filter)));
    }

    /**
     * The target vector in the encoding of the field; FLOAT32 for a field without vectors, which the
     * server refuses.
     */
    private static KnnVector vector(String field, List<Double> components, Stats stats)
            throws ToolException, Jobs.Cancelled {
        VectorEncoding encoding = stats.fields().stream()
                .filter(candidate -> candidate.name().equals(field))
                .flatMap(candidate -> candidate.vectorEncoding().stream())
                .findFirst()
                .orElse(VectorEncoding.FLOAT32);
        if (encoding == VectorEncoding.FLOAT32) {
            return new KnnVector(
                    encoding, components.stream().map(Double::floatValue).toList(), List.of());
        }
        List<Byte> bytes = new ArrayList<>();
        for (double component : components) {
            if (component != Math.rint(component) || component < Byte.MIN_VALUE || component > Byte.MAX_VALUE) {
                throw ToolError.invalid("The vector of the BYTE field " + field
                        + " takes whole numbers from -128 to 127, not " + component);
            }
            bytes.add((byte) component);
        }
        return new KnnVector(encoding, List.of(), bytes);
    }

    /**
     * The point fields of the query: every field of one dimension and 4 or 8 bytes, as INT or LONG
     * as the search screen guesses (ADR 0107), and {@code pointTypes} as it says.
     */
    private static List<PointField> points(Arguments arguments, OpenIndexResult opened, Stats stats)
            throws ToolException, Jobs.Cancelled {
        Map<String, PointType> types = new LinkedHashMap<>();
        if (opened.features().contains(Feature.POINTS)) {
            for (IndexField field : stats.fields()) {
                if (field.pointDimensionCount() == 1 && field.pointNumBytes() == Integer.BYTES) {
                    types.put(field.name(), PointType.INT);
                } else if (field.pointDimensionCount() == 1 && field.pointNumBytes() == Long.BYTES) {
                    types.put(field.name(), PointType.LONG);
                }
            }
        }
        for (PointFieldType given : Given.orEmpty(arguments.pointTypes())) {
            types.put(given.field(), given.type());
        }
        return types.entrySet().stream()
                .map(entry -> new PointField(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static DocValuesQuery docValues(DocValues docValues, Stats stats) throws ToolException, Jobs.Cancelled {
        boolean hex = Given.isTrue(docValues.binary());
        Optional<DocValuesNumber> number = Optional.ofNullable(docValues.number());
        if (number.isEmpty()) {
            boolean numeric = stats.docValuesType(docValues.field())
                    .filter(type -> type == DocValuesType.NUMERIC || type == DocValuesType.SORTED_NUMERIC)
                    .isPresent();
            number = numeric ? Optional.of(DocValuesNumber.LONG) : Optional.empty();
        }
        return new DocValuesQuery(
                docValues.field(),
                number,
                Optional.ofNullable(docValues.values())
                        .map(values -> values.stream()
                                .map(value -> new BytesValue(value, hex))
                                .toList()),
                Optional.ofNullable(docValues.range())
                        .map(range -> new DocValuesRange(
                                Optional.ofNullable(range.lower()).map(value -> new BytesValue(value, hex)),
                                Optional.ofNullable(range.upper()).map(value -> new BytesValue(value, hex)),
                                !Boolean.FALSE.equals(range.includeLower()),
                                !Boolean.FALSE.equals(range.includeUpper()))),
                Optional.ofNullable(docValues.prefix()).map(value -> new BytesValue(value, hex)));
    }

    static Analyzer analyzer(Arguments arguments, Call call) throws ToolException, Jobs.Cancelled {
        String named = arguments.analyzer();
        if (named == null && arguments.fieldAnalyzers() == null) {
            return Analyzers.STANDARD;
        }
        Analyzers.Names names = new Analyzers.Names(call);
        Analyzer base = named != null ? Analyzers.named(named, names) : Analyzers.STANDARD;
        List<io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer> fields = new ArrayList<>();
        for (FieldAnalyzer field : Given.orEmpty(arguments.fieldAnalyzers())) {
            fields.add(new io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer(
                    field.field(), Analyzers.named(field.analyzer(), names)));
        }
        return Analyzers.of(base.standard().orElse(null), base.preset().orElse(null), fields);
    }

    /** The default operator of {@code arguments}: OR without one. */
    static QueryOperator operator(Arguments arguments) {
        QueryOperator operator = arguments.defaultOperator();
        return operator != null ? operator : QueryOperator.OR;
    }
}
