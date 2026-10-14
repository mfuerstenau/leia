package io.github.mfuerstenau.leia.tui.search;

import static io.github.mfuerstenau.leia.tui.TestScreens.key;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.BooleanSimilarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.tui.search.ScoringForm.Row;
import io.github.mfuerstenau.leia.tui.search.ScoringSettings.SimilarityKind;
import io.github.mfuerstenau.leia.tui.search.ScoringSettings.Target;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The tests of {@code scoring.rs}, ported. */
class ScoringSettingsTest {

    private static IndexField field(String name, DocValuesType docValuesType) {
        return new IndexField(
                name,
                0,
                IndexOptions.NONE,
                false,
                false,
                false,
                docValuesType,
                0,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * {@code body} (no doc values), {@code checksum} (binary), {@code price} (numeric), {@code tags}
     * (sorted set): the sortable fields are {@code price} and {@code tags}.
     */
    private static ScoringSettings settings() {
        return ScoringSettings.of(List.of(
                field("body", DocValuesType.NONE),
                field("checksum", DocValuesType.BINARY),
                field("price", DocValuesType.NUMERIC),
                field("tags", DocValuesType.SORTED_SET)));
    }

    private static void press(ScoringForm form, KeyEvent... keys) {
        for (KeyEvent key : keys) {
            assertThat(form.handleKey(key)).isEqualTo(new Form.Event.None<>());
        }
    }

    private static KeyEvent k(KeyCode code) {
        return key(code);
    }

    private static ScoringSettings applied(ScoringForm form) {
        Form.Event<ScoringSettings> event = form.handleKey(key(KeyCode.ENTER));
        assertThat(event).isInstanceOf(Form.Event.Apply.class);
        return ((Form.Event.Apply<ScoringSettings>) event).draft();
    }

    private static SortKey fieldKey(FieldSort sort) {
        return new SortKey(Optional.of(sort), Optional.empty(), Optional.empty());
    }

    @Test
    void startsFromBm25ByScore() {
        ScoringSettings settings = settings();
        assertThat(settings.similarity())
                .isEqualTo(new Similarity(
                        Optional.of(new Bm25Similarity(1.2f, 0.75f)), Optional.empty(), Optional.empty()));
        assertThat(settings.sortKeys()).isEmpty();
        assertThat(settings.similarityText()).isEmpty();
        assertThat(settings.sortText()).isEmpty();
    }

    @Test
    void editsBm25InHundredths() {
        ScoringForm form = new ScoringForm(settings());
        // k1: 1.20 + 0.05, then typed as 0.9; b: typed 150 stays within 1.00.
        press(form, k(KeyCode.DOWN), k(KeyCode.RIGHT));
        assertThat(form.draft().k1).isEqualTo(125);
        press(form, key('9'), key('0'));
        assertThat(form.draft().k1).isEqualTo(90);
        press(form, k(KeyCode.DOWN), k(KeyCode.BACKSPACE), k(KeyCode.BACKSPACE), key('1'), key('5'), key('0'));
        assertThat(form.draft().b).isEqualTo(0);
        press(form, key('4'));
        ScoringSettings settings = applied(form);
        assertThat(settings.similarity().bm25()).hasValue(new Bm25Similarity(0.9f, 0.04f));
        assertThat(settings.similarityText()).hasValue("BM25 k1 0.90 b 0.04");
    }

    @Test
    void classicAndBooleanHaveNoParameters() {
        ScoringForm form = new ScoringForm(settings());
        press(form, key(' '));
        assertThat(form.rows().get(1)).isEqualTo(new Row.TargetRow(0));
        assertThat(form.draft().similarity().classic()).hasValue(new ClassicSimilarity());
        press(form, key(' '));
        assertThat(form.draft().similarity().booleanSimilarity()).hasValue(new BooleanSimilarity());
        press(form, key(' '));
        assertThat(form.draft().kind).isEqualTo(SimilarityKind.BM25);
        press(form, k(KeyCode.LEFT));
        assertThat(applied(form).similarityText()).hasValue("boolean");
    }

    @Test
    void sortsByUpToTwoFieldsWithGuessedTypes() {
        ScoringForm form = new ScoringForm(settings());
        // Similarity, k1, b, then "Sort by": score, doc, then price, guessed LONG, read as DOUBLE,
        // missing values last, descending.
        press(form, k(KeyCode.DOWN), k(KeyCode.DOWN), k(KeyCode.DOWN));
        assertThat(form.rows()).hasSize(4);
        press(form, k(KeyCode.RIGHT), k(KeyCode.RIGHT), k(KeyCode.RIGHT));
        // A single-valued number: no selector.
        assertThat(form.rows().subList(4, form.rows().size()))
                .containsExactly(new Row.Type(0), new Row.Missing(0), new Row.Order(0), new Row.TargetRow(1));
        press(
                form,
                k(KeyCode.DOWN),
                k(KeyCode.RIGHT),
                k(KeyCode.RIGHT),
                k(KeyCode.DOWN),
                k(KeyCode.LEFT),
                k(KeyCode.DOWN),
                key(' '));
        // "Then by": tags, a string, so its type doesn't show; by its largest value.
        press(form, k(KeyCode.DOWN), k(KeyCode.RIGHT), k(KeyCode.RIGHT), k(KeyCode.RIGHT), k(KeyCode.RIGHT));
        assertThat(form.rows().subList(8, form.rows().size()))
                .containsExactly(new Row.Selector(1), new Row.Missing(1), new Row.Order(1));
        press(form, k(KeyCode.DOWN), k(KeyCode.RIGHT));
        ScoringSettings settings = applied(form);
        assertThat(settings.sortKeys())
                .containsExactly(
                        fieldKey(new FieldSort("price", SortType.DOUBLE, true, SortSelector.MIN, SortMissing.LAST)),
                        fieldKey(new FieldSort("tags", SortType.STRING, false, SortSelector.MAX, SortMissing.DEFAULT)));
        assertThat(settings.sortText()).hasValue("price DOUBLE last ↓ · tags MAX ↑");
    }

    @Test
    void sortsByTheScoreAndTheDocumentId() {
        ScoringForm form = new ScoringForm(settings());
        // Sort by the score, worst first, then by the document id, descending.
        press(
                form,
                k(KeyCode.END),
                k(KeyCode.RIGHT),
                k(KeyCode.DOWN),
                key(' '),
                k(KeyCode.DOWN),
                k(KeyCode.RIGHT),
                k(KeyCode.RIGHT),
                k(KeyCode.DOWN),
                key(' '));
        ScoringSettings settings = applied(form);
        assertThat(settings.sortKeys())
                .containsExactly(
                        new SortKey(Optional.empty(), Optional.of(new ScoreSort(true)), Optional.empty()),
                        new SortKey(Optional.empty(), Optional.empty(), Optional.of(new DocSort(true))));
        assertThat(settings.sortText()).hasValue("score ↑ · doc ↓");
    }

    @Test
    void noFirstLevelClearsTheSecond() {
        ScoringForm form = new ScoringForm(settings());
        press(form, k(KeyCode.END), k(KeyCode.RIGHT));
        // Then by: tags.
        press(form, k(KeyCode.END), k(KeyCode.RIGHT), k(KeyCode.RIGHT));
        assertThat(form.draft().levels[1]).isNotNull();
        // Sort by: back to score.
        press(form, k(KeyCode.HOME), k(KeyCode.DOWN), k(KeyCode.DOWN), k(KeyCode.DOWN), k(KeyCode.LEFT));
        assertThat(Arrays.asList(form.draft().levels)).containsOnlyNulls();
        assertThat(applied(form).sortKeys()).isEmpty();
    }

    @Test
    void anIndexWithoutDocValuesSortsByScoreOrId() {
        ScoringForm form = new ScoringForm(ScoringSettings.of(List.of(field("body", DocValuesType.NONE))));
        assertThat(form.rows())
                .containsExactly(new Row.SimilarityRow(), new Row.K1(), new Row.B(), new Row.TargetRow(0));
        // The score, the document id, and back to nothing.
        press(form, k(KeyCode.END), k(KeyCode.RIGHT), k(KeyCode.RIGHT));
        assertThat(form.draft().levels[0].target()).isEqualTo(new Target.Doc());
        press(form, k(KeyCode.LEFT), k(KeyCode.LEFT));
        assertThat(form.draft().levels[0]).isNull();
    }
}
