package io.github.mfuerstenau.leia.client.values;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.client.values.PositionedTokens.Token;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The tokens of a rebuilt field in position order, and the line they make (ADR 0029, ADR 0126). */
class PositionedTokensTest {

    private static TermVectorTerm term(String text, int... positions) {
        return new TermVectorTerm(
                text,
                false,
                positions.length,
                Arrays.stream(positions)
                        .mapToObj(position -> new TermPosition(
                                OptionalInt.of(position), OptionalInt.empty(), OptionalInt.empty(), Optional.empty()))
                        .toList());
    }

    private static TermVectorTerm at(String text, boolean binary, int position, int offset) {
        return new TermVectorTerm(
                text,
                binary,
                1,
                List.of(new TermPosition(
                        OptionalInt.of(position), OptionalInt.of(offset), OptionalInt.empty(), Optional.empty())));
    }

    @Test
    void listsATokenPerPositionInPositionOrderAndTiesInTermOrder() {
        List<Token> tokens = PositionedTokens.of(List.of(term("the", 0, 4), term("quick", 1), term("fast", 1)));

        assertThat(tokens)
                .containsExactly(
                        new Token(0, null, "the", false),
                        new Token(1, null, "quick", false),
                        new Token(1, null, "fast", false),
                        new Token(4, null, "the", false));
    }

    @Test
    void keepsTheOffsetAndWhetherTheTermIsBinary() {
        assertThat(PositionedTokens.of(List.of(at("ff00", true, 2, 7)))).containsExactly(new Token(2, 7, "ff00", true));
    }

    @Test
    void leavesOutPositionsWithoutAPosition() {
        assertThat(PositionedTokens.of(List.of(term("black"), term("kettle")))).isEmpty();
    }

    @Test
    void joinsStackedTokensAndMarksGaps() {
        List<Token> tokens = PositionedTokens.of(List.of(term("car", 3), term("fast", 1), term("quick", 1)));

        assertThat(PositionedTokens.line(tokens, Token::term, "…", false, Integer.MAX_VALUE))
                .isEqualTo("fast|quick … car");
        assertThat(PositionedTokens.line(tokens, Token::term, "·", true, Integer.MAX_VALUE))
                .isEqualTo("· fast|quick · car");
    }

    @Test
    void startsWithAGapOnlyWhenAskedAndTheFirstPositionIsntZero() {
        List<Token> fromZero = PositionedTokens.of(List.of(term("a", 0), term("b", 1)));

        assertThat(PositionedTokens.line(fromZero, Token::term, "·", true, Integer.MAX_VALUE))
                .isEqualTo("a b");
    }

    @Test
    void showsEachTokenAsAsked() {
        List<Token> tokens = PositionedTokens.of(List.of(at("ff00", true, 0, 0)));

        assertThat(PositionedTokens.line(tokens, token -> "0x" + token.term(), "·", true, Integer.MAX_VALUE))
                .isEqualTo("0xff00");
    }

    @Test
    void stopsBeforeATokenOnceTheLineIsLongerThanItsLimit() {
        List<Token> tokens = PositionedTokens.of(List.of(term("abc", 0, 1, 2, 3)));

        assertThat(PositionedTokens.line(tokens, Token::term, "·", false, 5)).isEqualTo("abc abc");
    }
}
