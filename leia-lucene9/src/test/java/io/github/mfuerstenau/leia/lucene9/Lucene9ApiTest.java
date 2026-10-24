package io.github.mfuerstenau.leia.lucene9;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** On the 9.12 the adapter is built for, every API of {@link Lucene9Api} is there (ADR 0091). */
class Lucene9ApiTest {

    @Test
    void findsEveryApiOn912() {
        assertThat(new boolean[] {
                    Lucene9Api.STORED_FIELDS,
                    Lucene9Api.KNN,
                    Lucene9Api.VECTOR_ENCODING,
                    Lucene9Api.DOC_VALUE_COUNT,
                    Lucene9Api.SEARCH_TIMEOUT,
                    Lucene9Api.POINT_TREE,
                    Lucene9Api.HIGHLIGHTER,
                    Lucene9Api.HAS_BLOCKS
                })
                .containsOnly(true);
    }
}
