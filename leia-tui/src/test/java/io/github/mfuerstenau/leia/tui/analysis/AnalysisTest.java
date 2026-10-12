package io.github.mfuerstenau.leia.tui.analysis;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeParams;
import io.github.mfuerstenau.leia.protocol.analysis.AnalyzeResult;
import io.github.mfuerstenau.leia.protocol.analysis.CharFilterStep;
import io.github.mfuerstenau.leia.protocol.analysis.Token;
import io.github.mfuerstenau.leia.protocol.analysis.TokenAttribute;
import io.github.mfuerstenau.leia.protocol.analysis.TokenStage;
import io.github.mfuerstenau.leia.tui.TestScreens;
import io.github.mfuerstenau.leia.tui.analysis.Analysis.Request;
import io.github.mfuerstenau.leia.tui.analysis.Analysis.Step;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The tests of {@code analysis.rs}, ported. */
class AnalysisTest {

    private static Optional<Request> press(Analysis analysis, KeyEvent key) {
        return analysis.handleKey(key);
    }

    private static void typed(Analysis analysis, String text) {
        text.codePoints()
                .forEach(c -> assertThat(press(analysis, KeyEvent.ofChar(c))).isEmpty());
    }

    private static Token token(String term, int position, int increment) {
        return new Token(
                term,
                position,
                increment,
                1,
                0,
                term.length(),
                "<ALPHANUM>",
                List.of(new TokenAttribute("KeywordAttribute", "keyword", "false")));
    }

    private static AnalyzeResult result() {
        return new AnalyzeResult(
                List.of(new CharFilterStep("htmlStrip", "The Fox")),
                List.of(
                        new TokenStage("standard", List.of(token("The", 0, 1), token("Fox", 1, 1)), false),
                        new TokenStage("stop", List.of(token("fox", 1, 2)), false)));
    }

    private static AnalyzeResult empty() {
        return new AnalyzeResult(List.of(), List.of());
    }

    private static String render(Analysis analysis, int width) {
        return TestScreens.render(frame -> analysis.render(frame, frame.area()), width, 30);
    }

    private static Request analyze(String text) {
        return new Request.Analyze(new AnalyzeParams(AnalyzerSettings.standardAnalyzer(), text));
    }

    @Test
    void asksForTheComponentsOnce() {
        Analysis analysis = new Analysis();
        assertThat(analysis.initialRequest()).hasValue(new Request.Components());
        analysis.componentsFailed("down");
        assertThat(analysis.initialRequest()).isEmpty();
        assertThat(render(analysis, 100)).contains("Cannot list the components: down");
    }

    @Test
    void analyzesTheTypedTextWithTheCurrentAnalyzer() {
        Analysis analysis = new Analysis();
        assertThat(press(analysis, key('r'))).isEmpty();
        press(analysis, key('/'));
        assertThat(analysis.prompting()).isTrue();
        typed(analysis, "<b>The</b> Fox");
        assertThat(press(analysis, key(KeyCode.ENTER))).hasValue(analyze("<b>The</b> Fox"));
        assertThat(analysis.prompting()).isFalse();
        analysis.analyzed("<b>The</b> Fox", result());

        // The last stage is selected, with its first token.
        assertThat(analysis.step()).hasValue(new Step.Stage(1));
        assertThat(analysis.selectedToken().orElseThrow().term()).isEqualTo("fox");
        assertThat(render(analysis, 130))
                .contains("standard analyzer", "htmlStrip", "Tokens after stop · 1", "1·", "KeywordAttribute");

        // r analyzes again; the steps go up to the char filter's text.
        assertThat(press(analysis, key('r'))).containsInstanceOf(Request.Analyze.class);
        press(analysis, KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
        press(analysis, key(KeyCode.UP));
        assertThat(analysis.step()).hasValue(new Step.Stage(0));
        assertThat(analysis.selectedToken().orElseThrow().term()).isEqualTo("The");
        press(analysis, key(KeyCode.UP));
        assertThat(analysis.step()).hasValue(new Step.CharFilter(0));
        assertThat(analysis.selectedToken()).isEmpty();
        assertThat(render(analysis, 100)).contains("Text after htmlStrip");
    }

    @Test
    void refusesATextTheBackendWouldRefuse() {
        Analysis analysis = new Analysis();
        press(analysis, key('/'));
        analysis.input().set("é".repeat(AnalyzeParams.MAX_TEXT_LENGTH + 1));
        assertThat(press(analysis, key(KeyCode.ENTER))).isEmpty();
        assertThat(analysis.prompting()).isTrue();
        assertThat(analysis.error())
                .hasValueSatisfying(error -> assertThat(error).contains("longer"));
    }

    @Test
    void anAppliedAnalyzerBecomesCurrentOnceTheBackendTookIt() {
        Analysis analysis = new Analysis();
        press(analysis, key('b'));
        // ← switches to the custom chain; Enter applies it.
        press(analysis, key(KeyCode.LEFT));
        Optional<Request> applied = press(analysis, key(KeyCode.ENTER));
        assertThat(applied).containsInstanceOf(Request.Analyze.class);
        assertThat(((Request.Analyze) applied.orElseThrow()).params().analyzer().custom())
                .isPresent();
        assertThat(analysis.settings().isCustom()).isFalse();

        // Refused: the form stays open with the error, the analyzer stays as it was.
        analysis.analyzeFailed("Invalid analyzer: no tokenizer 'x'");
        assertThat(analysis.prompting()).isTrue();
        assertThat(analysis.settings().isCustom()).isFalse();
        assertThat(render(analysis, 100)).contains("no tokenizer 'x'");

        // Taken: the form closes and the analyzer is the current one.
        press(analysis, key(KeyCode.ENTER));
        analysis.analyzed("", empty());
        assertThat(analysis.prompting()).isFalse();
        assertThat(analysis.settings().isCustom()).isTrue();
        assertThat(render(analysis, 100)).contains("standard › lowercase");

        // Esc closes the form without a change.
        press(analysis, key('b'));
        press(analysis, key(' '));
        assertThat(press(analysis, key(KeyCode.ESCAPE))).isEmpty();
        assertThat(analysis.prompting()).isFalse();
        assertThat(analysis.settings().isCustom()).isTrue();
    }

    @Test
    void analyzesAValueFromElsewhereCutToWhatTheBackendTakes() {
        Analysis analysis = new Analysis();
        assertThat(analysis.analyzeValue("doc 3 · body", "The Fox")).hasValue(analyze("The Fox"));
        analysis.analyzed("The Fox", result());
        assertThat(render(analysis, 100)).contains("Text · doc 3 · body");

        // Once edited, the text is no longer the value.
        press(analysis, key('/'));
        typed(analysis, "!");
        assertThat(render(analysis, 100)).doesNotContain("doc 3");

        // A surrogate pair counts two, and is not split.
        String start = "a".repeat(AnalyzeParams.MAX_TEXT_LENGTH - 1);
        assertThat(analysis.analyzeValue("doc 4 · body", start + "🦊")).hasValue(analyze(start));
        assertThat(analysis.prompting()).isFalse();
        assertThat(render(analysis, 120))
                .contains("doc 4 · body · first " + AnalyzeParams.MAX_TEXT_LENGTH + " characters");
        assertThat(Analysis.withinLimit("🦊")).isEqualTo("🦊");
    }

    @Test
    void positionsShowGapsAndSpans() {
        assertThat(Analysis.positionText(token("fox", 3, 1))).isEqualTo("3");
        assertThat(Analysis.positionText(token("fox", 3, 2))).isEqualTo("3·");
        Token span = new Token("wireless", 1, 1, 2, 0, 8, "SYNONYM", List.of());
        assertThat(Analysis.positionText(span)).isEqualTo("1–2");
    }

    @Test
    void showsWhyTheAnalysisFailed() {
        Analysis analysis = new Analysis();
        analysis.analyzeFailed("Invalid analyzer: config directory: /x is not a directory");
        assertThat(render(analysis, 100)).contains("/x is not a directory");
    }

    @Test
    void theDetailsScrollNoFurtherThanTheLastLine() {
        Analysis analysis = new Analysis();
        analysis.analyzeValue("doc 3 · body", "The Fox");
        analysis.analyzed("The Fox", result());
        press(analysis, key(KeyCode.TAB));
        press(analysis, key(KeyCode.END));
        render(analysis, 100);
        // The next key after End moves at once.
        press(analysis, key(KeyCode.UP));
        assertThat(render(analysis, 100)).contains("KeywordAttribute");
    }
}
