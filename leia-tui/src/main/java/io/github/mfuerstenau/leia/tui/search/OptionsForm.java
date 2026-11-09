package io.github.mfuerstenau.leia.tui.search;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.tui.prompt.LineInput;
import io.github.mfuerstenau.leia.tui.search.ParserSettings.PointSetting;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import io.github.mfuerstenau.leia.tui.widgets.Panels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The parser options popup: a draft of the {@link ParserSettings}, applied with Enter. */
public final class OptionsForm {

    /** The popup's width. */
    private static final int WIDTH = 48;

    /** The largest slop or prefix length the popup takes. */
    private static final int MAX_NUMBER = 999;

    /**
     * One line of the popup; {@link #FIELD_DATE} is one per indexed field, then {@link #POINT} one per
     * point field.
     */
    private enum Row {
        LEADING_WILDCARD,
        PHRASE_SLOP,
        FUZZY_MAX_EDITS,
        FUZZY_PREFIX_LENGTH,
        POSITION_INCREMENTS,
        SPLIT_ON_WHITESPACE,
        AUTO_PHRASES,
        DATE_RESOLUTION,
        LOCALE,
        TIME_ZONE,
        FIELD_DATE,
        POINT
    }

    private static final List<Row> CLASSIC_ROWS = List.of(
            Row.LEADING_WILDCARD,
            Row.PHRASE_SLOP,
            Row.FUZZY_MAX_EDITS,
            Row.FUZZY_PREFIX_LENGTH,
            Row.POSITION_INCREMENTS,
            Row.SPLIT_ON_WHITESPACE,
            Row.AUTO_PHRASES,
            Row.DATE_RESOLUTION,
            Row.LOCALE,
            Row.TIME_ZONE);

    private static final List<Row> STANDARD_ROWS = List.of(
            Row.LEADING_WILDCARD,
            Row.PHRASE_SLOP,
            Row.FUZZY_PREFIX_LENGTH,
            Row.POSITION_INCREMENTS,
            Row.DATE_RESOLUTION,
            Row.LOCALE,
            Row.TIME_ZONE);

    private final ParserSettings draft;

    /** Whether the rows are the classic parser's, not the standard parser's. */
    private final boolean classic;

    private int selected;

    /** The row whose text is typed, or null. */
    private @Nullable Row editingRow;

    private @Nullable LineInput input;

    public OptionsForm(ParserSettings settings, boolean classic) {
        this.draft = settings.copy();
        this.classic = classic;
    }

    /** The draft, for the tests. */
    ParserSettings draft() {
        return draft;
    }

    /** The text that is typed, for the tests. */
    Optional<String> editedText() {
        return editingRow == null
                ? Optional.empty()
                : Optional.of(Objects.requireNonNull(input).text());
    }

    /** The rows before the field rows. */
    private List<Row> fixedRows() {
        return classic ? CLASSIC_ROWS : STANDARD_ROWS;
    }

    /** The index of the first point row; the standard parser's only. */
    private int firstPoint() {
        return fixedRows().size() + draft.dateFields.size();
    }

    private int rowCount() {
        return firstPoint() + (classic ? 0 : draft.points.size());
    }

    private Row row(int index) {
        if (index < fixedRows().size()) {
            return fixedRows().get(index);
        }
        return index < firstPoint() ? Row.FIELD_DATE : Row.POINT;
    }

    /** The field of a {@link Row#FIELD_DATE} row. */
    private String dateField(int index) {
        return draft.dateFields.get(index - fixedRows().size());
    }

    /**
     * ↑↓ select a row; ←→, Space, {@code -}/{@code +} change it; digits and Backspace edit a number;
     * →, Space or {@code +} types a locale or time zone, {@code d} or Backspace clears it or a
     * field's own date resolution; Enter
     * applies, Esc cancels.
     */
    public Form.Event<ParserSettings> handleKey(KeyEvent key) {
        if (editingRow != null) {
            textKey(key);
            return new Form.Event.None<>();
        }
        int last = rowCount() - 1;
        int index = Math.min(selected, last);
        Row row = row(index);
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
            case LEFT -> change(row, index, -1);
            case RIGHT -> change(row, index, 1);
            case BACKSPACE -> {
                if (isNumber(row)) {
                    setNumber(row, number(row) / 10);
                } else {
                    clear(row, index);
                }
            }
            case DELETE -> clear(row, index);
            case CHAR -> {
                int c = key.codePoint();
                switch (c) {
                    case 'k' -> selected = Math.max(selected - 1, 0);
                    case 'j' -> selected = Math.min(selected + 1, last);
                    case 'g' -> selected = 0;
                    case 'G' -> selected = last;
                    case 'h', '-' -> change(row, index, -1);
                    case 'l', '+', ' ' -> change(row, index, 1);
                    case 'd' -> clear(row, index);
                    default -> {
                        if (c >= '0' && c <= '9' && isNumber(row)) {
                            setNumber(row, Form.typeDigit(number(row), (char) c, max(row)));
                        }
                    }
                }
            }
            default -> {}
        }
        return new Form.Event.None<>();
    }

    /** Enter takes the text, blank for the backend's default; Esc goes back; any other key edits it. */
    private void textKey(KeyEvent key) {
        switch (key.code()) {
            case ESCAPE -> editingRow = null;
            case ENTER -> {
                String typed = Objects.requireNonNull(input).text().strip();
                setText(Objects.requireNonNull(editingRow), typed.isEmpty() ? null : typed);
                editingRow = null;
            }
            default -> Objects.requireNonNull(input).handleKey(key, LineInput::wordStart);
        }
    }

    private static boolean isText(Row row) {
        return row == Row.LOCALE || row == Row.TIME_ZONE;
    }

    private @Nullable String text(Row row) {
        return row == Row.LOCALE ? draft.locale : draft.timeZone;
    }

    private void setText(Row row, @Nullable String text) {
        if (row == Row.LOCALE) {
            draft.locale = text;
        } else {
            draft.timeZone = text;
        }
    }

    /** Clears a locale or time zone back to the backend's default, or a field's date resolution back to the one for every field. */
    private void clear(Row row, int index) {
        if (isText(row)) {
            setText(row, null);
        } else if (row == Row.FIELD_DATE) {
            draft.fieldDateResolutions.remove(dateField(index));
        }
    }

    private static boolean isNumber(Row row) {
        return row == Row.PHRASE_SLOP || row == Row.FUZZY_MAX_EDITS || row == Row.FUZZY_PREFIX_LENGTH;
    }

    private int number(Row row) {
        return switch (row) {
            case PHRASE_SLOP -> draft.phraseSlop;
            case FUZZY_MAX_EDITS -> draft.fuzzyMaxEdits;
            default -> draft.fuzzyPrefixLength;
        };
    }

    private static int max(Row row) {
        return row == Row.FUZZY_MAX_EDITS ? ParserSettings.MAX_FUZZY_EDITS : MAX_NUMBER;
    }

    private void setNumber(Row row, int value) {
        switch (row) {
            case PHRASE_SLOP -> draft.phraseSlop = value;
            case FUZZY_MAX_EDITS -> draft.fuzzyMaxEdits = value;
            default -> draft.fuzzyPrefixLength = value;
        }
    }

    /** Steps a number by {@code step}, flips a switch, cycles the date resolution or a point type, or starts typing a text forward. */
    private void change(Row row, int index, int step) {
        if (isNumber(row)) {
            setNumber(row, Math.clamp((long) number(row) + step, 0, max(row)));
            return;
        }
        if (isText(row)) {
            if (step > 0) {
                editingRow = row;
                input = new LineInput(Objects.requireNonNullElse(text(row), ""));
            }
            return;
        }
        switch (row) {
            case LEADING_WILDCARD -> draft.allowLeadingWildcard = !draft.allowLeadingWildcard;
            case POSITION_INCREMENTS -> draft.enablePositionIncrements = !draft.enablePositionIncrements;
            // Lucene generates phrases only while it splits on whitespace.
            case SPLIT_ON_WHITESPACE -> {
                draft.splitOnWhitespace = !draft.splitOnWhitespace;
                draft.autoGeneratePhraseQueries &= draft.splitOnWhitespace;
            }
            case AUTO_PHRASES -> {
                draft.autoGeneratePhraseQueries = !draft.autoGeneratePhraseQueries;
                draft.splitOnWhitespace |= draft.autoGeneratePhraseQueries;
            }
            case DATE_RESOLUTION -> draft.dateResolution = cycled(resolutions(), draft.dateResolution, step);
            case FIELD_DATE -> {
                String field = dateField(index);
                @Nullable
                DateResolution resolution = cycled(resolutions(), draft.fieldDateResolutions.get(field), step);
                if (resolution == null) {
                    draft.fieldDateResolutions.remove(field);
                } else {
                    draft.fieldDateResolutions.put(field, resolution);
                }
            }
            case POINT -> {
                int point = index - firstPoint();
                PointSetting setting = draft.points.get(point);
                List<@Nullable PointType> cycle = setting.bytes() == 4
                        ? Arrays.asList(PointType.INT, PointType.FLOAT, null)
                        : Arrays.asList(PointType.LONG, PointType.DOUBLE, null);
                @Nullable PointType type = cycled(cycle, setting.pointType(), step);
                draft.points.set(point, new PointSetting(setting.field(), setting.bytes(), type));
            }
            default -> {}
        }
    }

    /** No resolution, then each from the coarsest. */
    private static List<@Nullable DateResolution> resolutions() {
        List<@Nullable DateResolution> cycle = new ArrayList<>();
        cycle.add(null);
        cycle.addAll(List.of(DateResolution.values()));
        return cycle;
    }

    /** The entry after (or before) {@code current} in {@code cycle}, wrapping around. */
    private static <T extends @Nullable Object> T cycled(List<T> cycle, T current, int step) {
        int at = Math.max(cycle.indexOf(current), 0);
        return cycle.get(Math.floorMod(at + step, cycle.size()));
    }

    /** The keys for the footer while the popup is open. */
    public String keys() {
        return editingRow != null
                ? " ⏎ take   Esc back   ←→ Ctrl+A/E/U/K/W edit"
                : " ⏎ apply   Esc cancel   ↑↓ select   ←→ Space change   0-9 number   d default";
    }

    public void render(Frame frame, Rect area) {
        int count = rowCount();
        int shown = Math.min(selected, count - 1);
        List<Line> lines = new ArrayList<>();
        int selectedLine = 0;
        for (int i = 0; i < count; i++) {
            Row row = row(i);
            addHeading(lines, row, i);
            String label;
            String value;
            switch (row) {
                case LEADING_WILDCARD -> {
                    label = "Leading wildcard";
                    value = onOff(draft.allowLeadingWildcard);
                }
                case PHRASE_SLOP -> {
                    label = "Phrase slop";
                    value = Integer.toString(draft.phraseSlop);
                }
                case FUZZY_MAX_EDITS -> {
                    label = "Fuzzy max edits";
                    value = Integer.toString(draft.fuzzyMaxEdits);
                }
                case FUZZY_PREFIX_LENGTH -> {
                    label = "Fuzzy prefix length";
                    value = Integer.toString(draft.fuzzyPrefixLength);
                }
                case POSITION_INCREMENTS -> {
                    label = "Position increments";
                    value = onOff(draft.enablePositionIncrements);
                }
                case SPLIT_ON_WHITESPACE -> {
                    label = "Split on whitespace";
                    value = onOff(draft.splitOnWhitespace);
                }
                case AUTO_PHRASES -> {
                    label = "Auto phrase queries";
                    value = onOff(draft.autoGeneratePhraseQueries);
                }
                case DATE_RESOLUTION -> {
                    label = "Resolution";
                    value = draft.dateResolution == null ? "none: text" : draft.dateResolution.name();
                }
                case LOCALE -> {
                    label = "Locale";
                    value = textValue(draft.locale);
                }
                case TIME_ZONE -> {
                    label = "Time zone";
                    value = textValue(draft.timeZone);
                }
                case FIELD_DATE -> {
                    String field = dateField(i);
                    label = TextFormat.escapeControl(field);
                    value = Optional.ofNullable(draft.fieldDateResolutions.get(field))
                            .map(Enum::name)
                            .orElse("default");
                }
                case POINT -> {
                    PointSetting point = draft.points.get(i - firstPoint());
                    label = TextFormat.escapeControl(point.field());
                    value = ParserSettings.typeName(point.pointType());
                }
                default -> throw new IllegalStateException(row.name());
            }
            if (i == shown) {
                selectedLine = lines.size();
            }
            lines.add(Form.row(label, value, i == shown));
        }
        if (!classic && draft.points.isEmpty()) {
            lines.add(Line.empty());
            lines.add(Line.styled("No one-dimensional point fields", Form.muted()));
        }
        String title = classic ? " Classic parser options " : " Standard parser options ";
        Form.renderPopup(frame, area, title, lines, selectedLine, WIDTH);
        if (editingRow != null) {
            renderText(frame, area);
        }
    }

    /** The heading of the group that row {@code i} starts, if it starts one. */
    private void addHeading(List<Line> lines, Row row, int i) {
        addHeading(lines, row == Row.DATE_RESOLUTION, "Range bounds as dates");
        addHeading(
                lines, i == fixedRows().size() && !draft.dateFields.isEmpty(), "Per field, over the resolution above");
        addHeading(lines, !classic && i == firstPoint(), "Point types, guessed: the index doesn't say");
    }

    private static void addHeading(List<Line> lines, boolean starts, String heading) {
        if (starts) {
            lines.add(Line.empty());
            lines.add(Line.styled(heading, Form.muted()));
        }
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private static String textValue(@Nullable String text) {
        return text == null ? "server default" : TextFormat.escapeControl(text);
    }

    /** The line that the locale or the time zone is typed in, over the popup. */
    private void renderText(Frame frame, Rect area) {
        String title = editingRow == Row.LOCALE
                ? " Locale, such as en-US or de-DE (blank for the default) "
                : " Time zone, such as UTC or Europe/Berlin (blank for the default) ";
        int width = Math.min(area.width(), WIDTH + 20);
        int height = Math.min(3, area.height());
        Rect popup =
                new Rect(area.x() + (area.width() - width) / 2, area.y() + (area.height() - height) / 2, width, height);
        Block block = Panels.box(title);
        Rect inner = block.inner(popup);
        Panels.clear(frame, popup);
        frame.renderWidget(block, popup);
        LineInput.Visible visible = Objects.requireNonNull(input).visible(inner.width());
        frame.renderWidget(Paragraph.from(Line.from(visible.text())), inner);
        frame.setCursorPosition(inner.x() + visible.cursorColumn(), inner.y());
    }
}
