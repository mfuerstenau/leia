package io.github.mfuerstenau.leia.tui.search;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.tui.prompt.LineInput;
import io.github.mfuerstenau.leia.tui.search.ParserSettings.PointSetting;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** The parser options popup: a draft of the {@link ParserSettings}, applied with Enter. */
public final class OptionsForm {

    /** The popup's width. */
    private static final int WIDTH = 48;

    /** The largest slop or prefix length the popup takes. */
    private static final int MAX_NUMBER = 999;

    /** One line of the popup; {@link #POINT} and beyond are the point fields. */
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
    private Row editingRow;

    private LineInput input;

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
        return editingRow == null ? Optional.empty() : Optional.of(input.text());
    }

    private int rowCount() {
        return classic ? CLASSIC_ROWS.size() : STANDARD_ROWS.size() + draft.points.size();
    }

    private Row row(int index) {
        if (classic) {
            return CLASSIC_ROWS.get(index);
        }
        return index < STANDARD_ROWS.size() ? STANDARD_ROWS.get(index) : Row.POINT;
    }

    /**
     * ↑↓ select a row; ←→, Space, {@code -}/{@code +} change it; digits and Backspace edit a number;
     * →, Space or {@code +} types a locale or time zone, {@code d} or Backspace clears it; Enter
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
                    clearText(row);
                }
            }
            case DELETE -> clearText(row);
            case CHAR -> {
                int c = key.codePoint();
                switch (c) {
                    case 'k' -> selected = Math.max(selected - 1, 0);
                    case 'j' -> selected = Math.min(selected + 1, last);
                    case 'g' -> selected = 0;
                    case 'G' -> selected = last;
                    case 'h', '-' -> change(row, index, -1);
                    case 'l', '+', ' ' -> change(row, index, 1);
                    case 'd' -> clearText(row);
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
                String typed = input.text().strip();
                setText(editingRow, typed.isEmpty() ? Optional.empty() : Optional.of(typed));
                editingRow = null;
            }
            default -> input.handleKey(key, LineInput::wordStart);
        }
    }

    private static boolean isText(Row row) {
        return row == Row.LOCALE || row == Row.TIME_ZONE;
    }

    private Optional<String> text(Row row) {
        return row == Row.LOCALE ? draft.locale : draft.timeZone;
    }

    private void setText(Row row, Optional<String> text) {
        if (row == Row.LOCALE) {
            draft.locale = text;
        } else {
            draft.timeZone = text;
        }
    }

    private void clearText(Row row) {
        if (isText(row)) {
            setText(row, Optional.empty());
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
                input = new LineInput(text(row).orElse(""));
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
            case DATE_RESOLUTION -> {
                List<DateResolution> cycle = new ArrayList<>();
                cycle.add(null);
                cycle.addAll(List.of(DateResolution.values()));
                draft.dateResolution = Optional.ofNullable(cycled(cycle, draft.dateResolution.orElse(null), step));
            }
            case POINT -> {
                int point = index - STANDARD_ROWS.size();
                PointSetting setting = draft.points.get(point);
                List<PointType> cycle = setting.bytes() == 4
                        ? Arrays.asList(PointType.INT, PointType.FLOAT, null)
                        : Arrays.asList(PointType.LONG, PointType.DOUBLE, null);
                PointType type = cycled(cycle, setting.pointType().orElse(null), step);
                draft.points.set(point, new PointSetting(setting.field(), setting.bytes(), Optional.ofNullable(type)));
            }
            default -> {}
        }
    }

    /** The entry after (or before) {@code current} in {@code cycle}, wrapping around. */
    private static <T> T cycled(List<T> cycle, T current, int step) {
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
            if (row == Row.DATE_RESOLUTION) {
                lines.add(Line.empty());
                lines.add(Line.styled("Range bounds as dates", Form.dim()));
            }
            if (!classic && i == STANDARD_ROWS.size()) {
                lines.add(Line.empty());
                lines.add(Line.styled("Point types, guessed: the index doesn't say", Form.dim()));
            }
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
                    value = draft.dateResolution.map(Enum::name).orElse("none: text");
                }
                case LOCALE -> {
                    label = "Locale";
                    value = textValue(draft.locale);
                }
                case TIME_ZONE -> {
                    label = "Time zone";
                    value = textValue(draft.timeZone);
                }
                case POINT -> {
                    PointSetting point = draft.points.get(i - STANDARD_ROWS.size());
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
            lines.add(Line.styled("No one-dimensional point fields", Form.dim()));
        }
        String title = classic ? " Classic parser options " : " Standard parser options ";
        Form.renderPopup(frame, area, title, lines, selectedLine, WIDTH);
        if (editingRow != null) {
            renderText(frame, area);
        }
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private static String textValue(Optional<String> text) {
        return text.map(TextFormat::escapeControl).orElse("server default");
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
        Block block = Block.builder().borders(Borders.ALL).title(title).build();
        Rect inner = block.inner(popup);
        frame.renderWidget(Clear.INSTANCE, popup);
        frame.renderWidget(block, popup);
        LineInput.Visible visible = input.visible(inner.width());
        frame.renderWidget(Paragraph.from(Line.from(visible.text())), inner);
        frame.setCursorPosition(inner.x() + visible.cursorColumn(), inner.y());
    }
}
