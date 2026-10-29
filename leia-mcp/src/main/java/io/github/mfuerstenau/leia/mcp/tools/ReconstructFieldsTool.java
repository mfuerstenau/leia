package io.github.mfuerstenau.leia.mcp.tools;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

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
            Optional<List<String>> fields,

            @Description("The most terms per field, up to " + MAX_TERMS + "; " + DEFAULT_TERMS + " by default")
            OptionalInt maxTerms) {}

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
            Optional<String> text,

            @Description("Whether text is cut at " + MAX_TEXT_LENGTH + " characters")
            boolean textCut,

            @Description("The terms in term order, at most maxTerms")
            List<Term> terms,

            @Description("How many terms the document has in the field")
            int termCount,

            @Description("Whether terms holds fewer than termCount")
            boolean cut,

            @Description("The document's points, at most " + MAX_POINTS + ", in unsigned byte order")
            List<Point> points,

            @Description("How many points the document has in the field")
            int pointCount) {}

    record Term(
            @Description("The term as text, or in hex with binary")
            String term,

            boolean binary,

            @Description("How often the term occurs in the field of the document")
            int freq,

            @Description("Its positions, at most " + MAX_POSITIONS + "; empty without positions")
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
                + " terms in order. It goes through every term of a field without a term vector, which can take a"
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
                new StartReconstructParams(arguments.docId(), arguments.fields()),
                Methods.GET_RECONSTRUCT,
                GetReconstructResult::result,
                poll -> new Jobs.Progress(poll.scanned(), poll.total()));
        return new Result(
                result.fields().stream().map(field -> field(field, maxTerms)).toList(), result.tookMillis());
    }

    private static Field field(ReconstructedField field, int maxTerms) {
        Optional<String> text = text(field.terms());
        boolean textCut = text.isPresent() && text.get().length() > MAX_TEXT_LENGTH;
        return new Field(
                field.field(),
                field.indexOptions(),
                field.source(),
                textCut ? Optional.of(Slices.head(text.get(), false, MAX_TEXT_LENGTH)) : text,
                textCut,
                field.terms().stream()
                        .limit(maxTerms)
                        .map(ReconstructFieldsTool::term)
                        .toList(),
                field.terms().size(),
                field.terms().size() > maxTerms,
                field.points().stream()
                        .limit(MAX_POINTS)
                        .map(point -> new Point(point.dimensions()))
                        .toList(),
                field.points().size());
    }

    private static Term term(TermVectorTerm term) {
        return new Term(
                term.term(),
                term.binary(),
                term.freq(),
                term.positions().stream()
                        .map(TermPosition::position)
                        .filter(OptionalInt::isPresent)
                        .map(OptionalInt::getAsInt)
                        .limit(MAX_POSITIONS)
                        .toList());
    }

    /** A term at a position. */
    private record Occurrence(int position, String term) {}

    /** The terms in position order; empty if none has a position. */
    static Optional<String> text(List<TermVectorTerm> terms) {
        List<Occurrence> occurrences = new ArrayList<>();
        for (TermVectorTerm term : terms) {
            for (TermPosition position : term.positions()) {
                position.position().ifPresent(at -> occurrences.add(new Occurrence(at, term.term())));
            }
        }
        if (occurrences.isEmpty()) {
            return Optional.empty();
        }
        // Stable: the terms at one position stay in term order.
        occurrences.sort(Comparator.comparingInt(Occurrence::position));
        StringBuilder text = new StringBuilder();
        int previous = -1;
        for (Occurrence occurrence : occurrences) {
            if (text.length() > MAX_TEXT_LENGTH) {
                break;
            }
            if (previous < 0) {
                text.append(occurrence.term());
            } else if (occurrence.position() == previous) {
                text.append('|').append(occurrence.term());
            } else if (occurrence.position() == previous + 1) {
                text.append(' ').append(occurrence.term());
            } else {
                text.append(' ').append(GAP).append(' ').append(occurrence.term());
            }
            previous = occurrence.position();
        }
        return Optional.of(text.toString());
    }
}
