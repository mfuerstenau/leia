package io.github.mfuerstenau.leia.tui.search;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The tests of {@code hit_fields.rs}, ported. */
class HitFieldsTest {

    private static HitFields fields(String... names) {
        return new HitFields(List.of(names), Collections.nCopies(names.length, true));
    }

    private static HitFields applied(FieldsForm form) {
        Form.Event<HitFields> event = form.handleKey(key(KeyCode.ENTER));
        assertThat(event).isInstanceOf(Form.Event.Apply.class);
        return ((Form.Event.Apply<HitFields>) event).draft();
    }

    @Test
    void everyFieldIsLoadedUntilOneIsHidden() {
        HitFields all = fields("id", "title", "body", "price", "tags");
        assertThat(all.param()).isEmpty();
        assertThat(all.text()).isEmpty();

        FieldsForm form = new FieldsForm(all);
        form.handleKey(key(KeyCode.DOWN));
        form.handleKey(key(KeyCode.DOWN));
        form.handleKey(key(' '));
        HitFields chosen = applied(form);
        assertThat(chosen.param()).hasValue(List.of("id", "title", "price", "tags"));
        assertThat(chosen.text()).hasValue("4 of 5");
    }

    @Test
    void noneAndAll() {
        FieldsForm form = new FieldsForm(fields("id", "title", "body"));
        form.handleKey(key('n'));
        form.handleKey(key(KeyCode.END));
        form.handleKey(key(' '));
        HitFields one = applied(form);
        assertThat(one.param()).hasValue(List.of("body"));
        assertThat(one.text()).hasValue("body");

        form = new FieldsForm(one);
        form.handleKey(key('n'));
        assertThat(form.handleKey(key(KeyCode.ESCAPE))).isEqualTo(new Form.Event.Cancel<>());
        form.handleKey(key('n'));
        HitFields none = applied(form);
        assertThat(none.param()).hasValue(List.of());
        assertThat(none.text()).hasValue("none");
        form = new FieldsForm(none);
        form.handleKey(key('a'));
        assertThat(applied(form).param()).isEmpty();
    }

    @Test
    void offersOnlyTheStoredFieldsOnceTheyAreKnown() {
        HitFields all = new HitFields(List.of("id", "title", "body", "tags"), List.of(true, false, true, true));

        HitFields stored = all.storedOnly(List.of("body", "id", "title"));

        assertThat(stored.names()).containsExactly("id", "title", "body");
        assertThat(stored.shown()).containsExactly(true, false, true);
        assertThat(stored.param()).hasValue(List.of("id", "body"));
        assertThat(fields("id", "body").storedOnly(List.of("id", "body")).param())
                .isEmpty();
    }
}
