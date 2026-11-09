package io.github.mfuerstenau.leia.tui.analysis;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.GetAutoStopWordsResult;
import io.github.mfuerstenau.leia.protocol.analysis.StartAutoStopWordsParams;
import io.github.mfuerstenau.leia.tui.TestScreens;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The auto stop words popup (ADR 0039). */
class AutoStopWordsFormTest {

    private static String render(AutoStopWordsForm form) {
        return TestScreens.render(frame -> form.render(frame, frame.area()), 100, 30);
    }

    @Test
    void picksTheThresholdAndTheFields() {
        var form = new AutoStopWordsForm(List.of("body", "title"), null, null);
        assertThat(render(form)).contains("% of the documents", "More than (%)", "40", "every indexed field");

        // The value: digits replace it once it would go over 100.
        form.handleKey(key(KeyCode.DOWN));
        form.handleKey(key(KeyCode.BACKSPACE));
        form.handleKey(key(KeyCode.BACKSPACE));
        form.handleKey(key('2'));
        form.handleKey(key('5'));
        // The second field.
        form.handleKey(key(KeyCode.DOWN));
        form.handleKey(key(KeyCode.DOWN));
        form.handleKey(key(' '));
        assertThat(form.params())
                .isEqualTo(
                        new StartAutoStopWordsParams(List.of("title"), OptionalInt.empty(), OptionalDouble.of(0.25)));

        // A document frequency instead.
        form.handleKey(key(KeyCode.HOME));
        form.handleKey(key(' '));
        form.handleKey(key(KeyCode.DOWN));
        form.handleKey(key('7'));
        assertThat(form.params())
                .isEqualTo(new StartAutoStopWordsParams(List.of("title"), OptionalInt.of(17), OptionalDouble.empty()));
        form.handleKey(key('a'));
        assertThat(form.params().fields()).containsExactly("body", "title");
        form.handleKey(key('n'));
        assertThat(form.params().fields()).isEmpty();
    }

    @Test
    void showsAndListsTheWordsFoundAndDropsThem() {
        var form = new AutoStopWordsForm(List.of("body", "title"), null, null);
        assertThat(form.handleKey(key(KeyCode.ENTER))).isInstanceOf(AutoStopWordsForm.Event.Start.class);
        assertThat(form.running()).isTrue();
        // Enter does nothing more while it runs, and Esc waits for the job.
        assertThat(form.handleKey(key(KeyCode.ENTER))).isEqualTo(new AutoStopWordsForm.Event.None());
        assertThat(form.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new AutoStopWordsForm.Event.None());
        form.started(4);
        var found = new AutoStopWords(2, List.of(new FieldStopWords("body", List.of("a", "the"))));
        form.progress(new GetAutoStopWordsResult(10, Optional.of(found)));
        assertThat(form.running()).isFalse();
        assertThat(render(form)).contains("2 words", "more than 2 documents");

        form.handleKey(key(KeyCode.END));
        form.handleKey(key(KeyCode.UP));
        form.handleKey(key('v'));
        assertThat(render(form)).contains("a, the");

        assertThat(form.handleKey(key('d'))).isEqualTo(new AutoStopWordsForm.Event.Clear());
        assertThat(render(form)).doesNotContain("2 words");
        assertThat(form.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new AutoStopWordsForm.Event.Close());
    }

    @Test
    void choosesTheFieldsOfTheWordsFoundBefore() {
        var found = new AutoStopWords(2, List.of(new FieldStopWords("title", List.of())));
        var form = new AutoStopWordsForm(List.of("body", "title"), null, found);

        assertThat(form.params().fields()).containsExactly("title");
    }
}
