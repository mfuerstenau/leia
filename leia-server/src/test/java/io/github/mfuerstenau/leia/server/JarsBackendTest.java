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
import io.github.mfuerstenau.leia.protocol.jars.GetRuntimesParams;
import io.github.mfuerstenau.leia.protocol.jars.JarComponents;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeFolder;
import io.github.mfuerstenau.leia.protocol.jars.RuntimeState;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeParams;
import io.github.mfuerstenau.leia.runtime.LuceneRuntime;
import io.github.mfuerstenau.leia.server.runtime.FutureRuntime;
import java.io.IOException;
import java.io.InputStream;
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
 * Runtimes and jars loaded at the start and added while leia runs, through {@link LeiaBackend} (ADR
 * 0016, ADR 0018, ADR 0024, ADR 0038, ADR 0044, ADR 0068, ADR 0072, ADR 0073). Loading a jar changes Lucene's global lists for the whole JVM, and a loaded jar
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
        // Not on jarsTest's classpath, nor Lucene 10 itself: loaded from the runtimes' folders, as
        // leia loads runtimes/ (ADR 0072, ADR 0073).
        Path runtimes = testFile("leia.test.runtimes");

        // Without runtimes, the indexes of Lucene 9 and 10 open only without a reader, and there is no
        // analysis tab (ADR 0071, ADR 0072).
        try (RealBackend bare = RealBackend.start(Optional.empty(), List.of())) {
            for (String name : List.of("lucene9", "demo")) {
                String index = testIndex(name).toString();
                RpcException missing =
                        bare.fails(Methods.OPEN_INDEX, new OpenIndexParams(index), ErrorCode.MISSING_RUNTIME);
                assertThat(missing.getMessage()).as(name).contains("runtimes/lucene10", index);
            }
            assertThat(bare.call(
                                    Methods.OPEN_INDEX,
                                    new OpenIndexParams(
                                            testIndex("demo").toString(),
                                            Optional.empty(),
                                            Optional.of(OpenMode.DIRECTORY_ONLY)))
                            .mode())
                    .isEqualTo(OpenMode.DIRECTORY_ONLY);
            assertThat(bare.call(Methods.GET_RUNTIMES, new GetRuntimesParams()).runtimes())
                    .containsExactly(
                            missing("lucene10", 9, 10),
                            missing("lucene9", 8),
                            missing("lucene7", 6, 7),
                            missing("lucene5", 4, 5),
                            missing("lucene3", 2, 3));
            bare.fails(Methods.GET_ANALYSIS_COMPONENTS, new GetAnalysisComponentsParams(), ErrorCode.NOT_SUPPORTED);
        }

        // A folder that lacks a jar its adapter lists, and one whose runtime is built for another
        // runtime interface: neither loads, and leia starts all the same (ADR 0073).
        Path broken = Files.createDirectories(tmp.resolve("runtimes"));
        Path incomplete = Files.createDirectories(broken.resolve("lucene9"));
        Files.copy(runtimes.resolve("lucene9/leia-lucene9.jar"), incomplete.resolve("leia-lucene9.jar"));
        futureAdapterJar(Files.createDirectories(broken.resolve("lucene3")).resolve("leia-lucene3.jar"));
        try (RealBackend partial = RealBackend.start(Optional.of(broken), List.of())) {
            List<RuntimeFolder> found =
                    partial.call(Methods.GET_RUNTIMES, new GetRuntimesParams()).runtimes();
            assertThat(found.get(1)).satisfies(lucene9 -> {
                assertThat(lucene9.state()).isEqualTo(RuntimeState.INCOMPLETE);
                assertThat(lucene9.problem())
                        .hasValueSatisfying(jar -> assertThat(jar).endsWith(".jar"));
            });
            assertThat(found.getLast()).satisfies(lucene3 -> {
                assertThat(lucene3.state()).isEqualTo(RuntimeState.REFUSED);
                assertThat(lucene3.problem())
                        .hasValueSatisfying(reason -> assertThat(reason)
                                .contains("is built for runtime interface 99, leia has " + LuceneRuntime.API_VERSION));
            });
            String lucene8 = testIndex("lucene8").toString();
            RpcException missing =
                    partial.fails(Methods.OPEN_INDEX, new OpenIndexParams(lucene8), ErrorCode.MISSING_RUNTIME);
            assertThat(missing.getMessage()).contains("Lucene 8.11.4", "runtimes/lucene9", lucene8);
        }

        // A jar without its dependencies loads nothing, and nothing starts.
        assertThatThrownBy(() -> RealBackend.start(Optional.of(runtimes), List.of(phoneticJarAlone())))
                .isInstanceOfSatisfying(RpcException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_JAR);
                    assertThat(e.getMessage())
                            .startsWith("Cannot load the jars: cannot load the analysis factories")
                            .contains("org/apache/commons/codec/");
                });

        Path codecJar = Files.copy(testFile("leia.test.codec.jar"), tmp.resolve("leia-test-codec.jar"));
        // runtimes/lucene10 first, as the jars link against its Lucene (ADR 0065, ADR 0072).
        try (RealBackend backend = RealBackend.start(Optional.of(runtimes), List.of(codecJar))) {
            List<String> jars =
                    backend.call(Methods.GET_JARS, new GetJarsParams()).jars();
            assertThat(jars.getFirst()).startsWith(runtimes.resolve("lucene10").toString());
            assertThat(jars).endsWith(codecJar.toString());
            for (String name : List.of("lucene9", "demo")) {
                assertThat(backend.call(
                                        Methods.OPEN_INDEX,
                                        new OpenIndexParams(testIndex(name).toString()))
                                .luceneRuntime())
                        .as(name)
                        .startsWith("10.");
            }
            assertThat(backend.call(Methods.GET_RUNTIMES, new GetRuntimesParams())
                            .runtimes())
                    .allSatisfy(runtime -> {
                        assertThat(runtime.state()).isEqualTo(RuntimeState.LOADED);
                        assertThat(runtime.luceneVersion()).isPresent();
                    });

            // Indexes created by 8.x, with Lucene 9.12 in a class loader of its own (ADR 0049, ADR 0073).
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
            // A custom analyzer in shingles, through 9.12's own factories (ADR 0067, ADR 0073).
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

            // Through the Extensions of leia-lucene10's runtime (ADR 0068, ADR 0071): a directory's jars
            // in name order, the names they add, and a jar that is loaded already isn't loaded again. Before
            // the refused runtime below, which a new backend would refuse to start with.
            try (RealBackend runtime = RealBackend.start(Optional.empty(), List.of())) {
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

            // Lucene 5.5 finds its codecs in its own jars, those of 4.x in backward-codecs, though the
            // backend thread's context class loader sees Lucene 10's (ADR 0046, ADR 0073).
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

    /** A runtime's state when its folder is missing. */
    private static RuntimeFolder missing(String folder, Integer... majors) {
        return new RuntimeFolder(folder, List.of(majors), RuntimeState.MISSING, Optional.empty(), Optional.empty());
    }

    /**
     * An adapter jar with {@link FutureRuntime}, a runtime for interface 99, its services file and an
     * empty list.
     */
    private static void futureAdapterJar(Path jar) throws IOException {
        String path = FutureRuntime.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                InputStream bytes = FutureRuntime.class.getClassLoader().getResourceAsStream(path)) {
            out.putNextEntry(new JarEntry(path));
            bytes.transferTo(out);
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/services/" + LuceneRuntime.class.getName()));
            out.write((FutureRuntime.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/leia/runtime.txt"));
            out.closeEntry();
        }
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
