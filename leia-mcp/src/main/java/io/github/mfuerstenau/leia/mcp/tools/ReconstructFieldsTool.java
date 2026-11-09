package io.github.mfuerstenau.leia.mcp.tools;

import io.github.mfuerstenau.leia.client.values.PositionedTokens;
import io.github.mfuerstenau.leia.client.values.PositionedTokens.Token;
import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Description;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.McpTool;
import io.github.mfuerstenau.leia.mcp.ToolException;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * {@code reconstruct_fields}: the terms and points a document was indexed with, from its term
 * vectors or the postings, also in the fields that aren't stored (ADR 0100).
 */
final class ReconstructFieldsTool implements McpTool<ReconstructFieldsTool.Arguments, ReconstructFieldsTool.Result> {

    static final int DEFAULT_TERMS = 500;

    /** The largest {@code maxTerms}. */
    static final int MAX_TERMS = 10_000;

    /** The most positions a term shows; its {@code freq} says how many it has. */
    static final int MAX_POSITIONS = 1000;

    /** The most points a field shows. */
    static final int MAX_POINTS = 100;

    /** The longest {@code text}, in characters. */
    static final int MAX_TEXT_LENGTH = 65_536;

    /** Between two terms whose positions aren't next to each other, such as where a stop word was. */
    static final String GAP = "…";

    record Arguments(
            @Description("The document id, from 0 to maxDoc - 1; a deleted document is rebuilt too")
            int docId,

            @Description("The fields with postings or points to rebuild, in this order; by default every such field")
            @Nullable
            List<String> fields,

            @Description("The most terms per field in terms, up to " + MAX_TERMS + "; " + DEFAULT_TERMS + " by default")
            @Nullable
            Integer maxTerms,

            @Description("Whether to list the terms with their freqs and positions also where text has them; false"
                    + " by default")
            @Nullable
            Boolean withTerms) {}

    record Result(
            @Description("The fields in the order they were asked for")
            List<Field> fields,

            long tookMillis) {}

    record Field(
            String field,

            @Description("What the postings of the field hold in the document's segment")
            IndexOptions indexOptions,

            @Description("Where the terms come from: the document's TERM_VECTOR, or the POSTINGS of every term")
            ReconstructSource source,

            @Description("The terms in position order, as the analyzer left the text: terms at one position joined"
                    + " with |, a gap of positions as " + GAP + "; absent without positions")
            @Nullable
            String text,

            @Description("Present, and true, only if text is cut at " + MAX_TEXT_LENGTH + " characters") @Nullable
            Boolean textCut,

            @Description("The terms in term order, at most maxTerms; absent where text has them, unless withTerms")
            @Nullable
            List<Term> terms,

            @Description("How many terms the document has in the field")
            int termCount,

            @Description("Present, and true, only if terms holds fewer than termCount") @Nullable
            Boolean cut,

            @Description("The document's points, at most " + MAX_POINTS + ", in unsigned byte order; absent without")
            @Nullable
            List<Point> points,

            @Description("How many points the document has in the field; absent without") @Nullable
            Integer pointCount) {}

    record Term(
            @Description("The term as text, or in hex with binary")
            String term,

            @Description("Present, and true, only if term is in hex") @Nullable
            Boolean binary,

            @Description("How often the term occurs in the field of the document")
            int freq,

            @Description("Its positions, at most " + MAX_POSITIONS + "; absent without positions") @Nullable
            List<Integer> positions) {}

    record Point(
            @Description("The bytes of each dimension in lowercase hex, as Lucene packs them: sortable and"
                    + " big-endian, for IntPoint and the like")
            List<String> dimensions) {}

    @Override
    public String name() {
        return "reconstruct_fields";
    }

    @Override
    public String description() {
        return "Rebuild the terms and points a document was indexed with, field by field, from its term vectors or"
                + " the postings: what an indexed field holds even if it isn't stored. With positions, text gives the"
                + " terms in order, and stands for the terms unless withTerms is set. It goes through every term of a field without a term vector, which can take a"
                + " while in a large index.";
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
        int maxTerms = Slices.bounded("maxTerms", arguments.maxTerms(), DEFAULT_TERMS, 1, MAX_TERMS);
        ReconstructResult result = call.job(
                Methods.START_RECONSTRUCT,
                new StartReconstructParams(arguments.docId(), Optional.ofNullable(arguments.fields())),
                Methods.GET_RECONSTRUCT,
                GetReconstructResult::result,
                poll -> new Jobs.Progress(poll.scanned(), Optionals.orNull(poll.total())));
        boolean withTerms = Given.isTrue(arguments.withTerms());
        return new Result(
                result.fields().stream()
                        .map(field -> field(field, maxTerms, withTerms))
                        .toList(),
                result.tookMillis());
    }

    private static Field field(ReconstructedField field, int maxTerms, boolean withTerms) {
        Optional<String> text = text(field.terms());
        boolean textCut = text.isPresent() && text.get().length() > MAX_TEXT_LENGTH;
        boolean listTerms = withTerms || text.isEmpty();
        return new Field(
                field.field(),
                field.indexOptions(),
                field.source(),
                textCut ? Slices.head(text.get(), false, MAX_TEXT_LENGTH) : text.orElse(null),
                Absent.flag(textCut),
                listTerms
                        ? field.terms().stream()
                                .limit(maxTerms)
                                .map(ReconstructFieldsTool::term)
                                .toList()
                        : null,
                field.terms().size(),
                Absent.flag(listTerms && field.terms().size() > maxTerms),
                Absent.nonEmpty(field.points().stream()
                        .limit(MAX_POINTS)
                        .map(point -> new Point(point.dimensions()))
                        .toList()),
                Absent.nonZero(field.points().size()));
    }

    private static Term term(TermVectorTerm term) {
        return new Term(
                term.term(),
                Absent.flag(term.binary()),
                term.freq(),
                Absent.nonEmpty(term.positions().stream()
                        .map(TermPosition::position)
                        .filter(OptionalInt::isPresent)
                        .map(OptionalInt::getAsInt)
                        .limit(MAX_POSITIONS)
                        .toList()));
    }

    /** The terms in position order; empty if none has a position. */
    static Optional<String> text(List<TermVectorTerm> terms) {
        List<Token> tokens = PositionedTokens.of(terms);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PositionedTokens.line(tokens, Token::term, GAP, false, MAX_TEXT_LENGTH));
    }
}
