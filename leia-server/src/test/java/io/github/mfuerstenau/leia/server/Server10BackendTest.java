package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A fake server on Lucene 10.2 (ADR 0090), read with leia's Lucene 10 adapter and leia-core, in a
 * class loader of their own beside the jar chain's Lucene 10. A server is loaded once per JVM, so
 * this runs in a JVM of its own, from serverTest.
 */
@Tag("server")
class Server10BackendTest {

    @Test
    void readsTheIndexesOfLucene9And10WithTheServersLucene() throws Exception {
        ServerBackends.missesTheServersFormat("10");

        try (RealBackend backend = ServerBackends.readsWithTheServer("10", "10.2.2")) {
            // Lucene 9.x, through the server's backward codecs.
            OpenIndexResult lucene9 = backend.call(
                    Methods.OPEN_INDEX, new OpenIndexParams(testIndex("lucene9").toString()));
            assertThat(lucene9.luceneRuntime()).isEqualTo("10.2.2");
            assertThat(lucene9.server()).hasValue(ServerBackends.label("10"));

            // Lucene 8.x isn't the server's: leia's Lucene 9 reads it.
            assertThat(backend.call(
                                    Methods.OPEN_INDEX,
                                    new OpenIndexParams(testIndex("lucene8").toString()))
                            .luceneRuntime())
                    .startsWith("9.12");
        }
    }
}
