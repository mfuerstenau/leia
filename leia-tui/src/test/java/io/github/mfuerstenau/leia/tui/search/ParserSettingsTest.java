package io.github.mfuerstenau.leia.tui.search;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The tests of {@code parser_options.rs}, ported. */
class ParserSettingsTest {

    private static IndexField field(String name, int dimensions, int bytes) {
        return new IndexField(
                name,
                0,
                IndexOptions.NONE,
                false,
                false,
                false,
                DocValuesType.NONE,
                dimensions,
                bytes,
                0,
                Optional.empty());
    }

    private static ParserSettings settings() {
        return ParserSettings.of(List.of(
                field("body", 0, 0),
                field("quantity", 1, 4),
                field("price", 1, 8),
                field("location", 2, 4),
                field("ip", 1, 16)));
    }

    private static void press(OptionsForm form, KeyEvent... keys) {
        for (KeyEvent key : keys) {
            assertThat(form.handleKey(key)).isEqualTo(new Form.Event.None<>());
        }
    }

    private static void press(OptionsForm form, KeyCode code, int times) {
        for (int i = 0; i < times; i++) {
            press(form, key(code));
        }
    }

    private static void typeText(OptionsForm form, String text) {
        text.chars().forEach(c -> press(form, key((char) c)));
    }

    private static ParserSettings applied(OptionsForm form) {
        Form.Event<ParserSettings> event = form.handleKey(key(KeyCode.ENTER));
        assertThat(event).isInstanceOf(Form.Event.Apply.class);
        return ((Form.Event.Apply<ParserSettings>) event).draft();
    }

    @Test
    void guessesIntegerTypesForOneDimensionalPoints() {
        ParserSettings settings = settings();
        assertThat(settings.pointFields())
                .containsExactly(new PointField("quantity", PointType.INT), new PointField("price", PointType.LONG));
        assertThat(settings.pointsText()).isEqualTo("quantity INT · price LONG");
        assertThat(settings.changed(true)).isEmpty();
    }

    @Test
    void cyclesAPointTypeThroughItsWidth() {
        OptionsForm form = new OptionsForm(settings(), false);
        // Leading wildcard, slop, prefix, increments, then quantity and price.
        press(form, key(KeyCode.END), key(' '));
        assertThat(form.draft().points.get(1).pointType()).hasValue(PointType.DOUBLE);
        press(form, key(' '));
        assertThat(form.draft().points.get(1).pointType()).isEmpty();
        press(form, key(KeyCode.UP), key(KeyCode.LEFT));
        assertThat(form.draft().points.get(0).pointType()).isEmpty();
        ParserSettings settings = applied(form);
        assertThat(settings.pointFields()).isEmpty();
        assertThat(settings.pointsText()).isEqualTo("quantity text · price text");
    }

    @Test
    void editsNumbersWithinTheirRange() {
        OptionsForm form = new OptionsForm(settings(), true);
        // Phrase slop: digits append, Backspace drops the last one, ← stops at 0.
        press(form, key(KeyCode.DOWN), key('1'), key('2'), key('3'), key('4'), key(KeyCode.BACKSPACE));
        assertThat(form.draft().phraseSlop).isEqualTo(0);
        press(form, key('1'), key('2'));
        assertThat(form.draft().phraseSlop).isEqualTo(12);
        // Fuzzy max edits stays within 0 to 2.
        press(form, key(KeyCode.DOWN), key(KeyCode.RIGHT), key('7'));
        assertThat(form.draft().fuzzyMaxEdits).isEqualTo(2);
        press(form, key('1'), key(KeyCode.LEFT), key(KeyCode.LEFT));
        assertThat(form.draft().fuzzyMaxEdits).isEqualTo(0);
        ParserSettings settings = applied(form);
        assertThat(settings.changed(true)).containsExactly("phrase slop 12", "fuzzy max edits 0");
        // The standard parser has no fuzzy max edits.
        assertThat(settings.changed(false)).containsExactly("phrase slop 12");
    }

    @Test
    void autoPhrasesNeedWhitespaceSplitting() {
        OptionsForm form = new OptionsForm(settings(), true);
        // The last rows are the dates': resolution, locale and time zone.
        press(form, key(KeyCode.END), key(KeyCode.UP), key(KeyCode.UP), key(KeyCode.UP), key(' '));
        assertThat(form.draft().autoGeneratePhraseQueries).isTrue();
        assertThat(form.draft().splitOnWhitespace).isTrue();
        press(form, key(KeyCode.UP), key(' '));
        assertThat(form.draft().splitOnWhitespace).isFalse();
        assertThat(form.draft().autoGeneratePhraseQueries).isFalse();
    }

    @Test
    void cancellingKeepsNothing() {
        OptionsForm form = new OptionsForm(settings(), true);
        press(form, key(' '));
        assertThat(form.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new Form.Event.Cancel<>());
    }

    @Test
    void cyclesTheDateResolution() {
        OptionsForm form = new OptionsForm(settings(), true);
        // Leading wildcard, slop, fuzzy max edits, prefix, increments, split, auto phrases.
        press(form, KeyCode.DOWN, 7);
        press(form, key(KeyCode.RIGHT));
        assertThat(form.draft().dateResolution).hasValue(DateResolution.YEAR);
        press(form, key(KeyCode.LEFT), key(KeyCode.LEFT));
        assertThat(form.draft().dateResolution).hasValue(DateResolution.MILLISECOND);
        press(form, key(' '));
        assertThat(form.draft().dateResolution).isEmpty();
        press(form, key('+'), key('+'), key('+'));
        ParserSettings settings = applied(form);
        assertThat(settings.options().dateResolution()).hasValue(DateResolution.DAY);
        assertThat(settings.changed(true)).containsExactly("dates DAY");
        assertThat(settings.changed(false)).containsExactly("dates DAY");
    }

    @Test
    void typesTheLocaleAndTheTimeZone() {
        OptionsForm form = new OptionsForm(settings(), false);
        // Leading wildcard, slop, prefix, increments, resolution, then locale and time zone.
        press(form, KeyCode.DOWN, 5);
        press(form, key(' '));
        // While typing, letters are text, not keys of the popup.
        typeText(form, "de-DEx");
        press(form, key(KeyCode.BACKSPACE), key(KeyCode.ENTER));
        assertThat(form.draft().locale).hasValue("de-DE");
        press(form, key(KeyCode.DOWN), key(KeyCode.RIGHT));
        typeText(form, "  Europe/Berlin ");
        press(form, key(KeyCode.ENTER));
        assertThat(form.draft().timeZone).hasValue("Europe/Berlin");
        // Esc goes back to the popup without taking the text.
        press(form, key(KeyCode.RIGHT));
        typeText(form, "UTC");
        press(form, key(KeyCode.ESCAPE));
        assertThat(form.draft().timeZone).hasValue("Europe/Berlin");
        assertThat(applied(form).changed(false)).containsExactly("locale de-DE", "zone Europe/Berlin");
    }

    @Test
    void clearsTheLocaleAndTheTimeZone() {
        ParserSettings settings = settings();
        settings.locale = Optional.of("de-DE");
        settings.timeZone = Optional.of("UTC");
        OptionsForm form = new OptionsForm(settings, false);
        press(form, KeyCode.DOWN, 5);
        press(form, key('d'), key(KeyCode.DOWN));
        // A blank text is the backend's default too.
        press(form, key(KeyCode.RIGHT));
        assertThat(form.editedText()).hasValue("UTC");
        press(form, KeyCode.BACKSPACE, 3);
        typeText(form, "  ");
        press(form, key(KeyCode.ENTER));
        ParserSettings cleared = applied(form);
        assertThat(cleared.locale).isEmpty();
        assertThat(cleared.timeZone).isEmpty();
        assertThat(cleared.changed(false)).isEmpty();
    }
}
