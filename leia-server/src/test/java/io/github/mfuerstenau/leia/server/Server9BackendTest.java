package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.Feature;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A fake server on Lucene 9.10 (ADR 0090), read with leia's Lucene 9 adapter, and its fake k-NN plugin
 * (ADR 0092). A server is loaded once per JVM, so this runs in a JVM of its own, from serverTest.
 */
@Tag("server")
class Server9BackendTest {

    @Test
    void readsTheIndexesOfLucene8And9AndOfTheKnnPluginWithTheServersLucene() throws Exception {
        ServerBackends.missesTheServersFormat("9");
        missesThePluginsCodec();

        try (RealBackend backend = ServerBackends.readsWithTheServer("9", "9.10.0")) {
            // Lucene 8.x too, which the server reads before leia's Lucene 9.12 does.
            OpenIndexResult lucene8 = backend.call(
                    Methods.OPEN_INDEX, new OpenIndexParams(testIndex("lucene8").toString()));
            assertThat(lucene8.luceneRuntime()).isEqualTo("9.10.0");
            assertThat(lucene8.server()).hasValue(ServerBackends.label("9"));

            // Written by 9.12, whose postings 9.10 lacks: Lucene 10 reads it, as without the server.
            OpenIndexResult lucene9 = backend.call(
                    Methods.OPEN_INDEX, new OpenIndexParams(testIndex("lucene9").toString()));
            assertThat(lucene9.luceneRuntime()).startsWith("10.");
            assertThat(lucene9.server()).isEmpty();

            readsTheIndexOfTheKnnPlugin(backend);
        }
    }

    /** Without the server, the plugin's codec is as unknown as the server's postings format. */
    private static void missesThePluginsCodec() throws Exception {
        try (RealBackend backend = RealBackend.start()) {
            RpcException missing =
                    backend.fails(Methods.OPEN_INDEX, new OpenIndexParams(knnIndex()), ErrorCode.MISSING_CODEC);
            assertThat(missing.getMessage()).contains("LeiaKnn");
        }
    }

    /**
     * The plugin's codec is found in {@code plugins/opensearch-knn}, its vectors format needs a class of
     * the module it extends, and its reader needs the setup that gives the plugin a cluster service: it
     * fails as an index is closed or checked without.
     */
    private static void readsTheIndexOfTheKnnPlugin(RealBackend backend) throws Exception {
        OpenIndexResult opened = backend.call(Methods.OPEN_INDEX, new OpenIndexParams(knnIndex()));
        assertThat(opened.luceneRuntime()).isEqualTo("9.10.0");
        assertThat(opened.server()).hasValue(ServerBackends.label("9"));
        assertThat(opened.numDocs()).hasValue(3);
        assertThat(opened.features()).contains(Feature.KNN_VECTORS);

        SearchQuery nearest = new SearchQuery(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new KnnQuery(
                        "vector", OptionalInt.of(0), Optional.empty(), OptionalInt.of(2), false, Optional.empty())));
        assertThat(backend.search(nearest, 10).hits()).extracting(Hit::docId).containsExactly(0, 1);

        assertThat(ServerBackends.check(backend).indexes())
                .singleElement()
                .satisfies(index ->
                        assertThat(index.clean()).as(index.error().toString()).isTrue());
        backend.call(Methods.CLOSE_INDEX, new CloseIndexParams());
    }

    private static String knnIndex() {
        return RealBackend.testFile("leia.test.server9.knn").toString();
    }
}
