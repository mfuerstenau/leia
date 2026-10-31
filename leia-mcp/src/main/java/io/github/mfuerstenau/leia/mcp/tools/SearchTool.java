package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.GetSearchResult;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import io.github.mfuerstenau.leia.protocol.search.TotalHitsRelation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * {@code search}: a page of the hits of a query, a term query, a doc values query or a KNN query, best score
 * first or sorted by doc values (ADR 0108), with their stored values brief (ADR 0100). The parser and similarity are the ones the
 * search screen starts with: Lucene's defaults and BM25. A query is analyzed with {@code
 * StandardAnalyzer} unless {@code analyzer} and {@code fieldAnalyzers} name others, by class name
 * with their default settings (ADR 0104). It goes through the standard parser where the runtime
 * has one, so that it makes point queries (ADR 0107).
 */
final class SearchTool implements McpTool<SearchTool.Arguments, SearchTool.Result> {

    static final int DEFAULT_LIMIT = 10;

    /** The longest value shown, in characters, unless {@code maxValueLength} says otherwise. */
    static final int DEFAULT_VALUE_LENGTH = 100;

    /**
     * The characters of values a hit shows when {@code fields} is absent: fields are taken whole
     * until the sum passes it, and the rest are named in {@code moreFields}.
     */
    static final int HIT_BUDGET = 250;

    /** The most hits of a page. */
    static final int MAX_LIMIT = 100;

    record Arguments(
            @Description("A query in Lucene's syntax, such as title:lamp AND body:qu*. Ranges on point fields work,"
                    + " such as price:[10 TO 20]; dates on point fields are epoch milliseconds, and dates indexed as"
                    + " text are written as they are indexed, such as published:[20260131 TO 20260228]. Give exactly"
                    + " one of query, term, docValues and knn")
            Optional<String> query,

            @Description("A query for exactly one term, neither analyzed nor split: for keyword fields such as ids."
                    + " Give exactly one of query, term, docValues and knn")
            Optional<Queries.Term> term,

            @Description("A query on the doc values of a field, for fields that have only those. Give exactly one of"
                    + " query, term, docValues and knn")
            Optional<Queries.DocValues> docValues,

            @Description("The nearest documents to a vector or to a document's vector, by KNN vectors; the score is"
                    + " the similarity. Give exactly one of query, term, docValues and knn")
            Optional<Queries.Knn> knn,

            @Description("The type of point fields in query, when the guess is wrong: a point field of 4 bytes is"
                    + " taken as INT and one of 8 bytes as LONG, so FLOAT and DOUBLE fields need it (index_info shows"
                    + " pointBytes). Goes with query")
            Optional<List<Queries.PointFieldType>> pointTypes,

            @Description("The field of the query's terms that name none; needed with query")
            Optional<String> defaultField,

            @Description("The analyzer of query, by simple or class name, such as EnglishAnalyzer or KeywordAnalyzer;"
                    + " StandardAnalyzer by default. Fields indexed with another need theirs")
            Optional<String> analyzer,

            @Description("Fields with an analyzer of their own, such as KeywordAnalyzer for ids")
            Optional<List<Queries.FieldAnalyzer>> fieldAnalyzers,

            @Description("How clauses of the query without an operator combine; OR by default")
            Optional<QueryOperator> defaultOperator,

            @Description("The order of the hits, one key after the other; best score first by default. Needs a"
                    + " runtime with SCORING")
            Optional<List<Sort>> sort,

            @Description("The stored fields to show per hit; by default as many as fit about " + HIT_BUDGET
                    + " characters per hit, the rest named in moreFields; [] for none")
            Optional<List<String>> fields,

            @Description("The longest value shown, in characters, from 1 to " + SearchResults.MAX_VALUE_LENGTH + "; "
                    + DEFAULT_VALUE_LENGTH + " by default. get_field_value has a value whole")
            OptionalInt maxValueLength,

            @Description("How many hits to skip, for the next page: the nextOffset of the last page; 0 by default")
            OptionalInt offset,

            @Description("The most hits of the page, from 1 to " + MAX_LIMIT + "; " + DEFAULT_LIMIT + " by default")
            OptionalInt limit,

            @Description("Whether to count every hit; otherwise counting stops after 1000; false by default")
            Optional<Boolean> exactTotalHits)
            implements Queries.Arguments {}

    record Sort(
            @Description("A field with doc values (not BINARY), or _score or _doc")
            String field,

            @Description("How a field's doc values are read: STRING for SORTED and SORTED_SET, else LONG by default."
                    + " DoubleDocValuesField and FloatDocValuesField need DOUBLE or FLOAT; get_document's asStored"
                    + " says which. Not for _score and _doc")
            Optional<SortType> type,

            @Description("Largest first, or for _score lowest first; false by default")
            Optional<Boolean> reverse,

            @Description("Which value of a multi-valued field sorts: MIN by default; SORTED_NUMERIC takes MIN or MAX."
                    + " Not for _score and _doc")
            Optional<SortSelector> selector,

            @Description("Where documents without a value go: LAST by default. Not for _score and _doc")
            Optional<SortMissing> missing) {}

    record SortValue(
            @Description("The value; absent for a document without one")
            Optional<String> value,

            @Description("Present, and true, only if value is in hex")
            Optional<Boolean> binary) {}

    record Result(
            @Description("The query as Lucene writes it") String query,
            @Description("The query after rewriting") String rewrittenQuery,
            long totalHits,

            @Description("Whether totalHits is exact (EQUAL_TO) or a lower bound")
            TotalHitsRelation totalHitsRelation,

            int offset,

            @Description("The offset of the next page; absent on the last page")
            OptionalInt nextOffset,

            long tookMillis,

            @Description("The hits of the page, best score first unless sort says otherwise")
            List<Hit> hits) {}

    record Hit(
            @Description("The document id, for get_document")
            int docId,

            float score,

            @Description("The values the hit sorted by, one per sort key; absent without sort")
            Optional<List<SortValue>> sortValues,

            @Description("The hit's stored values, in the order of each field's first value")
            List<HitField> fields,

            @Description("Stored fields left out to keep the page short; name them in fields, or read them with"
                    + " get_document. Absent if none was")
            Optional<List<String>> moreFields) {}

    record HitField(String name, List<HitValue> values) {}

    record HitValue(
            @Description("STRING if absent") Optional<StoredType> type,

            @Description("The value; numbers in decimal; binary values as text if they are UTF-8, else in hex")
            String value,

            @Description("Present, and true, only if value is in hex")
            Optional<Boolean> binary,

            @Description("Present, and true, only if value is cut; get_field_value has it whole")
            Optional<Boolean> cut) {}

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Search the open index, best score first unless sort says otherwise, a page at a time: each hit with its document id, score and"
                + " a few brief stored values, to find documents; get_document and get_field_value read them. Give a"
                + " query in Lucene's syntax with a defaultField, a term for an exact keyword, docValues for a"
                + " field that has only those, or knn for the nearest documents to a vector or a document's. For the next page, call it again with the result's nextOffset.";
    }

    @Override
    public Class<Arguments> arguments() {
        return Arguments.class;
    }

    @Override
    public Class<Result> result() {
        return Result.class;
    }

    @Override
    public Result call(Arguments arguments, Call call) throws ToolException, Jobs.Cancelled {
        int offset = Slices.bounded("offset", arguments.offset(), 0, 0, StartSearchParams.MAX_WINDOW - 1);
        int limit = Slices.bounded("limit", arguments.limit(), DEFAULT_LIMIT, 1, MAX_LIMIT);
        int maxValueLength = Slices.bounded(
                "maxValueLength", arguments.maxValueLength(), DEFAULT_VALUE_LENGTH, 1, SearchResults.MAX_VALUE_LENGTH);
        if (offset + limit > StartSearchParams.MAX_WINDOW) {
            throw ToolError.invalid("offset + limit must be at most " + StartSearchParams.MAX_WINDOW
                    + ": a search pages that far only");
        }
        Queries.Stats stats = new Queries.Stats(call);
        SearchQuery query = Queries.query(arguments, call, stats, offset + limit);
        List<SortKey> sort = sort(arguments, stats);
        StartSearchParams params = new StartSearchParams(
                query,
                Queries.BM25,
                sort,
                offset,
                limit,
                arguments.exactTotalHits().orElse(false),
                arguments.fields(),
                Optional.empty());
        // The documents of the index, of which the search goes through the segments' share.
        OptionalLong total = call.session().maxDoc();
        SearchResults results = call.job(
                Methods.START_SEARCH,
                params,
                Methods.GET_SEARCH,
                GetSearchResult::results,
                poll -> new Jobs.Progress(poll.docsSearched(), total));
        return result(results, offset, limit, maxValueLength, arguments.fields().isEmpty(), !sort.isEmpty());
    }

    /**
     * The sort keys: {@code _score} and {@code _doc} by name, any other field by its doc values,
     * with the type guessed from {@code docValuesType} as the search screen does (ADR 0108).
     */
    private static List<SortKey> sort(Arguments arguments, Queries.Stats stats) throws ToolException, Jobs.Cancelled {
        List<SortKey> keys = new ArrayList<>();
        for (Sort sort : arguments.sort().orElse(List.of())) {
            boolean reverse = sort.reverse().orElse(false);
            if (sort.field().equals("_score") || sort.field().equals("_doc")) {
                if (sort.type().isPresent()
                        || sort.selector().isPresent()
                        || sort.missing().isPresent()) {
                    throw ToolError.invalid("type, selector and missing don't go with " + sort.field());
                }
                keys.add(
                        sort.field().equals("_score")
                                ? new SortKey(Optional.empty(), Optional.of(new ScoreSort(reverse)), Optional.empty())
                                : new SortKey(Optional.empty(), Optional.empty(), Optional.of(new DocSort(reverse))));
                continue;
            }
            SortType type = sort.type().isPresent() ? sort.type().get() : guessType(sort.field(), stats);
            keys.add(new SortKey(
                    Optional.of(new FieldSort(
                            sort.field(),
                            type,
                            reverse,
                            sort.selector().orElse(SortSelector.MIN),
                            sort.missing().orElse(SortMissing.LAST))),
                    Optional.empty(),
                    Optional.empty()));
        }
        return keys;
    }

    /** STRING for sorted doc values, else LONG: the server answers for a field that has none. */
    private static SortType guessType(String field, Queries.Stats stats) throws ToolException, Jobs.Cancelled {
        boolean text = stats.docValuesType(field)
                .filter(type -> type == DocValuesType.SORTED || type == DocValuesType.SORTED_SET)
                .isPresent();
        return text ? SortType.STRING : SortType.LONG;
    }

    private static Result result(
            SearchResults results, int offset, int limit, int maxValueLength, boolean withBudget, boolean sorted) {
        int next = offset + results.hits().size();
        boolean more = results.hits().size() == limit
                && next < StartSearchParams.MAX_WINDOW
                && (results.totalHitsRelation() == TotalHitsRelation.GREATER_THAN_OR_EQUAL_TO
                        || next < results.totalHits());
        return new Result(
                results.query(),
                results.rewrittenQuery(),
                results.totalHits(),
                results.totalHitsRelation(),
                offset,
                more ? OptionalInt.of(next) : OptionalInt.empty(),
                results.tookMillis(),
                results.hits().stream()
                        .map(hit -> hit(hit, maxValueLength, withBudget, sorted))
                        .toList());
    }

    /**
     * The hit with its values cut at {@code maxValueLength}; with {@code withBudget}, as many
     * fields as it takes to pass {@link #HIT_BUDGET} characters, the value that passes it included.
     */
    private static Hit hit(
            io.github.mfuerstenau.leia.protocol.search.Hit hit,
            int maxValueLength,
            boolean withBudget,
            boolean sorted) {
        List<HitField> shown = new ArrayList<>();
        List<String> left = new ArrayList<>();
        int characters = 0;
        for (var field : hit.fields()) {
            if (withBudget && characters >= HIT_BUDGET) {
                left.add(field.name());
                continue;
            }
            List<HitValue> values = new ArrayList<>();
            for (StoredValue value : field.values()) {
                HitValue shownValue = value(value, maxValueLength);
                characters += shownValue.value().length();
                values.add(shownValue);
            }
            shown.add(new HitField(field.name(), values));
        }
        Optional<List<SortValue>> sortValues = sorted
                ? Optional.of(
                        hit.sortValues().stream().map(SearchTool::sortValue).toList())
                : Optional.empty();
        return new Hit(hit.docId(), hit.score(), sortValues, shown, Absent.nonEmpty(left));
    }

    private static SortValue sortValue(io.github.mfuerstenau.leia.protocol.search.SortValue value) {
        if (value.number().isPresent()) {
            return new SortValue(value.number(), Optional.empty());
        }
        return value.term()
                .map(term -> new SortValue(Optional.of(term.value()), Absent.flag(term.binary())))
                .orElseGet(() -> new SortValue(Optional.empty(), Optional.empty()));
    }

    private static HitValue value(StoredValue value, int maxValueLength) {
        String text = value.value();
        boolean cut = value.fullLength().isPresent() || text.length() > maxValueLength;
        return new HitValue(
                Absent.type(value.type()),
                text.length() > maxValueLength ? Slices.head(text, value.binary(), maxValueLength) : text,
                Absent.flag(value.binary()),
                Absent.flag(cut));
    }
}
