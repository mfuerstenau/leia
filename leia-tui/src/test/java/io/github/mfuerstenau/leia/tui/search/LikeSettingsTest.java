package io.github.mfuerstenau.leia.tui.search;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.tui.analysis.AnalyzerSettings;
import io.github.mfuerstenau.leia.tui.search.LikeSettings.LikeSource;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The tests of {@code more_like_this.rs}, ported. */
class LikeSettingsTest {

    private static final Analyzer STANDARD = AnalyzerSettings.standardAnalyzer();

    static IndexField field(String name, IndexOptions indexOptions) {
        return new IndexField(
                name,
                0,
                indexOptions,
                false,
                false,
                false,
                DocValuesType.NONE,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static LikeSettings settings() {
        return LikeSettings.of(List.of(
                field("id", IndexOptions.DOCS),
                field("title", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS),
                field("price", IndexOptions.NONE),
                field("body", IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)));
    }

    private static void press(LikeForm form, KeyEvent... keys) {
        for (KeyEvent key : keys) {
            form.handleKey(key);
        }
    }

    private static void press(LikeForm form, KeyCode code, int times) {
        for (int i = 0; i < times; i++) {
            form.handleKey(key(code));
        }
    }

    private static LikeSettings applied(LikeForm form) {
        Form.Event<LikeSettings> event = form.handleKey(key(KeyCode.ENTER));
        assertThat(event).isInstanceOf(Form.Event.Apply.class);
        return ((Form.Event.Apply<LikeSettings>) event).draft();
    }

    @Test
    void startsFromLucenesDefaultsWithTheTextFields() {
        LikeSettings settings = settings();
        assertThat(settings.query(new LikeSource.Doc(42), STANDARD))
                .hasValue(new MoreLikeThisQuery(
                        OptionalInt.of(42),
                        Optional.empty(),
                        List.of("title", "body"),
                        2,
                        5,
                        OptionalInt.empty(),
                        25,
                        false,
                        STANDARD,
                        0,
                        OptionalInt.empty(),
                        List.of()));
        assertThat(settings.fieldsText()).isEqualTo("fields title · body");
        assertThat(settings.limitsText()).isEqualTo("term freq ≥ 2 · doc freq ≥ 5 · 25 terms");
        MoreLikeThisQuery text =
                settings.query(new LikeSource.Text("red lamp"), STANDARD).orElseThrow();
        assertThat(text.docId()).isEmpty();
        assertThat(text.text()).hasValue("red lamp");

        // Without a text field, the first indexed one.
        LikeSettings keyword =
                LikeSettings.of(List.of(field("price", IndexOptions.NONE), field("id", IndexOptions.DOCS)));
        assertThat(keyword.query(new LikeSource.Doc(0), STANDARD).map(MoreLikeThisQuery::fields))
                .hasValue(List.of("id"));
    }

    @Test
    void editsTheLimitsAndTheFields() {
        LikeForm form = new LikeForm(settings());
        press(form, key(KeyCode.BACKSPACE), key('1'));
        press(form, key(KeyCode.DOWN), key(KeyCode.LEFT), key(KeyCode.DOWN));
        press(form, key('1'), key('0'), key('0'));
        press(form, key(KeyCode.DOWN), key(KeyCode.BACKSPACE));
        press(form, key(KeyCode.DOWN), key(' '));
        // Min and max word length.
        press(form, key(KeyCode.DOWN), key('3'));
        press(form, key(KeyCode.DOWN), key('2'), key('0'));
        // Past the stop words, the first field row: id.
        press(form, key(KeyCode.DOWN), key(KeyCode.DOWN), key(' '));
        LikeSettings edited = applied(form);

        MoreLikeThisQuery query = edited.query(new LikeSource.Doc(7), STANDARD).orElseThrow();
        assertThat(query.minTermFreq()).isEqualTo(1);
        assertThat(query.minDocFreq()).isEqualTo(4);
        assertThat(query.maxDocFreq()).hasValue(100);
        assertThat(query.maxQueryTerms()).isEqualTo(2);
        assertThat(query.boost()).isTrue();
        assertThat(query.minWordLen()).isEqualTo(3);
        assertThat(query.maxWordLen()).hasValue(20);
        assertThat(query.fields()).containsExactly("id", "title", "body");
        assertThat(edited.fieldsText()).isEqualTo("fields id · title · body");
        assertThat(edited.limitsText())
                .isEqualTo("term freq ≥ 1 · doc freq 4 to 100 · 2 terms · boost · words 3 to 20");

        // A max word length below the min one applies as the min one.
        form = new LikeForm(edited);
        press(form, KeyCode.DOWN, 6);
        press(form, key(KeyCode.BACKSPACE), key(KeyCode.BACKSPACE), key('2'));
        assertThat(applied(form).limitsText()).endsWith(" · words 3 to 3");
    }

    @Test
    void typesTheStopWords() {
        LikeForm form = new LikeForm(settings());
        press(form, KeyCode.DOWN, 7);
        form.handleKey(key(KeyCode.RIGHT));
        assertThat(form.editing()).isTrue();
        assertThat(form.keys()).contains("take");
        "a, the  with,a".chars().forEach(c -> form.handleKey(key((char) c)));
        form.handleKey(key(KeyCode.ENTER));
        assertThat(form.editing()).isFalse();
        LikeSettings words = applied(form);
        assertThat(words.query(new LikeSource.Doc(0), STANDARD).map(MoreLikeThisQuery::stopWords))
                .hasValue(List.of("a", "the", "with"));
        assertThat(words.limitsText()).endsWith(" · stop words a the with");

        // Editing starts from the words; Esc keeps them, d clears them.
        LikeForm again = new LikeForm(words);
        press(again, KeyCode.DOWN, 7);
        press(again, key(' '), key(KeyCode.BACKSPACE), key(KeyCode.ESCAPE));
        assertThat(again.draft().stopWords).hasSize(3);
        again.handleKey(key('d'));
        assertThat(applied(again).stopWords).isEmpty();

        assertThat(LikeSettings.stopWordsText(List.of("w0", "w1", "w2", "w3", "w4")))
                .isEqualTo("5 stop words");
    }

    @Test
    void needsAFieldAndAQueryTerm() {
        LikeForm form = new LikeForm(settings());
        form.handleKey(key('n'));
        assertThat(form.handleKey(key(KeyCode.ENTER))).isEqualTo(new Form.Event.None<>());
        assertThat(form.noField()).isTrue();
        form.handleKey(key('a'));
        assertThat(form.noField()).isFalse();

        // Max query terms: Backspace twice leaves 0, which applies as 1; ← stops at 1.
        press(form, KeyCode.DOWN, 3);
        press(form, KeyCode.BACKSPACE, 2);
        LikeSettings one = applied(form);
        assertThat(one.query(new LikeSource.Doc(0), STANDARD).map(MoreLikeThisQuery::maxQueryTerms))
                .hasValue(1);
        assertThat(one.fieldsText()).isEqualTo("fields id · title · body");
        form = new LikeForm(one);
        press(form, key('n'), key(KeyCode.END), key(' '));
        assertThat(applied(form).fieldsText()).isEqualTo("field body");

        form = new LikeForm(one);
        form.handleKey(key(KeyCode.END));
        press(form, KeyCode.UP, 7);
        form.handleKey(key(KeyCode.LEFT));
        assertThat(form.draft().maxQueryTerms).isEqualTo(1);
        assertThat(form.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new Form.Event.Cancel<>());
    }
}
