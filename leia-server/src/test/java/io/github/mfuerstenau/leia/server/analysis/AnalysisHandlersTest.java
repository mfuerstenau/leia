package io.github.mfuerstenau.leia.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.ProtocolJson;
import io.github.mfuerstenau.leia.protocol.Response;
import io.github.mfuerstenau.leia.protocol.analysis.AnalysisComponent;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AvailableComponent;
import io.github.mfuerstenau.leia.protocol.analysis.CharFilterStep;
import io.github.mfuerstenau.leia.protocol.analysis.CustomAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsParams;
import io.github.mfuerstenau.leia.protocol.analysis.GetAnalysisComponentsResult;
import io.github.mfuerstenau.leia.protocol.analysis.PresetAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.StandardAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.Token;
import io.github.mfuerstenau.leia.protocol.analysis.TokenAttribute;
import io.github.mfuerstenau.leia.protocol.analysis.TokenStage;
import io.github.mfuerstenau.leia.server.RpcException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code getAnalysisComponents} and {@code analyze}. Neither needs an open index. */
class AnalysisHandlersTest {

    private static final Analyzer STANDARD =
            new Analyzer(Optional.of(new StandardAnalyzer()), Optional.empty(), Optional.empty());

    @TempDir
    Path tmp;

    private final GetAnalysisComponentsHandler getComponents = new GetAnalysisComponentsHandler();
    private final AnalyzeHandler analyze = new AnalyzeHandler();

    @Test
    void listsTheComponents() {
        GetAnalysisComponentsResult result = getComponents.handle(new GetAnalysisComponentsParams());

        assertThat(result.charFilters()).extracting(AvailableComponent::name).contains("htmlStrip");
        assertThat(result.tokenizers())
                .contains(new AvailableComponent(
                        "standard", "org.apache.lucene.analysis.standard.StandardTokenizerFactory"));
        assertThat(result.tokenFilters()).extracting(AvailableComponent::name).contains("lowercase", "stop");
        assertThat(result.analyzers())
                .contains(new AvailableAnalyzer("EnglishAnalyzer", "org.apache.lucene.analysis.en.EnglishAnalyzer"));
    }

    @Test
    void analyzesWithAPreset() throws Exception {
        AnalyzeResult result = analyze.handle(
                new AnalyzeParams(preset("org.apache.lucene.analysis.en.EnglishAnalyzer"), "The running foxes"));

        assertThat(result.charFilters()).isEmpty();
        assertThat(result.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.name()).isEqualTo("EnglishAnalyzer");
            assertThat(stage.tokens()).extracting(Token::term).containsExactly("run", "fox");
        });
        assertFails(
                () -> analyze.handle(new AnalyzeParams(preset("java.lang.Thread"), "x")), ErrorCode.INVALID_ANALYZER);
        var both = new Analyzer(STANDARD.standard(), preset("java.lang.Thread").preset(), Optional.empty());
        assertFails(() -> analyze.handle(new AnalyzeParams(both, "x")), ErrorCode.INVALID_PARAMS);
    }

    @Test
    void analyzesWithTheStandardAnalyzer() throws Exception {
        AnalyzeResult result = analyze.handle(new AnalyzeParams(STANDARD, "Quick Fox"));

        assertThat(result.charFilters()).isEmpty();
        assertThat(result.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.name()).isEqualTo("StandardAnalyzer");
            assertThat(stage.truncated()).isFalse();
            assertThat(stage.tokens().getFirst())
                    .isEqualTo(new Token(
                            "quick",
                            0,
                            1,
                            1,
                            0,
                            5,
                            "<ALPHANUM>",
                            List.of(
                                    new TokenAttribute("TermToBytesRefAttribute", "bytes", "[71 75 69 63 6b]"),
                                    new TokenAttribute("TermFrequencyAttribute", "termFrequency", "1"))));
        });
    }

    @Test
    void analyzesACustomAnalyzerStepByStep() throws Exception {
        Files.writeString(tmp.resolve("stop.txt"), "fox\n");
        var analyzer = custom(
                List.of(component("htmlStrip")),
                component("whitespace"),
                List.of(component("lowercase"), new AnalysisComponent("stop", Map.of("words", "stop.txt"))),
                Optional.of(tmp.toString()));

        AnalyzeResult result = analyze.handle(new AnalyzeParams(analyzer, "<i>Quick</i> FOX"));

        assertThat(result.charFilters()).containsExactly(new CharFilterStep("htmlStrip", "Quick FOX"));
        assertThat(result.stages()).extracting(TokenStage::name).containsExactly("whitespace", "lowercase", "stop");
        assertThat(result.stages().get(1).tokens()).extracting(Token::term).containsExactly("quick", "fox");
        assertThat(result.stages().get(2).tokens()).extracting(Token::term).containsExactly("quick");
    }

    @Test
    void checksTheParams() throws Exception {
        assertFails(
                () -> analyze.handle(new AnalyzeParams(STANDARD, "x".repeat(AnalyzeParams.MAX_TEXT_LENGTH + 1))),
                ErrorCode.INVALID_PARAMS);
        assertThat(analyze.handle(new AnalyzeParams(STANDARD, "x".repeat(AnalyzeParams.MAX_TEXT_LENGTH)))
                        .stages())
                .isNotEmpty();

        var both = new Analyzer(
                STANDARD.standard(),
                Optional.empty(),
                custom(List.of(), component("whitespace"), List.of(), Optional.empty())
                        .custom());
        assertFails(() -> analyze.handle(new AnalyzeParams(both, "x")), ErrorCode.INVALID_PARAMS);
        assertFails(
                () -> analyze.handle(
                        new AnalyzeParams(new Analyzer(Optional.empty(), Optional.empty(), Optional.empty()), "x")),
                ErrorCode.INVALID_PARAMS);
    }

    @Test
    void failsForAnAnalyzerThatCantBeBuilt() throws Exception {
        var unknown = custom(List.of(), component("nope"), List.of(), Optional.empty());
        assertThatThrownBy(() -> analyze.handle(new AnalyzeParams(unknown, "x")))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ANALYZER))
                .hasMessage("Invalid analyzer: no tokenizer 'nope'");
        assertThat(errorCodeOf("analyze.error-invalid-analyzer.json")).isEqualTo(ErrorCode.INVALID_ANALYZER);

        var noDir = custom(
                List.of(),
                component("whitespace"),
                List.of(),
                Optional.of(tmp.resolve("none").toString()));
        assertFails(() -> analyze.handle(new AnalyzeParams(noDir, "x")), ErrorCode.INVALID_ANALYZER);

        var badParam = custom(
                List.of(),
                component("whitespace"),
                List.of(new AnalysisComponent("lowercase", Map.of("a", "b"))),
                Optional.empty());
        assertFails(() -> analyze.handle(new AnalyzeParams(badParam, "x")), ErrorCode.INVALID_ANALYZER);
    }

    private static Analyzer preset(String className) {
        return new Analyzer(Optional.empty(), Optional.of(new PresetAnalyzer(className)), Optional.empty());
    }

    private static AnalysisComponent component(String name) {
        return new AnalysisComponent(name, Map.of());
    }

    private static Analyzer custom(
            List<AnalysisComponent> charFilters,
            AnalysisComponent tokenizer,
            List<AnalysisComponent> tokenFilters,
            Optional<String> configDir) {
        return new Analyzer(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new CustomAnalyzer(charFilters, tokenizer, tokenFilters, configDir)));
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static ErrorCode errorCodeOf(String fixture) throws IOException {
        String json = Files.readString(Path.of(System.getProperty("leia.protocol.dir"))
                .resolve("fixtures")
                .resolve(fixture));
        Response<?> response = ProtocolJson.mapper().readValue(json, ProtocolJson.responseType(Object.class));
        return response.error().orElseThrow().code();
    }
}
