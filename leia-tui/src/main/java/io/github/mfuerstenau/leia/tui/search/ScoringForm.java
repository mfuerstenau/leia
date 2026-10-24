package io.github.mfuerstenau.leia.tui.search;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.tui.search.ScoringSettings.SimilarityKind;
import io.github.mfuerstenau.leia.tui.search.ScoringSettings.SortLevel;
import io.github.mfuerstenau.leia.tui.search.ScoringSettings.SortableField;
import io.github.mfuerstenau.leia.tui.search.ScoringSettings.Target;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The similarity and sort popup: a draft of the {@link ScoringSettings}, applied with Enter. */
public final class ScoringForm {

    /** The largest {@code k1}, in hundredths, that the popup takes. */
    private static final int MAX_K1 = 999;

    /** The largest {@code b}, in hundredths. */
    private static final int MAX_B = 100;

    /** One line of the popup. */
    sealed interface Row {
        record SimilarityRow() implements Row {}

        record K1() implements Row {}

        record B() implements Row {}

        /** What a level sorts by. */
        record TargetRow(int level) implements Row {}

        record Type(int level) implements Row {}

        record Selector(int level) implements Row {}

        record Missing(int level) implements Row {}

        record Order(int level) implements Row {}
    }

    private final ScoringSettings draft;
    private int selected;

    public ScoringForm(ScoringSettings settings) {
        this.draft = settings.copy();
    }

    /** The draft, for the tests. */
    ScoringSettings draft() {
        return draft;
    }

    /**
     * {@code k1} and {@code b} for BM25 only; a level's order once it sorts by something, its type and
     * missing values for a field, its selector for a multi-valued field, and the second level once the
     * first sorts by something.
     */
    List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row.SimilarityRow());
        if (draft.kind == SimilarityKind.BM25) {
            rows.add(new Row.K1());
            rows.add(new Row.B());
        }
        for (int level = 0; level < ScoringSettings.MAX_LEVELS; level++) {
            rows.add(new Row.TargetRow(level));
            SortLevel sort = draft.levels[level];
            if (sort == null) {
                break;
            }
            if (sort.target() instanceof Target.Field field) {
                SortableField sortable = draft.fields.get(field.index());
                if (sortable.numeric()) {
                    rows.add(new Row.Type(level));
                }
                if (sortable.selectors().size() > 1) {
                    rows.add(new Row.Selector(level));
                }
                rows.add(new Row.Missing(level));
            }
            rows.add(new Row.Order(level));
        }
        return rows;
    }

    /** ↑↓ select a row; ←→, Space, {@code -}/{@code +} change it; digits and Backspace edit a number; Enter applies, Esc cancels. */
    public Form.Event<ScoringSettings> handleKey(KeyEvent key) {
        List<Row> rows = rows();
        int last = rows.size() - 1;
        Row row = rows.get(Math.min(selected, last));
        switch (key.code()) {
            case ENTER -> {
                return new Form.Event.Apply<>(draft.copy());
            }
            case ESCAPE -> {
                return new Form.Event.Cancel<>();
            }
            case UP -> selected = Math.max(selected - 1, 0);
            case DOWN -> selected = Math.min(selected + 1, last);
            case HOME -> selected = 0;
            case END -> selected = last;
            case LEFT -> change(row, -1);
            case RIGHT -> change(row, 1);
            case BACKSPACE -> {
                if (row instanceof Row.K1) {
                    draft.k1 /= 10;
                } else if (row instanceof Row.B) {
                    draft.b /= 10;
                }
            }
            case CHAR -> {
                int c = key.codePoint();
                switch (c) {
                    case 'k' -> selected = Math.max(selected - 1, 0);
                    case 'j' -> selected = Math.min(selected + 1, last);
                    case 'g' -> selected = 0;
                    case 'G' -> selected = last;
                    case 'h', '-' -> change(row, -1);
                    case 'l', '+', ' ' -> change(row, 1);
                    default -> {
                        if (c >= '0' && c <= '9') {
                            if (row instanceof Row.K1) {
                                draft.k1 = Form.typeDigit(draft.k1, (char) c, MAX_K1);
                            } else if (row instanceof Row.B) {
                                draft.b = Form.typeDigit(draft.b, (char) c, MAX_B);
                            }
                        }
                    }
                }
            }
            default -> {}
        }
        return new Form.Event.None<>();
    }

    /** Steps a number by {@code step} hundredths times 5, or cycles a choice. */
    private void change(Row row, int step) {
        switch (row) {
            case Row.K1 _ -> draft.k1 = Math.clamp(draft.k1 + 5L * step, 0, MAX_K1);
            case Row.B _ -> draft.b = Math.clamp(draft.b + 5L * step, 0, MAX_B);
            case Row.SimilarityRow _ -> draft.kind = cycled(List.of(SimilarityKind.values()), draft.kind, step);
            case Row.TargetRow(int level) -> changeTarget(level, step);
            case Row.Type(int level) -> changeType(level, step);
            case Row.Selector(int level) -> {
                SortLevel sort = draft.levels[level];
                if (sort != null && sort.target() instanceof Target.Field field) {
                    SortSelector selector =
                            cycled(draft.fields.get(field.index()).selectors(), sort.selector(), step);
                    draft.levels[level] =
                            new SortLevel(sort.target(), sort.sortType(), sort.reverse(), selector, sort.missing());
                }
            }
            case Row.Missing(int level) -> {
                SortLevel sort = draft.levels[level];
                if (sort != null) {
                    SortMissing missing = cycled(List.of(SortMissing.values()), sort.missing(), step);
                    draft.levels[level] =
                            new SortLevel(sort.target(), sort.sortType(), sort.reverse(), sort.selector(), missing);
                }
            }
            case Row.Order(int level) -> {
                SortLevel sort = draft.levels[level];
                if (sort != null) {
                    draft.levels[level] = new SortLevel(
                            sort.target(), sort.sortType(), !sort.reverse(), sort.selector(), sort.missing());
                }
            }
        }
    }

    /**
     * Nothing, the score, the document id, then each sortable field; a new field starts from its
     * guessed type, its smallest value and Lucene's missing values.
     */
    private void changeTarget(int level, int step) {
        List<Target> choices = new ArrayList<>(Arrays.asList(null, new Target.Score(), new Target.Doc()));
        for (int field = 0; field < draft.fields.size(); field++) {
            choices.add(new Target.Field(field));
        }
        SortLevel current = draft.levels[level];
        Target target = cycled(choices, current == null ? null : current.target(), step);
        if (target == null) {
            Arrays.fill(draft.levels, level, ScoringSettings.MAX_LEVELS, null);
            return;
        }
        SortType type = target instanceof Target.Field field
                        && !draft.fields.get(field.index()).numeric()
                ? SortType.STRING
                : SortType.LONG;
        draft.levels[level] = new SortLevel(
                target, type, current != null && current.reverse(), SortSelector.MIN, SortMissing.DEFAULT);
    }

    /** The type a field of numbers is read as. */
    private void changeType(int level, int step) {
        SortLevel sort = draft.levels[level];
        if (sort != null
                && sort.target() instanceof Target.Field field
                && draft.fields.get(field.index()).numeric()) {
            SortType type = cycled(
                    List.of(SortType.INT, SortType.LONG, SortType.FLOAT, SortType.DOUBLE), sort.sortType(), step);
            draft.levels[level] = new SortLevel(sort.target(), type, sort.reverse(), sort.selector(), sort.missing());
        }
    }

    /** The choice {@code step} places after {@code current} in {@code choices}, wrapping around. */
    private static <T> T cycled(List<T> choices, T current, int step) {
        int at = Math.max(choices.indexOf(current), 0);
        return choices.get(Math.floorMod(at + step, choices.size()));
    }

    /** The keys for the footer while the popup is open. */
    // Matches the keys() of the other forms (ADR 0088)
    @SuppressWarnings("java:S3400")
    public static String keys() {
        return " ⏎ apply   Esc cancel   ↑↓ select   ←→ Space change   0-9 number";
    }

    public void render(Frame frame, Rect area) {
        List<Row> rows = rows();
        int shown = Math.min(selected, rows.size() - 1);
        List<Line> lines = new ArrayList<>();
        int selectedLine = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.equals(new Row.TargetRow(0))) {
                lines.add(Line.empty());
            }
            if (i == shown) {
                selectedLine = lines.size();
            }
            lines.add(Form.row(label(row), value(row), i == shown));
        }
        if (draft.fields.isEmpty()) {
            lines.add(Line.empty());
            lines.add(Line.styled("No field has doc values: score or document id only", Form.dim()));
        } else if (sortsByNumbers()) {
            lines.add(Line.empty());
            lines.add(Line.styled("Number types, guessed: the index doesn't say", Form.dim()));
        }
        Form.renderPopup(frame, area, " Similarity and sort ", lines, selectedLine, 52);
    }

    private static String label(Row row) {
        return switch (row) {
            case Row.SimilarityRow _ -> "Similarity";
            case Row.K1 _ -> "  k1";
            case Row.B _ -> "  b";
            case Row.TargetRow(int level) -> level == 0 ? "Sort by" : "Then by";
            case Row.Type _ -> "  read as";
            case Row.Selector _ -> "  value";
            case Row.Missing _ -> "  missing";
            case Row.Order _ -> "  order";
        };
    }

    private String value(Row row) {
        return switch (row) {
            case Row.SimilarityRow _ ->
                switch (draft.kind) {
                    case BM25 -> "BM25";
                    case CLASSIC -> "classic TF-IDF";
                    case BOOLEAN -> "boolean";
                };
            case Row.K1 _ -> ScoringSettings.hundredths(draft.k1);
            case Row.B _ -> ScoringSettings.hundredths(draft.b);
            case Row.TargetRow(int level) -> targetValue(level);
            case Row.Type(int level) ->
                draft.levels[level] == null
                        ? ""
                        : draft.levels[level].sortType().name();
            case Row.Selector(int level) ->
                draft.levels[level] == null
                        ? ""
                        : draft.levels[level].selector().name();
            case Row.Missing(int level) -> missingValue(level);
            case Row.Order(int level) -> orderValue(level);
        };
    }

    private String targetValue(int level) {
        SortLevel sort = draft.levels[level];
        return switch (sort == null ? null : sort.target()) {
            case null -> level == 0 ? "best score (default)" : "nothing";
            case Target.Score _ -> "score";
            case Target.Doc _ -> "document id";
            case Target.Field field ->
                TextFormat.escapeControl(draft.fields.get(field.index()).name());
        };
    }

    private String missingValue(int level) {
        SortMissing missing = draft.levels[level] == null ? SortMissing.DEFAULT : draft.levels[level].missing();
        return switch (missing) {
            case FIRST -> "first";
            case LAST -> "last";
            case DEFAULT -> "Lucene's default";
        };
    }

    private String orderValue(int level) {
        SortLevel sort = draft.levels[level];
        if (sort != null && sort.target() instanceof Target.Score) {
            return sort.reverse() ? "worst first" : "best first";
        }
        return sort != null && sort.reverse() ? "descending" : "ascending";
    }

    /** Whether a level sorts by a field of numbers, whose type the index doesn't say. */
    private boolean sortsByNumbers() {
        return Arrays.stream(draft.levels)
                .anyMatch(sort -> sort != null
                        && sort.target() instanceof Target.Field field
                        && draft.fields.get(field.index()).numeric());
    }
}
