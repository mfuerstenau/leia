package io.github.mfuerstenau.leia.server;

import static io.github.mfuerstenau.leia.server.RealBackend.testFile;
import static io.github.mfuerstenau.leia.server.RealBackend.testIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableComponent;
import io.github.mfuerstenau.leia.protocol.analysis.Collation;
import io.github.mfuerstenau.leia.protocol.analysis.CollationStrength;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.PresetOption;
import io.github.mfuerstenau.leia.protocol.analysis.Shingles;
import io.github.mfuerstenau.leia.protocol.analysis.WordSetSupport;
import io.github.mfuerstenau.leia.protocol.check.CheckIndexResult;
import io.github.mfuerstenau.leia.protocol.check.GetCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.check.StartCheckIndexParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitParams;
import io.github.mfuerstenau.leia.protocol.commits.GetCommitsParams;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentParams;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsParams;
import io.github.mfuerstenau.leia.protocol.index.OpenIndexParams;
import io.github.mfuerstenau.leia.protocol.index.OpenMode;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.AddJarsResult;
import io.github.mfuerstenau.leia.protocol.jars.GetJarsParams;
import io.github.mfuerstenau.leia.protocol.jars.JarComponents;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.index.Routing;
import io.github.mfuerstenau.leia.server.runtime.FutureRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * 0018, ADR 0024, ADR 0038, ADR 0044, ADR 0068). Loading a jar changes Lucene's global lists for the whole JVM, and a loaded jar
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
        // Not on jarsTest's classpath: loaded as leia loads runtimes/ (ADR 0044).
        Path lucene3Jar = testFile("leia.test.runtime.lucene3");
        Path lucene5Jar = testFile("leia.test.runtime.lucene5");
        Path lucene7Jar = testFile("leia.test.runtime.lucene7");
        try (RealBackend backend = RealBackend.start(List.of(codecJar, lucene3Jar, lucene5Jar, lucene7Jar))) {
            assertThat(backend.call(Methods.GET_JARS, new GetJarsParams()).jars())
                    .containsExactly(
                            codecJar.toString(), lucene3Jar.toString(), lucene5Jar.toString(), lucene7Jar.toString());

            // An index created by 8.x needs leia-lucene9.jar: without it, it opens only without a
            // reader, with Lucene 10; once the jar is added, with its runtime (ADR 0049).
            String lucene8 = testIndex("lucene8").toString();
            RpcException missing =
                    backend.fails(Methods.OPEN_INDEX, new OpenIndexParams(lucene8), ErrorCode.MISSING_RUNTIME);
            assertThat(missing.getMessage()).contains("Lucene 8.11.4", "leia-lucene9.jar", lucene8);
            assertThat(backend.call(
                                    Methods.OPEN_INDEX,
                                    new OpenIndexParams(
                                            lucene8, Optional.empty(), Optional.of(OpenMode.DIRECTORY_ONLY)))
                            .mode())
                    .isEqualTo(OpenMode.DIRECTORY_ONLY);
            Path lucene9Jar = testFile("leia.test.runtime.lucene9");
            backend.call(Methods.ADD_JARS, new AddJarsParams(List.of(lucene9Jar.toString())));
            for (String name : List.of("lucene8", "lucene8to9")) {
                assertThat(backend.call(
                                        Methods.OPEN_INDEX,
                                        new OpenIndexParams(testIndex(name).toString()))
                                .luceneRuntime())
                        .isEqualTo("9.12.3");
                assertThat(backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams("pt"))
                                .segments())
                        .as(name)
                        .hasSize(2);
                assertThat(backend.call(Methods.GET_INDEX_STATS, new GetIndexStatsParams())
                                .indexes()
                                .getFirst()
                                .segments())
                        .as(name)
                        .hasSize(name.equals("lucene8") ? 2 : 3);
            }
            // Its memory segments and the KNN vectors of the 9.x segment.
            assertThat(backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(8))
                            .fields())
                    .filteredOn(field -> field.name().equals("embedding"))
                    .singleElement()
                    .satisfies(embedding -> assertThat(embedding.vector()).isPresent());
            // A custom analyzer in shingles, through the relocated factories of the loaded jar (ADR 0067).
            Analyzer custom = new Analyzer(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new CustomAnalyzer(
                            List.of(),
                            new AnalysisComponent("whitespace", Map.of()),
                            List.of(
                                    new AnalysisComponent("lowercase", Map.of()),
                                    new AnalysisComponent("porterStem", Map.of())),
                            Optional.empty())),
                    Optional.of(new Shingles(2, 2, "_", true, false, "-")),
                    Optional.empty(),
                    List.of(),
                    List.of());
            assertThat(backend.search(RealBackend.classic("Alpha Documents", custom), 10)
                            .query())
                    .isEqualTo("((+body:alpha +body:document) body:alpha_document)");

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

            // Through the Extensions of leia-lucene10's runtime (ADR 0068): a directory's jars in name
            // order, the names they add, and a jar that is loaded already isn't loaded again. Before
            // the refused runtime below, which a new backend would refuse to start with.
            try (RealBackend runtime = RealBackend.start(new Routing(true))) {
                AddJarsResult phonetic = runtime.call(
                        Methods.ADD_JARS,
                        new AddJarsParams(
                                List.of(ANALYSIS_JARS.resolve("phonetic").toString())));
                assertThat(phonetic.jars())
                        .extracting(jar -> Path.of(jar).getFileName().toString())
                        .satisfiesExactly(
                                codec -> assertThat(codec).startsWith("commons-codec-"),
                                jar -> assertThat(jar).startsWith("lucene-analysis-phonetic-"));
                assertThat(phonetic.alreadyLoaded()).isEmpty();
                assertThat(phonetic.added().tokenFilters()).contains("doubleMetaphone", "phonetic");
                assertThat(phonetic.added().analyzers()).isEmpty();
                assertThat(runtime.call(Methods.GET_ANALYSIS_COMPONENTS, new GetAnalysisComponentsParams())
                                .tokenFilters())
                        .contains(new AvailableComponent(
                                "doubleMetaphone", "org.apache.lucene.analysis.phonetic.DoubleMetaphoneFilterFactory"));
                assertThat(runtime.call(
                                Methods.ADD_JARS,
                                new AddJarsParams(List.of(phonetic.jars().getLast()))))
                        .isEqualTo(new AddJarsResult(
                                List.of(),
                                List.of(phonetic.jars().getLast()),
                                new JarComponents(
                                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                                        List.of())));
                assertThat(runtime.call(Methods.GET_JARS, new GetJarsParams()).jars())
                        .endsWith(phonetic.jars().toArray(String[]::new));
            }

            // A runtime built for another runtime interface is refused, and never used; the loaded
            // one reads the Lucene 2.4 index (ADR 0044).
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
            // Its commits and its check too, read anew by the runtime (ADR 0045).
            backend.call(
                    Methods.OPEN_INDEX, new OpenIndexParams(testIndex("lucene3").toString()));
            assertThat(backend.call(Methods.GET_COMMITS, new GetCommitsParams()).commits())
                    .hasSize(2);
            long check = backend.call(Methods.START_CHECK_INDEX, new StartCheckIndexParams())
                    .jobId();
            Optional<CheckIndexResult> checked = Optional.empty();
            for (int poll = 0; poll < 3000 && checked.isEmpty(); poll++) {
                Thread.sleep(10);
                checked = backend.call(Methods.GET_CHECK_INDEX, new GetCheckIndexParams(check, 0))
                        .result();
            }
            assertThat(checked)
                    .hasValueSatisfying(result -> assertThat(result.indexes())
                            .singleElement()
                            .satisfies(index -> assertThat(index.clean()).isTrue()));

            // Lucene 5.5 finds its relocated codecs in its own jar, those of 4.x in backward-codecs
            // (ADR 0046).
            for (String name : List.of("lucene4", "lucene5")) {
                assertThat(backend.call(
                                        Methods.OPEN_INDEX,
                                        new OpenIndexParams(testIndex(name).toString()))
                                .luceneRuntime())
                        .isEqualTo("5.5.5");
                assertThat(backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(0))
                                .fields())
                        .as(name)
                        .filteredOn(field -> field.name().equals("dv_set"))
                        .singleElement()
                        .satisfies(dvSet -> assertThat(dvSet.docValues()).isPresent());
                assertThat(backend.call(Methods.GET_COMMIT, new GetCommitParams(2, OptionalInt.empty()))
                                .segments())
                        .as(name)
                        .allSatisfy(segment -> assertThat(segment.codec()).isPresent());
            }

            // Lucene 7.7 likewise, with the codecs of 6.x in backward-codecs and the points (ADR 0048).
            for (String name : List.of("lucene6", "lucene7")) {
                assertThat(backend.call(
                                        Methods.OPEN_INDEX,
                                        new OpenIndexParams(testIndex(name).toString()))
                                .luceneRuntime())
                        .isEqualTo("7.7.3");
                assertThat(backend.call(Methods.GET_DOCUMENT, new GetDocumentParams(0))
                                .fields())
                        .as(name)
                        .filteredOn(field -> field.name().equals("dv_set"))
                        .singleElement()
                        .satisfies(dvSet -> assertThat(dvSet.docValues()).isPresent());
                assertThat(backend.call(Methods.GET_POINT_TREE, new GetPointTreeParams("pt"))
                                .segments())
                        .as(name)
                        .hasSize(2);
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
