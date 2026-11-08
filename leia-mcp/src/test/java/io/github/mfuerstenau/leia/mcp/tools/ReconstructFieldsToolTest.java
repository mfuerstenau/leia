package io.github.mfuerstenau.leia.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The {@code text} of {@code reconstruct_fields}: pinned, so that it stays the same (ADR 0126). */
class ReconstructFieldsToolTest {

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

    private static TermVectorTerm at(String text, int position, int offset) {
        return new TermVectorTerm(
                text,
                false,
                1,
                List.of(new TermPosition(
                        OptionalInt.of(position), OptionalInt.of(offset), OptionalInt.empty(), Optional.empty())));
    }

    @Test
    void putsTheTermsInPositionOrder() {
        assertThat(ReconstructFieldsTool.text(
                        List.of(term("dog", 5), term("fox", 2), term("quick", 1), term("saw", 3), term("the", 0, 4))))
                .contains("the quick fox saw the dog");
    }

    @Test
    void joinsStackedTermsInTermOrderAndMarksGapsButNoLeadingOne() {
        assertThat(ReconstructFieldsTool.text(List.of(term("car", 3), term("quick", 1), term("fast", 1))))
                .contains("quick|fast " + ReconstructFieldsTool.GAP + " car");
        // Term order, not offsets, decides between stacked terms.
        assertThat(ReconstructFieldsTool.text(List.of(at("wifi", 0, 0), at("wi", 0, 0), at("fi", 1, 3))))
                .contains("wifi|wi fi");
    }

    @Test
    void keepsABinaryTermAsItIs() {
        assertThat(ReconstructFieldsTool.text(List.of(new TermVectorTerm(
                        "ff00",
                        true,
                        1,
                        List.of(new TermPosition(
                                OptionalInt.of(0), OptionalInt.empty(), OptionalInt.empty(), Optional.empty()))))))
                .contains("ff00");
    }

    @Test
    void hasNoTextWithoutPositions() {
        assertThat(ReconstructFieldsTool.text(List.of(term("black"), term("kettle"))))
                .isEmpty();
    }

    @Test
    void stopsOnceTheTextIsLongerThanItsLimit() {
        String word = "x".repeat(1000);
        int[] positions = new int[100];
        Arrays.setAll(positions, i -> i);
        String text = ReconstructFieldsTool.text(List.of(term(word, positions))).orElseThrow();

        // The term that makes it longer than the limit is still appended; the next isn't.
        assertThat(text.length()).isGreaterThan(ReconstructFieldsTool.MAX_TEXT_LENGTH);
        assertThat(text.length()).isLessThanOrEqualTo(ReconstructFieldsTool.MAX_TEXT_LENGTH + word.length() + 1);
        assertThat(text).startsWith(word + " " + word);
    }
}
