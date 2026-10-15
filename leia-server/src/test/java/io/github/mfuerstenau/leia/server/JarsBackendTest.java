package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testFile;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CollationStrength;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.PresetOption;
import io.github.mfuerstenau.leia.protocol.analysis.WordSetSupport;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsParams;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.runtime.FutureRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Jars loaded at the start and added while leia runs, through {@link LeiaBackend} (ADR 0016, ADR
 * 0018, ADR 0024, ADR 0038, ADR 0044). Loading a jar changes Lucene's global lists for the whole JVM, and a loaded jar
 * that is gone stops every later load, so this is one flow, in jarsTest's JVM of its own.
 */
@Tag("jars")
class JarsBackendTest {

    private static final Path ANALYSIS_JARS = testFile("leia.test.analysis.jars");

    private static final String ICU_COLLATION = "org.apache.lucene.analysis.icu.ICUCollationKeyAnalyzer";

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

            // ICU's collation analyzer, with ICU's collator and the keywords of the tag (ADR 0038).
            backend.call(
                    Methods.ADD_JARS,
                    new AddJarsParams(List.of(ANALYSIS_JARS.resolve("icu").toString())));
            assertThat(backend.call(Methods.GET_ANALYSIS_COMPONENTS, new GetAnalysisComponentsParams())
                            .analyzers())
                    .contains(new AvailableAnalyzer(
                            "ICUCollationKeyAnalyzer",
                            ICU_COLLATION,
                            WordSetSupport.NONE,
                            WordSetSupport.NONE,
                            List.of(PresetOption.COLLATION)));
            var level1 = icuCollation(new Collation("de-u-ks-level1", Optional.empty(), Optional.empty()));
            assertThat(bytes(backend, level1, "Äpfel")).isEqualTo(bytes(backend, level1, "apfel"));
            var quaternary =
                    icuCollation(new Collation("de", Optional.of(CollationStrength.QUATERNARY), Optional.empty()));
            assertThat(bytes(backend, quaternary, "Äpfel")).isNotEqualTo(bytes(backend, quaternary, "apfel"));

            // A runtime built for another runtime interface is refused, and never used (ADR 0044).
            Path futureRuntime = futureRuntimeJar();
            RpcException refused = backend.fails(
                    Methods.ADD_JARS, new AddJarsParams(List.of(futureRuntime.toString())), ErrorCode.INVALID_JAR);
            assertThat(refused.getMessage())
                    .startsWith("Cannot add the jars: the runtime " + FutureRuntime.class.getName() + " in ")
                    .contains("is built for runtime interface 99, leia has " + LuceneRuntime.API_VERSION);
            assertThat(backend.call(
                                    Methods.OPEN_INDEX,
                                    new OpenIndexParams(testIndex("lucene24").toString()))
                            .luceneRuntime())
                    .startsWith("3.6");

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

    private static Analyzer icuCollation(Collation collation) {
        return new Analyzer(
                Optional.empty(),
                Optional.of(new PresetAnalyzer(
                        ICU_COLLATION,
                        Optional.empty(),
                        Optional.empty(),
                        OptionalInt.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(collation),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of());
    }

    /** The collation key bytes of the first token. */
    private static String bytes(RealBackend backend, Analyzer analyzer, String text) throws Exception {
        return backend
                .call(Methods.ANALYZE, new AnalyzeParams(analyzer, text, Optional.empty()))
                .stages()
                .getFirst()
                .tokens()
                .getFirst()
                .attributes()
                .stream()
                .filter(attribute -> attribute.key().equals("bytes"))
                .findFirst()
                .orElseThrow()
                .value();
    }

    /** A jar with only a services file, which names {@link FutureRuntime} of the test classpath. */
    private Path futureRuntimeJar() throws IOException {
        Path jar = tmp.resolve("future-runtime.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/" + LuceneRuntime.class.getName()));
            out.write((FutureRuntime.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
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
