package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolError;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPositionsResult;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * {@code postings}: the documents that hold one exact term, a page at a time in document order,
 * with where it occurs on request (ADR 0115).
 */
final class PostingsTool implements McpTool<PostingsTool.Arguments, PostingsTool.Result> {

    static final int DEFAULT_LIMIT = 50;

    /** The most postings of a page with positions, which costs a backend call each. */
    static final int MAX_LIMIT_WITH_POSITIONS = 100;

    /** The most positions of one document that a page returns. */
    static final int MAX_POSITIONS = 100;

    record Arguments(
            String field,

            @Description("The term as text, or in hex with binary, as terms lists it")
            String term,

            @Description("Whether term is the term's bytes in hex; false by default")
            Optional<Boolean> binary,

            @Description("Where the page starts: the nextDoc of the last page; 0 by default")
            OptionalInt fromDoc,

            @Description("The most postings of the page, from 1 to " + GetPostingsParams.MAX_LIMIT + "; "
                    + DEFAULT_LIMIT + " by default; with positions at most " + MAX_LIMIT_WITH_POSITIONS)
            OptionalInt limit,

            @Description("Whether to give each posting its positions, offsets and payloads, which needs a field"
                    + " indexed with positions; false by default")
            Optional<Boolean> positions) {}

    record Result(
            String field,
            String term,

            @Description("Present, and true, only if term is the term's bytes in hex")
            Optional<Boolean> binary,

            @Description("The documents with the term, deleted ones included")
            int docFreq,

            @Description("The occurrences of the term in all documents, on Lucene 3 in the live ones only, or with"
                    + " DOCS_AND_CUSTOM_FREQS the sum of the freqs; absent if equal to docFreq")
            OptionalLong totalTermFreq,

            @Description("The documents of the page, in document order")
            List<Entry> postings,

            @Description("The fromDoc of the next page; absent after the last posting")
            OptionalInt nextDoc) {}

    record Entry(
            int docId,

            @Description("Present, and true, only if the document is deleted")
            Optional<Boolean> deleted,

            @Description("How often the term occurs in the document; with the index options DOCS_AND_CUSTOM_FREQS a"
                    + " value of its own, such as a FeatureField's score. Absent where frequencies aren't indexed")
            OptionalInt freq,

            @Description("The occurrences in order; only with positions")
            Optional<List<Position>> positions,

            @Description("Present, and true, only if the document has more than " + MAX_POSITIONS
                    + " occurrences, of which positions has the first; freq says how many")
            Optional<Boolean> positionsCut) {}

    record Position(
            @Description("Absent where positions aren't indexed")
            OptionalInt position,

            @Description("Absent where offsets aren't indexed")
            OptionalInt startOffset,

            @Description("Absent where offsets aren't indexed")
            OptionalInt endOffset,

            @Description("In hex; absent if there is none") Optional<String> payload) {}

    @Override
    public String name() {
        return "postings";
    }

    @Override
    public String description() {
        return "List the documents that hold one exact term, as indexed (terms lists them), in document order, with"
                + " how often and, with positions, where. Deleted documents are included and marked. For the next"
                + " page, call it again with the result's nextDoc as fromDoc.";
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
        boolean positions = arguments.positions().orElse(false);
        int limit = Slices.bounded(
                "limit",
                arguments.limit(),
                DEFAULT_LIMIT,
                1,
                positions ? MAX_LIMIT_WITH_POSITIONS : GetPostingsParams.MAX_LIMIT);
        int fromDoc = arguments.fromDoc().orElse(0);
        if (fromDoc < 0) {
            throw ToolError.invalid("fromDoc must not be negative, not " + fromDoc);
        }
        Optional<OpenIndexResult> opened = call.session().opened();
        if (opened.isPresent()) {
            if (!opened.get().features().contains(Feature.POSTINGS)) {
                throw ToolError.notSupported("The Lucene runtime that reads this index lists no postings");
            }
            if (positions && !opened.get().features().contains(Feature.POSITIONS)) {
                throw ToolError.notSupported("The Lucene runtime that reads this index lists no positions");
            }
        }
        if (positions) {
            requirePositions(arguments.field(), new Queries.Stats(call));
        }
        BytesValue term = new BytesValue(arguments.term(), arguments.binary().orElse(false));
        GetPostingsResult page = call.call(
                Methods.GET_POSTINGS, new GetPostingsParams(arguments.field(), term, fromDoc, limit, Optional.empty()));
        List<Entry> entries = new ArrayList<>();
        for (Posting posting : page.postings()) {
            entries.add(entry(posting, positions ? positions(posting, arguments.field(), term, call) : null));
        }
        return new Result(
                arguments.field(),
                arguments.term(),
                Absent.flag(term.binary()),
                page.docFreq(),
                page.totalTermFreq() == page.docFreq() ? OptionalLong.empty() : OptionalLong.of(page.totalTermFreq()),
                entries,
                page.nextDoc());
    }

    /**
     * Refuses a field whose postings hold no positions, which the agent would see as empty lists. A
     * field without postings is left to the server, which says that it has no such term.
     */
    private static void requirePositions(String field, Queries.Stats stats) throws ToolException, Jobs.Cancelled {
        Optional<IndexOptions> options = stats.indexOptions(field);
        if (options.isPresent()
                && options.get() != IndexOptions.NONE
                && options.get() != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
                && options.get() != IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) {
            throw ToolError.invalid("The field " + field + " is indexed with " + options.get()
                    + ", which holds no positions: leave positions out");
        }
    }

    private static List<TermPosition> positions(Posting posting, String field, BytesValue term, Call call)
            throws ToolException, Jobs.Cancelled {
        GetPositionsResult result =
                call.call(Methods.GET_POSITIONS, new GetPositionsParams(posting.docId(), field, term));
        return result.positions();
    }

    private static Entry entry(Posting posting, List<TermPosition> positions) {
        boolean cut = positions != null && positions.size() > MAX_POSITIONS;
        Optional<List<Position>> listed = positions == null
                ? Optional.empty()
                : Optional.of(positions.stream()
                        .limit(MAX_POSITIONS)
                        .map(position -> new Position(
                                position.position(), position.startOffset(), position.endOffset(), position.payload()))
                        .toList());
        return new Entry(posting.docId(), Absent.flag(posting.deleted()), posting.freq(), listed, Absent.flag(cut));
    }
}
