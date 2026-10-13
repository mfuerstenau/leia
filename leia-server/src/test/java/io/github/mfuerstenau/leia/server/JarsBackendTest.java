package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testFile;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Jars loaded at the start and added while leia runs, through {@link LeiaBackend} (ADR 0016, ADR
 * 0018, ADR 0024). Loading a jar changes Lucene's global lists for the whole JVM, and a loaded jar
 * that is gone stops every later load, so this is one flow, in jarsTest's JVM of its own.
 */
@Tag("jars")
class JarsBackendTest {

    private static final Path ANALYSIS_JARS = testFile("leia.test.analysis.jars");

    @TempDir
    Path tmp;

    @Test
    void loadsTheJarsThatTheIndexesNeedAndStopsWhenALoadedOneIsGone() throws Exception {
        // A jar without its dependencies loads nothing, and nothing starts.
        assertThatThrownBy(() -> RealBackend.start(List.of(phoneticJarAlone())))
                .isInstanceOfSatisfying(RpcException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_JAR);
                    assertThat(e.getMessage())
                            .startsWith("Cannot load the jars: cannot load the analysis factories")
                            .contains("org/apache/commons/codec/");
                });

        Path codecJar = Files.copy(testFile("leia.test.codec.jar"), tmp.resolve("leia-test-codec.jar"));
        try (RealBackend backend = RealBackend.start(List.of(codecJar))) {
            assertThat(backend.call(Methods.GET_JARS, new GetJarsParams()).jars())
                    .containsExactly(codecJar.toString());

            // The codec, and Lucene's codec with the jar's postings, doc values and KNN vectors formats.
            for (String name : List.of("custom-codec", "custom-formats")) {
                assertThat(backend.call(
                                        Methods.OPEN_INDEX,
                                        new OpenIndexParams(testIndex(name).toString()))
                                .maxDoc())
                        .as(name)
                        .hasValue(3);
                List<DocumentField> fields = backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(2))
                        .fields();
                assertThat(fields)
                        .as(name)
                        .filteredOn(field -> field.name().equals("rank"))
                        .singleElement()
                        .satisfies(rank -> assertThat(rank.docValues()).isPresent());
                assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams(OptionalInt.empty()))
                                .commits())
                        .as(name)
                        .hasSize(1);
            }

            // Lucene reads the service files of every loaded jar again, so a jar that is gone stops it.
            Files.delete(codecJar);
            RpcException gone = backend.fails(
                    Methods.ADD_JARS,
                    new AddJarsParams(List.of(ANALYSIS_JARS.resolve("stempel").toString())),
                    ErrorCode.INVALID_JAR);
            assertThat(gone.getMessage())
                    .startsWith("Cannot add the jars: the jar " + codecJar + " was loaded before and is gone");
        }
    }

    /** The phonetic analysis jar without commons-codec, which its factories need. */
    private static Path phoneticJarAlone() throws IOException {
        try (Stream<Path> jars = Files.list(ANALYSIS_JARS.resolve("phonetic"))) {
            return jars.filter(jar -> jar.getFileName().toString().startsWith("lucene-analysis-phonetic-"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
