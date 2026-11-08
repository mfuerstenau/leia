package io.github.mfuerstenau.leia.tui.documents;

import io.github.mfuerstenau.leia.client.values.PositionedTokens;
import io.github.mfuerstenau.leia.client.values.PositionedTokens.Token;
import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Rebuilding what a field of the shown document was indexed with (ADR 0029, ADR 0041): the state
 * of the rebuild on the documents screen, and the field's tokens put back in position order.
 */
public sealed interface Reconstruction {

    /** Asked for; the backend hasn't started the job yet. */
    record Starting() implements Reconstruction {}

    /** @param total the terms and points the job goes through, if the backend knows */
    record Running(long jobId, long scanned, OptionalLong total) implements Reconstruction {}

    record Done(ReconstructedField field) implements Reconstruction {}

    record Failed(String error) implements Reconstruction {}

    /** Whether the rebuild is asked for or running. */
    default boolean active() {
        return this instanceof Starting || this instanceof Running;
    }

    /** The job, while it runs. */
    default OptionalLong job() {
        return this instanceof Running running ? OptionalLong.of(running.jobId()) : OptionalLong.empty();
    }

    /**
     * One line on where the rebuild stands, or what it found, for a field with postings ({@code
     * terms}), points, or both.
     */
    default String summary(boolean hasTerms, boolean points) {
        boolean terms = hasTerms || !points;
        String unit = "terms";
        if (points) {
            unit = terms ? "terms and points" : "points";
        }
        return switch (this) {
            case Starting _ -> "rebuilding…";
            case Running running
            when running.total().isPresent() ->
                "rebuilding… " + running.scanned() + " of " + running.total().getAsLong() + " " + unit + ", Esc stops";
            case Running running -> "rebuilding… " + running.scanned() + " " + unit + ", Esc stops";
            case Done done -> {
                List<String> parts = new ArrayList<>();
                if (terms) {
                    String source =
                            done.field().source() == ReconstructSource.TERM_VECTOR ? "the term vector" : "the postings";
                    parts.add(TextFormat.count(done.field().terms().size(), "term") + ", from " + source);
                }
                if (points) {
                    parts.add(TextFormat.count(done.field().points().size(), "point"));
                }
                yield String.join(" · ", parts);
            }
            case Failed failed -> "cannot rebuild it: " + failed.error();
        };
    }

    /**
     * Whether the freqs of a rebuilt field count occurrences: postings without freqs give 1 for every
     * term, and custom freqs are something else, such as scores.
     */
    static boolean hasFreqs(ReconstructedField field) {
        return field.source() == ReconstructSource.TERM_VECTOR
                || switch (field.indexOptions()) {
                    case DOCS_AND_FREQS, DOCS_AND_FREQS_AND_POSITIONS, DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS -> true;
                    default -> false;
                };
    }

    /**
     * The field's tokens in position order, as the analyzer left them: tokens at the same position,
     * such as synonyms, joined by {@code |} in the order of their offsets, and {@code ·} where
     * positions were skipped, such as for a removed stop word. Empty if the terms have no positions.
     */
    static Optional<String> tokenLine(List<TermVectorTerm> terms) {
        List<Token> tokens = new ArrayList<>(PositionedTokens.of(terms));
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        Function<Token, String> text = token -> TextFormat.bytesText(token.term(), token.binary());
        tokens.sort(Comparator.comparingInt(Token::position)
                .thenComparingInt(token -> token.startOffset().orElse(0))
                .thenComparing(text));
        return Optional.of(PositionedTokens.line(tokens, text, "·", true, Integer.MAX_VALUE));
    }
}
