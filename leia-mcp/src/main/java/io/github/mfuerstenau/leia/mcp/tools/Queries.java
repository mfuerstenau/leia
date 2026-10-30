package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesNumber;
import io.github.mfuerstenau.leia.protocol.search.DocValuesQuery;
import io.github.mfuerstenau.leia.protocol.search.DocValuesRange;
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

/**
 * The query of {@code search} and {@code explain}, made from their shared arguments: a query in
 * Lucene's syntax, a term or doc values, with the parser and similarity the search screen starts
 * with (ADR 0100, ADR 0104, ADR 0107, ADR 0109).
 */
final class Queries {

    private Queries() {}

    /** The arguments that make a query, which {@code search} and {@code explain} share. */
    interface Arguments {
        Optional<String> query();

        Optional<Term> term();

        Optional<DocValues> docValues();

        Optional<List<PointFieldType>> pointTypes();

        Optional<String> defaultField();

        Optional<String> analyzer();

        Optional<List<FieldAnalyzer>> fieldAnalyzers();

        Optional<QueryOperator> defaultOperator();
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
            Optional<DocValuesNumber> number,

            @Description("Match documents with a value equal to one of these: decimal text for a number. Give"
                    + " exactly one of values, range and prefix")
            Optional<List<String>> values,

            @Description("Match documents with a value in this range. Give exactly one of values, range and prefix")
            Optional<Range> range,

            @Description("Match documents with a value that starts with this, for a field of bytes. Give exactly one"
                    + " of values, range and prefix")
            Optional<String> prefix,

            @Description("Whether values, the bounds and prefix are bytes in hex; false by default")
            Optional<Boolean> binary) {}

    record Range(
            @Description("The lower bound; absent for none") Optional<String> lower,

            @Description("The upper bound; absent for none") Optional<String> upper,

            @Description("Whether the lower bound matches; true by default")
            Optional<Boolean> includeLower,

            @Description("Whether the upper bound matches; true by default")
            Optional<Boolean> includeUpper) {}

    record Term(
            String field,

            @Description("The term as text, or in hex with binary")
            String value,

            @Description("Whether value is the term's bytes in hex; false by default")
            Optional<Boolean> binary) {}

    /** The index's fields, fetched by the first who asks. */
    static final class Stats {
        private final Call call;
        private GetIndexStatsResult stats;

        Stats(Call call) {
            this.call = call;
        }

        List<IndexField> fields() throws ToolException, Jobs.Cancelled {
            if (stats == null) {
                stats = call.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams());
            }
            return stats.fields();
        }

        Optional<DocValuesType> docValuesType(String field) throws ToolException, Jobs.Cancelled {
            return fields().stream()
                    .filter(candidate -> candidate.name().equals(field))
                    .map(IndexField::docValuesType)
                    .findFirst();
        }
    }

    static SearchQuery query(Arguments arguments, Call call, Stats stats) throws ToolException, Jobs.Cancelled {
        int given = (arguments.query().isPresent() ? 1 : 0)
                + (arguments.term().isPresent() ? 1 : 0)
                + (arguments.docValues().isPresent() ? 1 : 0);
        if (given != 1) {
            throw ToolError.invalid("Give exactly one of query, term and docValues");
        }
        if (arguments.pointTypes().isPresent() && arguments.query().isEmpty()) {
            throw ToolError.invalid("pointTypes goes with query only");
        }
        if (arguments.term().isPresent() || arguments.docValues().isPresent()) {
            if (arguments.analyzer().isPresent() || arguments.fieldAnalyzers().isPresent()) {
                throw ToolError.invalid(
                        "analyzer and fieldAnalyzers go with query only: a term or doc values aren't analyzed");
            }
        }
        if (arguments.term().isPresent()) {
            Term term = arguments.term().get();
            return new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new TermQuery(
                            term.field(),
                            new BytesValue(term.value(), term.binary().orElse(false)))),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }
        if (arguments.docValues().isPresent()) {
            return new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(docValues(arguments.docValues().get(), stats)),
                    Optional.empty());
        }
        String defaultField = arguments
                .defaultField()
                .orElseThrow(
                        () -> ToolError.invalid("A query needs a defaultField: the field of terms that name none"));
        String text = arguments.query().get();
        QueryOperator operator = arguments.defaultOperator().orElse(QueryOperator.OR);
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
        if (arguments.pointTypes().isPresent()) {
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
        for (PointFieldType given : arguments.pointTypes().orElse(List.of())) {
            types.put(given.field(), given.type());
        }
        return types.entrySet().stream()
                .map(entry -> new PointField(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static DocValuesQuery docValues(DocValues docValues, Stats stats) throws ToolException, Jobs.Cancelled {
        boolean hex = docValues.binary().orElse(false);
        Optional<DocValuesNumber> number = docValues.number();
        if (number.isEmpty()) {
            boolean numeric = stats.docValuesType(docValues.field())
                    .filter(type -> type == DocValuesType.NUMERIC || type == DocValuesType.SORTED_NUMERIC)
                    .isPresent();
            number = numeric ? Optional.of(DocValuesNumber.LONG) : Optional.empty();
        }
        return new DocValuesQuery(
                docValues.field(),
                number,
                docValues
                        .values()
                        .map(values -> values.stream()
                                .map(value -> new BytesValue(value, hex))
                                .toList()),
                docValues
                        .range()
                        .map(range -> new DocValuesRange(
                                range.lower().map(value -> new BytesValue(value, hex)),
                                range.upper().map(value -> new BytesValue(value, hex)),
                                range.includeLower().orElse(true),
                                range.includeUpper().orElse(true))),
                docValues.prefix().map(value -> new BytesValue(value, hex)));
    }

    static Analyzer analyzer(Arguments arguments, Call call) throws ToolException, Jobs.Cancelled {
        if (arguments.analyzer().isEmpty() && arguments.fieldAnalyzers().isEmpty()) {
            return Analyzers.STANDARD;
        }
        Analyzers.Names names = new Analyzers.Names(call);
        Analyzer base = arguments.analyzer().isPresent()
                ? Analyzers.named(arguments.analyzer().get(), names)
                : Analyzers.STANDARD;
        List<io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer> fields = new ArrayList<>();
        for (FieldAnalyzer field : arguments.fieldAnalyzers().orElse(List.of())) {
            fields.add(new io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer(
                    field.field(), Analyzers.named(field.analyzer(), names)));
        }
        return Analyzers.of(base.standard(), base.preset(), fields);
    }
}
