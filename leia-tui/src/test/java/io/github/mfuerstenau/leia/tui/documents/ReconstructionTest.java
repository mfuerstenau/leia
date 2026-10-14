package io.github.mfuerstenau.leia.tui.documents;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.document.ReconstructSource;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.document.TermVectorTerm;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The tests of {@code reconstruct.rs}, ported. */
class ReconstructionTest {

    private static TermVectorTerm term(String text, int... positions) {
        return term(text, false, positions);
    }

    private static TermVectorTerm term(String text, boolean binary, int... positions) {
        return new TermVectorTerm(
                text,
                binary,
                positions.length,
                Arrays.stream(positions)
                        .mapToObj(position -> new TermPosition(
                                OptionalInt.of(position), OptionalInt.empty(), OptionalInt.empty(), Optional.empty()))
                        .toList());
    }

    /** A term at one position, starting at {@code offset}. */
    private static TermVectorTerm at(String text, int position, int offset) {
        return new TermVectorTerm(
                text,
                false,
                1,
                List.of(new TermPosition(
                        OptionalInt.of(position), OptionalInt.of(offset), OptionalInt.empty(), Optional.empty())));
    }

    @Test
    void putsTheTokensBackInPositionOrder() {
        List<TermVectorTerm> terms =
                List.of(term("dog", 5), term("fox", 2), term("quick", 1), term("saw", 3), term("the", 0, 4));

        assertThat(Reconstruction.tokenLine(terms)).contains("the quick fox saw the dog");
    }

    @Test
    void marksStackedTokensAndGaps() {
        List<TermVectorTerm> terms = List.of(term("car", 3), term("fast", 1), term("quick", 1));

        assertThat(Reconstruction.tokenLine(terms)).contains("· fast|quick · car");
    }

    @Test
    void stackedTokensFollowTheirOffsets() {
        List<TermVectorTerm> terms = List.of(at("fi", 1, 3), at("wi", 0, 0), at("wifi", 0, 0));

        assertThat(Reconstruction.tokenLine(terms)).contains("wi|wifi fi");
    }

    @Test
    void showsBinaryTermsAsHex() {
        assertThat(Reconstruction.tokenLine(List.of(term("ff00", true, 0)))).contains("0xff00");
    }

    @Test
    void hasNoLineWithoutPositions() {
        assertThat(Reconstruction.tokenLine(List.of(term("black"), term("kettle"))))
                .isEmpty();
    }

    @Test
    void summarizesTheState() {
        Reconstruction running = new Reconstruction.Running(4, 1024, OptionalLong.of(80000));
        assertThat(running.summary(true, false)).isEqualTo("rebuilding… 1024 of 80000 terms, Esc stops");
        assertThat(running.summary(false, true)).isEqualTo("rebuilding… 1024 of 80000 points, Esc stops");
        assertThat(running.summary(true, true)).isEqualTo("rebuilding… 1024 of 80000 terms and points, Esc stops");
        assertThat(running.job()).hasValue(4);
        assertThat(running.active()).isTrue();
        assertThat(new Reconstruction.Running(4, 7, OptionalLong.empty()).summary(true, false))
                .isEqualTo("rebuilding… 7 terms, Esc stops");
        assertThat(new Reconstruction.Starting().summary(true, false)).isEqualTo("rebuilding…");

        ReconstructedField field = new ReconstructedField(
                "title", IndexOptions.DOCS, ReconstructSource.POSTINGS, List.of(term("a")), List.of());
        Reconstruction done = new Reconstruction.Done(field);
        assertThat(done.summary(true, false)).isEqualTo("1 term, from the postings");
        assertThat(done.summary(true, true)).isEqualTo("1 term, from the postings · 0 points");
        assertThat(done.active()).isFalse();
        assertThat(Reconstruction.hasFreqs(field)).isFalse();
        assertThat(Reconstruction.hasFreqs(new ReconstructedField(
                        "title", IndexOptions.DOCS, ReconstructSource.TERM_VECTOR, List.of(), List.of())))
                .isTrue();
        assertThat(new Reconstruction.Failed("gone").summary(true, false)).isEqualTo("cannot rebuild it: gone");

        ReconstructedField points = new ReconstructedField(
                "price",
                IndexOptions.NONE,
                ReconstructSource.POSTINGS,
                List.of(),
                List.of(new PointValue(List.of("80000001")), new PointValue(List.of("80000002"))));
        assertThat(new Reconstruction.Done(points).summary(false, true)).isEqualTo("2 points");
    }
}
