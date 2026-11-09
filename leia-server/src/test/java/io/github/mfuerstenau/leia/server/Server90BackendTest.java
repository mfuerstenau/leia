package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.BM25;
import static io.github.mfuerstenau.leia.server.RealBackend.DEFAULTS;
import static io.github.mfuerstenau.leia.server.RealBackend.STANDARD;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.commits.Segment;
import io.github.mfuerstenau.leia.protocol.document.BytesValue;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructedField;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.index.VectorEncoding;
import io.github.mfuerstenau.leia.protocol.search.ClassicQuery;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.QueryOperator;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchResults;
import io.github.mfuerstenau.leia.protocol.search.StartSearchParams;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A fake Elasticsearch on Lucene 9.0 (ADR 0091), read with leia's Lucene 9 adapter, which is built
 * for 9.12 and leaves out what 9.0 lacks: KNN vectors, points and highlighting. A
 * server is loaded once per JVM, so this runs in a JVM of its own, from serverTest.
 */
@Tag("server")
class Server90BackendTest {

    @Test
    void readsTheIndexWithoutWhatLucene90Lacks() throws Exception {
        ServerBackends.missesTheServersFormat("90");

        try (RealBackend backend = ServerBackends.readsWithTheServer("90", "9.0.0")) {
            OpenIndexResult opened = backend.call(Methods.OPEN_INDEX, new OpenIndexParams(ServerBackends.index("90")));
            assertThat(opened.features())
                    .contains(Feature.DOCUMENTS, Feature.TERM_VECTORS, Feature.DOC_VALUES, Feature.CLASSIC_SEARCH)
                    .doesNotContain(Feature.KNN_VECTORS, Feature.KNN_GRAPH, Feature.POINTS, Feature.HIGHLIGHT);

            // Before 9.4 every vector is of floats.
            IndexField vector = backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams()).fields().stream()
                    .filter(field -> field.name().equals("vector"))
                    .findFirst()
                    .orElseThrow();
            assertThat(vector.vectorDimension()).isEqualTo(2);
            assertThat(vector.vectorEncoding()).contains(VectorEncoding.FLOAT32);

            // The stored fields and term vectors through 9.0's reader, the sorted set to NO_MORE_ORDS.
            GetDocumentResult document = backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(1));
            assertThat(field(document, "title").hasTermVector()).isTrue();
            assertThat(field(document, "tags").docValues().orElseThrow().values())
                    .extracting(BytesValue::value)
                    .containsExactly("all", "t1");
            assertThat(field(document, "vector").vector()).isEmpty();

            // Without the searcher's timeout, which 9.0 lacks.
            SearchResults results = backend.search(new StartSearchParams(
                    title("server"), BM25, List.of(), 0, 10, true, Optional.empty(), Optional.empty()));
            assertThat(results.totalHits()).isEqualTo(3);
            assertThat(results.hits()).extracting(Hit::docId).containsExactlyInAnyOrder(0, 1, 2);

            // The points through PointValues.intersect, without the PointTree that 9.0 lacks.
            assertThat(backend.reconstruct(1, List.of("count")).fields())
                    .singleElement()
                    .satisfies(count -> assertThat(count.points()).hasSize(1))
                    .extracting(ReconstructedField::field)
                    .isEqualTo("count");

            // No blocks before 9.9.
            assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits())
                    .hasSize(1);
            assertThat(backend.call(Methods.GET_COMMIT, new GetCommitParams(1, OptionalInt.empty()))
                            .segments())
                    .extracting(Segment::hasBlocks)
                    .containsExactly(false);
        }
    }

    /** The classic parser on {@code expression}, with {@code title} as the default field. */
    private static SearchQuery title(String expression) {
        return new SearchQuery(
                Optional.of(
                        new ClassicQuery(expression, "title", QueryOperator.OR, DEFAULTS, 2, false, false, STANDARD)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static DocumentField field(GetDocumentResult document, String name) {
        return document.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
