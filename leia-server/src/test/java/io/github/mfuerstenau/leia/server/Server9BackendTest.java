package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A fake server on Lucene 9.10 (ADR 0090), read with leia's Lucene 9 adapter. A server is loaded
 * once per JVM, so this runs in a JVM of its own, from serverTest.
 */
@Tag("server")
class Server9BackendTest {

    @Test
    void readsTheIndexesOfLucene8And9WithTheServersLucene() throws Exception {
        ServerBackends.missesTheServersFormat("9");

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
        }
    }
}
