package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.core.AnalysisComponents.Available;
import io.github.mfuerstenau.leia.core.AnalyzerSpec.Component;
import io.github.mfuerstenau.leia.core.TextAnalysis.CharFilterStep;
import io.github.mfuerstenau.leia.core.TextAnalysis.Token;
import io.github.mfuerstenau.leia.core.TextAnalysis.TokenAttribute;
import io.github.mfuerstenau.leia.core.TextAnalysis.TokenStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.analysis.Analyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzersTest {

    @TempDir
    Path tmp;

    @Test
    void listsTheComponentsSortedByNameIgnoringCase() {
        AnalysisComponents components = Analyzers.components();

        assertThat(components.charFilters())
                .contains(
                        new Available("htmlStrip", "org.apache.lucene.analysis.charfilter.HTMLStripCharFilterFactory"));
        assertThat(components.tokenizers())
                .contains(new Available("standard", "org.apache.lucene.analysis.standard.StandardTokenizerFactory"))
                .contains(new Available("whitespace", "org.apache.lucene.analysis.core.WhitespaceTokenizerFactory"));
        assertThat(components.tokenFilters())
                .contains(new Available("lowercase", "org.apache.lucene.analysis.core.LowerCaseFilterFactory"));
        assertThat(components.tokenFilters())
                .extracting(Available::name)
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void theStandardAnalyzerIsOneStage() throws Exception {
        TextAnalysis analysis = analyze(new AnalyzerSpec.Standard(), "The Quick fox", 100);

        assertThat(analysis.charFilters()).isEmpty();
        assertThat(analysis.stages()).singleElement().satisfies(stage -> {
            assertThat(stage.name()).isEqualTo("StandardAnalyzer");
            assertThat(stage.truncated()).isFalse();
            assertThat(stage.tokens()).extracting(Token::term).containsExactly("the", "quick", "fox");
            assertThat(stage.tokens()).extracting(Token::position).containsExactly(0, 1, 2);
            Token quick = stage.tokens().get(1);
            assertThat(quick.startOffset()).isEqualTo(4);
            assertThat(quick.endOffset()).isEqualTo(9);
            assertThat(quick.positionIncrement()).isEqualTo(1);
            assertThat(quick.positionLength()).isEqualTo(1);
            assertThat(quick.type()).isEqualTo("<ALPHANUM>");
        });
    }

    @Test
    void aCustomAnalyzerIsShownStepByStep() throws Exception {
        var spec = custom(
                List.of(new Component("htmlStrip", Map.of())),
                new Component("standard", Map.of()),
                List.of(new Component("lowercase", Map.of()), new Component("stop", Map.of())));

        TextAnalysis analysis = analyze(spec, "<b>The</b> Fox and THE dog", 100);

        assertThat(analysis.charFilters()).containsExactly(new CharFilterStep("htmlStrip", "The Fox and THE dog"));
        assertThat(analysis.stages()).extracting(TokenStage::name).containsExactly("standard", "lowercase", "stop");
        assertThat(analysis.stages().get(0).tokens())
                .extracting(Token::term)
                .containsExactly("The", "Fox", "and", "THE", "dog");
        assertThat(analysis.stages().get(1).tokens())
                .extracting(Token::term)
                .containsExactly("the", "fox", "and", "the", "dog");
        // The stop filter's default words are English: the gaps stay in the positions.
        List<Token> stopped = analysis.stages().get(2).tokens();
        assertThat(stopped).extracting(Token::term).containsExactly("fox", "dog");
        assertThat(stopped).extracting(Token::position).containsExactly(1, 4);
        assertThat(stopped).extracting(Token::positionIncrement).containsExactly(2, 3);
        // Offsets are into the original text, before the char filter took the tags out.
        assertThat(stopped.getFirst().startOffset()).isEqualTo(11);
        assertThat(stopped.getFirst().endOffset()).isEqualTo(14);
    }

    @Test
    void attributesWithoutAFieldOfTheirOwnAreListed() throws Exception {
        var spec = custom(
                List.of(), new Component("whitespace", Map.of()), List.of(new Component("keywordRepeat", Map.of())));

        List<Token> tokens = analyze(spec, "fox", 100).stages().get(1).tokens();

        assertThat(tokens).hasSize(2);
        assertThat(tokens.getFirst().attributes())
                .contains(new TokenAttribute("KeywordAttribute", "keyword", "true"))
                .contains(new TokenAttribute("TermToBytesRefAttribute", "bytes", "[66 6f 78]"))
                .extracting(TokenAttribute::attribute)
                .doesNotContain("CharTermAttribute", "OffsetAttribute", "PositionIncrementAttribute", "TypeAttribute");
        assertThat(tokens.get(1).attributes()).contains(new TokenAttribute("KeywordAttribute", "keyword", "false"));
    }

    @Test
    void aStageStopsAtTheTokenLimit() throws Exception {
        TextAnalysis analysis = analyze(new AnalyzerSpec.Standard(), "a b c", 2);
        assertThat(analysis.stages().getFirst().tokens())
                .extracting(Token::term)
                .containsExactly("a", "b");
        assertThat(analysis.stages().getFirst().truncated()).isTrue();

        TextAnalysis exact = analyze(new AnalyzerSpec.Standard(), "a b", 2);
        assertThat(exact.stages().getFirst().truncated()).isFalse();

        assertThat(analyze(new AnalyzerSpec.Standard(), "", 2)
                        .stages()
                        .getFirst()
                        .tokens())
                .isEmpty();
    }

    @Test
    void readsResourceFilesFromTheConfigDirectory() throws Exception {
        Files.writeString(tmp.resolve("stop.txt"), "# comment\nfox\n");
        var spec = new AnalyzerSpec.Custom(
                List.of(),
                new Component("whitespace", Map.of()),
                List.of(new Component("stop", Map.of("words", "stop.txt"))),
                Optional.of(tmp));

        assertThat(analyze(spec, "the fox", 100).stages().get(1).tokens())
                .extracting(Token::term)
                .containsExactly("the");
    }

    @Test
    void anAnalyzerThatCantBeBuiltSaysWhichComponentFailed() {
        assertThatThrownBy(() -> Analyzers.build(custom(List.of(), new Component("nope", Map.of()), List.of())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("no tokenizer 'nope'");
        assertThatThrownBy(() -> Analyzers.build(custom(
                        List.of(),
                        new Component("standard", Map.of()),
                        List.of(new Component("lowercase", Map.of("foo", "bar"))))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("token filter 'lowercase': Unknown parameters: {foo=bar}");
        assertThatThrownBy(() -> Analyzers.build(
                        custom(List.of(), new Component("standard", Map.of("maxTokenLength", "x")), List.of())))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessageStartingWith("tokenizer 'standard': ");
        assertThatThrownBy(() -> Analyzers.build(custom(
                        List.of(),
                        new Component("standard", Map.of()),
                        List.of(new Component("stop", Map.of("words", "missing.txt"))))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessage("token filter 'stop': Resource not found: missing.txt");
        assertThatThrownBy(() -> Analyzers.build(new AnalyzerSpec.Custom(
                        List.of(), new Component("standard", Map.of()), List.of(), Optional.of(tmp.resolve("none")))))
                .isInstanceOf(InvalidAnalyzerException.class)
                .hasMessageStartingWith("config directory: ");
    }

    @Test
    void componentNamesIgnoreCase() throws Exception {
        var spec =
                custom(List.of(), new Component("Whitespace", Map.of()), List.of(new Component("LowerCase", Map.of())));

        TextAnalysis analysis = analyze(spec, "Fox", 100);

        assertThat(analysis.stages()).extracting(TokenStage::name).containsExactly("whitespace", "lowercase");
    }

    private static AnalyzerSpec.Custom custom(
            List<Component> charFilters, Component tokenizer, List<Component> filters) {
        return new AnalyzerSpec.Custom(charFilters, tokenizer, filters, Optional.empty());
    }

    private static TextAnalysis analyze(AnalyzerSpec spec, String text, int maxTokens) throws Exception {
        try (Analyzer analyzer = Analyzers.build(spec)) {
            return Analyzers.analyze(analyzer, text, maxTokens);
        }
    }
}
