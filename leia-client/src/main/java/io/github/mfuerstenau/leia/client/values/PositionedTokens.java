package io.github.mfuerstenau.leia.client.values;

import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The tokens of a rebuilt field (ADR 0029, ADR 0041) back in position order, as the analyzer left
 * them, and the line they make: tokens at one position, such as synonyms, joined by {@code |}, and a
 * gap mark where positions were skipped, such as for a removed stop word.
 */
public final class PositionedTokens {

    private PositionedTokens() {}

    /**
     * A term at one of its positions.
     *
     * @param startOffset where it starts in the text; null if the terms have no offsets
     * @param term the term as the protocol has it: hex if {@code binary}
     */
    public record Token(int position, @Nullable Integer startOffset, String term, boolean binary) {}

    /**
     * A token for each position of each term, in position order; tokens at one position stay in the
     * order of {@code terms}. Positions without a position are left out.
     */
    public static List<Token> of(List<TermVectorTerm> terms) {
        List<Token> tokens = new ArrayList<>();
        for (TermVectorTerm term : terms) {
            for (TermPosition position : term.positions()) {
                Integer startOffset = position.startOffset().isPresent()
                        ? position.startOffset().getAsInt()
                        : null;
                position.position().ifPresent(at -> tokens.add(new Token(at, startOffset, term.term(), term.binary())));
            }
        }
        // Stable: the tokens at one position stay in term order.
        tokens.sort(Comparator.comparingInt(Token::position));
        return tokens;
    }

    /**
     * The line of {@code tokens}, each shown as {@code text} gives it: {@code |} between tokens at
     * one position, a space between neighbours, and {@code gap} between spaces where positions were
     * skipped.
     *
     * @param leadingGap whether the line starts with {@code gap} if the first token's position isn't 0
     * @param maxLength once the line is longer, no further token is added
     */
    public static String line(
            List<Token> tokens, Function<Token, String> text, String gap, boolean leadingGap, int maxLength) {
        StringBuilder line = new StringBuilder();
        Integer last = null;
        for (Token token : tokens) {
            if (line.length() > maxLength) {
                break;
            }
            if (last == null) {
                if (leadingGap && token.position() > 0) {
                    line.append(gap).append(' ');
                }
            } else if (last == token.position()) {
                line.append('|');
            } else if (token.position() > last + 1) {
                line.append(' ').append(gap).append(' ');
            } else {
                line.append(' ');
            }
            line.append(text.apply(token));
            last = token.position();
        }
        return line.toString();
    }
}
