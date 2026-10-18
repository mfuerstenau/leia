package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.BitSet;
import java.util.Optional;
import org.apache.lucene.search.DocIdSetIterator;
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
    void saysWhetherADocumentMatchesAndWhichIsNext() {
        DocFilter filter = filter(130, 3, 64, 129);

        assertThat(filter.contains(3)).isTrue();
        assertThat(filter.contains(4)).isFalse();
        assertThat(filter.contains(-1)).isFalse();
        assertThat(filter.contains(130)).isFalse();
        assertThat(filter.next(-5)).isEqualTo(3);
        assertThat(filter.next(4)).isEqualTo(64);
        assertThat(filter.next(129)).isEqualTo(129);
        assertThat(filter.next(130)).isEqualTo(DocIdSetIterator.NO_MORE_DOCS);
        assertThat(filter.timedOutValues()).isEmpty();
    }

    @Test
    void anEmptyFilterHasNoMatches() {
        assertThat(filter(0).step(0, true)).isEmpty();
        assertThat(filter(0).step(0, false)).isEmpty();
        assertThat(filter(5).matchCount()).isZero();
        assertThat(filter(5).step(0, true)).isEqualTo(Optional.empty());
    }

    @Test
    void takesTheDocumentsOfABitSet() {
        BitSet docs = new BitSet();
        docs.set(3);
        docs.set(64);
        docs.set(129);

        DocFilter filter = DocFilter.of(docs, 130, "body:beta", "body:beta", Duration.ofMillis(7));

        assertThat(filter.matchCount()).isEqualTo(3);
        assertThat(filter.contains(64)).isTrue();
        assertThat(filter.contains(65)).isFalse();
        assertThat(filter.step(65, true)).contains(new DocFilter.Match(129, 2));
        assertThat(filter.step(63, false)).contains(new DocFilter.Match(3, 0));
        assertThat(filter.next(130)).isEqualTo(DocIdSetIterator.NO_MORE_DOCS);
        assertThat(filter.query()).isEqualTo("body:beta");
        assertThat(filter.took()).isEqualTo(Duration.ofMillis(7));
        assertThat(DocFilter.of(new BitSet(), 0, "q", "q", Duration.ZERO).matchCount())
                .isZero();
    }

    @Test
    void refusesABitSetWithADocumentBeyondMaxDoc() {
        BitSet docs = new BitSet();
        docs.set(10);

        assertThatThrownBy(() -> DocFilter.of(docs, 10, "q", "q", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDoc is 10");
    }

    private static DocFilter filter(int maxDoc, int... matches) {
        FixedBitSet bits = new FixedBitSet(maxDoc);
        for (int docId : matches) {
            bits.set(docId);
        }
        return new DocFilter(bits, "q", "q", Duration.ZERO);
    }
}
