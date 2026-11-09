package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testFile;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.index.CloseIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexResult;
import io.github.mfuerstenau.leia.protocol.search.Hit;
import io.github.mfuerstenau.leia.protocol.search.KnnQuery;
import io.github.mfuerstenau.leia.protocol.search.SearchQuery;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The fake OpenSearch on Lucene 9.10 with a k-NN plugin whose settings are unlike the real plugin's
 * (ADR 0092): the setup that gives the plugin a cluster service fails in leia's own code. It is best
 * effort, so leia still starts and reads the index, and what needs the cluster service fails as it
 * would without the setup. A server is loaded once per JVM, so this runs in a JVM of its own, from
 * serverTest.
 */
@Tag("server")
class Server9KnnSetupBackendTest {

    @Test
    void readsTheKnnIndexAlthoughTheSetupFails() throws Exception {
        System.setProperty("leia.test.server9.knn.foreignSettings", "true");
        try (RealBackend backend =
                RealBackend.start(testFile("leia.test.runtimes"), List.of(), List.of(ServerBackends.server("9")))) {
            OpenIndexResult opened = backend.call(
                    Methods.OPEN_INDEX,
                    new OpenIndexParams(testFile("leia.test.server9.knn").toString()));
            assertThat(opened.luceneRuntime()).isEqualTo("9.10.0");
            assertThat(opened.numDocs()).hasValue(3);

            SearchQuery nearest = new SearchQuery(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new KnnQuery(
                            "vector",
                            OptionalInt.of(0),
                            Optional.empty(),
                            OptionalInt.of(2),
                            false,
                            Optional.empty())));
            assertThat(backend.search(nearest, 10).hits())
                    .extracting(Hit::docId)
                    .containsExactly(0, 1);

            // Without the cluster service, the check and the close of the index fail.
            assertThat(ServerBackends.check(backend).indexes())
                    .singleElement()
                    .satisfies(index -> assertThat(index.clean()).as("clean").isFalse());
            assertThatThrownBy(() -> backend.call(Methods.CLOSE_INDEX, new CloseIndexParams()))
                    .isInstanceOfSatisfying(
                            RpcException.class,
                            failure -> assertThat(failure.code())
                                    .as(failure.getMessage())
                                    .isEqualTo(ErrorCode.INTERNAL_ERROR));

            // The backend still answers.
            assertThat(backend.call(
                                    Methods.OPEN_INDEX,
                                    new OpenIndexParams(testIndex("lucene8").toString()))
                            .luceneRuntime())
                    .isEqualTo("9.10.0");
        }
    }
}
