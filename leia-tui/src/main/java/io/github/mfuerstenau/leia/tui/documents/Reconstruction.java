package io.github.mfuerstenau.leia.tui.documents;

import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Rebuilding what a field of the shown document was indexed with (ADR 0029): the state of the
 * rebuild on the documents screen, and the field's tokens put back in position order.
 */
public sealed interface Reconstruction {

    /** Asked for; the backend hasn't started the job yet. */
    record Starting() implements Reconstruction {}

    /** @param total the terms the job goes through, if the backend knows */
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

    /** One line on where the rebuild stands, or what it found. */
    default String summary() {
        return switch (this) {
            case Starting _ -> "rebuilding…";
            case Running running
            when running.total().isPresent() ->
                "rebuilding… " + running.scanned() + " of " + running.total().getAsLong() + " terms, Esc stops";
            case Running running -> "rebuilding… " + running.scanned() + " terms, Esc stops";
            case Done done -> {
                String source =
                        done.field().source() == ReconstructSource.TERM_VECTOR ? "the term vector" : "the postings";
                yield TextFormat.count(done.field().terms().size(), "term") + ", from " + source;
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

    /** A token at its position, for {@link #tokenLine}. */
    record Token(int position, int startOffset, String text) {}

    /**
     * The field's tokens in position order, as the analyzer left them: tokens at the same position,
     * such as synonyms, joined by {@code |}, and {@code ·} where positions were skipped, such as for
     * a removed stop word. Empty if the terms have no positions.
     */
    static Optional<String> tokenLine(List<TermVectorTerm> terms) {
        List<Token> tokens = new ArrayList<>();
        for (TermVectorTerm term : terms) {
            String text = TextFormat.bytesText(term.term(), term.binary());
            for (TermPosition position : term.positions()) {
                if (position.position().isPresent()) {
                    tokens.add(new Token(
                            position.position().getAsInt(),
                            position.startOffset().orElse(0),
                            text));
                }
            }
        }
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        tokens.sort(Comparator.comparingInt(Token::position)
                .thenComparingInt(Token::startOffset)
                .thenComparing(Token::text));
        StringBuilder line = new StringBuilder();
        Integer last = null;
        for (Token token : tokens) {
            if (last == null) {
                if (token.position() > 0) {
                    line.append("· ");
                }
            } else if (last == token.position()) {
                line.append('|');
            } else {
                line.append(token.position() > last + 1 ? " · " : " ");
            }
            line.append(token.text());
            last = token.position();
        }
        return Optional.of(line.toString());
    }
}
