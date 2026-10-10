package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.apache.lucene.util.FixedBitSet;
import org.junit.jupiter.api.Test;

class DocFilterTest {

    @Test
    void stepsToTheNextOrPreviousMatchFromADocumentOn() {
        DocFilter filter = filter(200, 3, 64, 65, 130, 199);

        assertThat(filter.matchCount()).isEqualTo(5);
        assertThat(filter.step(0, true)).contains(new DocFilter.Match(3, 0));
        assertThat(filter.step(3, true)).contains(new DocFilter.Match(3, 0));
        assertThat(filter.step(4, true)).contains(new DocFilter.Match(64, 1));
        assertThat(filter.step(65, true)).contains(new DocFilter.Match(65, 2));
        assertThat(filter.step(66, true)).contains(new DocFilter.Match(130, 3));
        assertThat(filter.step(131, true)).contains(new DocFilter.Match(199, 4));
        assertThat(filter.step(198, false)).contains(new DocFilter.Match(130, 3));
        assertThat(filter.step(64, false)).contains(new DocFilter.Match(64, 1));
        assertThat(filter.step(63, false)).contains(new DocFilter.Match(3, 0));
        assertThat(filter.step(2, false)).isEmpty();
    }

    @Test
    void takesAnyDocumentIdToStepFrom() {
        DocFilter filter = filter(10, 0, 9);

        assertThat(filter.step(-1, true)).contains(new DocFilter.Match(0, 0));
        assertThat(filter.step(-1, false)).isEmpty();
        assertThat(filter.step(10, true)).isEmpty();
        assertThat(filter.step(Integer.MAX_VALUE, false)).contains(new DocFilter.Match(9, 1));
    }

    @Test
    void anEmptyFilterHasNoMatches() {
        assertThat(filter(0).step(0, true)).isEmpty();
        assertThat(filter(0).step(0, false)).isEmpty();
        assertThat(filter(5).matchCount()).isZero();
        assertThat(filter(5).step(0, true)).isEqualTo(Optional.empty());
    }

    private static DocFilter filter(int maxDoc, int... matches) {
        FixedBitSet bits = new FixedBitSet(maxDoc);
        for (int docId : matches) {
            bits.set(docId);
        }
        return new DocFilter(bits, "q", "q", Duration.ZERO);
    }
}
