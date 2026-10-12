package io.github.mfuerstenau.leia.tui.search;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.tui.prompt.LineInput;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The More-Like-This popup: a draft of the {@link LikeSettings}, applied with Enter once a field is chosen. */
public final class LikeForm {

    /** The largest frequency the popup takes. */
    private static final int MAX_FREQ = 99_999_999;

    /** The longest word length the popup takes. */
    private static final int MAX_WORD_LEN = 9_999;

    /** The popup's width. */
    private static final int WIDTH = 52;

    /** One line of the popup; {@link #FIELD} and beyond are the fields. */
    private enum Row {
        MIN_TERM_FREQ,
        MIN_DOC_FREQ,
        MAX_DOC_FREQ,
        MAX_QUERY_TERMS,
        BOOST,
        MIN_WORD_LEN,
        MAX_WORD_LEN,
        STOP_WORDS,
        FIELD
    }

    private final LikeSettings draft;
    private int selected;

    /** Whether Enter was refused because no field is chosen. */
    private boolean noField;

    /** The stop words, while they are typed; null otherwise. */
    private LineInput editing;

    public LikeForm(LikeSettings settings) {
        this.draft = settings.copy();
    }

    /** The draft, for the tests. */
    LikeSettings draft() {
        return draft;
    }

    boolean noField() {
        return noField;
    }

    boolean editing() {
        return editing != null;
    }

    private int rowCount() {
        return Row.FIELD.ordinal() + draft.names.size();
    }

    private Row row(int index) {
        return Row.values()[Math.min(index, Row.FIELD.ordinal())];
    }

    /**
     * ↑↓ select a row; ←→, Space, {@code -}/{@code +} change it; digits and Backspace edit a number;
     * →, Space or {@code +} types the stop words, {@code d} or Backspace clears them; {@code a}/{@code
     * n} choose all fields or none; Enter applies, Esc cancels.
     */
    public Form.Event<LikeSettings> handleKey(KeyEvent key) {
        if (editing != null) {
            switch (key.code()) {
                case ESCAPE -> editing = null;
                case ENTER -> {
                    draft.stopWords = LikeSettings.splitStopWords(editing.text());
                    editing = null;
                }
                default -> editing.handleKey(key, LineInput::wordStart);
            }
            return new Form.Event.None<>();
        }
        int last = rowCount() - 1;
        int index = Math.min(selected, last);
        Row row = row(index);
        switch (key.code()) {
            case ENTER -> {
                if (!draft.chosen.contains(true)) {
                    noField = true;
                    return new Form.Event.None<>();
                }
                LikeSettings settings = draft.copy();
                settings.maxQueryTerms = Math.max(settings.maxQueryTerms, 1);
                if (settings.maxWordLen > 0) {
                    settings.maxWordLen = Math.max(settings.maxWordLen, settings.minWordLen);
                }
                return new Form.Event.Apply<>(settings);
            }
            case ESCAPE -> {
                return new Form.Event.Cancel<>();
            }
            case UP -> selected = Math.max(selected - 1, 0);
            case DOWN -> selected = Math.min(selected + 1, last);
            case PAGE_UP -> selected = Math.max(selected - 10, 0);
            case PAGE_DOWN -> selected = Math.min(selected + 10, last);
            case HOME -> selected = 0;
            case END -> selected = last;
            case LEFT -> change(row, index, -1);
            case RIGHT -> change(row, index, 1);
            case BACKSPACE, DELETE -> {
                if (row == Row.STOP_WORDS) {
                    draft.stopWords = List.of();
                } else if (key.code() == KeyCode.BACKSPACE && isNumber(row)) {
                    setNumber(row, number(row) / 10);
                }
            }
            case CHAR -> {
                int c = key.codePoint();
                switch (c) {
                    case 'k' -> selected = Math.max(selected - 1, 0);
                    case 'j' -> selected = Math.min(selected + 1, last);
                    case 'g' -> selected = 0;
                    case 'G' -> selected = last;
                    case 'h', '-' -> change(row, index, -1);
                    case 'l', '+', ' ' -> change(row, index, 1);
                    case 'a' -> Collections.fill(draft.chosen, true);
                    case 'n' -> Collections.fill(draft.chosen, false);
                    case 'd' -> {
                        if (row == Row.STOP_WORDS) {
                            draft.stopWords = List.of();
                        }
                    }
                    default -> {
                        if (c >= '0' && c <= '9' && isNumber(row)) {
                            setNumber(row, Form.typeDigit(number(row), (char) c, max(row)));
                        }
                    }
                }
            }
            default -> {}
        }
        if (draft.chosen.contains(true)) {
            noField = false;
        }
        return new Form.Event.None<>();
    }

    private static boolean isNumber(Row row) {
        return row != Row.BOOST && row != Row.STOP_WORDS && row != Row.FIELD;
    }

    private int number(Row row) {
        return switch (row) {
            case MIN_TERM_FREQ -> draft.minTermFreq;
            case MIN_DOC_FREQ -> draft.minDocFreq;
            case MAX_DOC_FREQ -> draft.maxDocFreq;
            case MAX_QUERY_TERMS -> draft.maxQueryTerms;
            case MIN_WORD_LEN -> draft.minWordLen;
            case MAX_WORD_LEN -> draft.maxWordLen;
            default -> throw new IllegalArgumentException(row.name());
        };
    }

    private static int max(Row row) {
        return switch (row) {
            case MAX_QUERY_TERMS -> MoreLikeThisQuery.MAX_QUERY_TERMS;
            case MIN_WORD_LEN, MAX_WORD_LEN -> MAX_WORD_LEN;
            default -> MAX_FREQ;
        };
    }

    private void setNumber(Row row, int value) {
        switch (row) {
            case MIN_TERM_FREQ -> draft.minTermFreq = value;
            case MIN_DOC_FREQ -> draft.minDocFreq = value;
            case MAX_DOC_FREQ -> draft.maxDocFreq = value;
            case MAX_QUERY_TERMS -> draft.maxQueryTerms = value;
            case MIN_WORD_LEN -> draft.minWordLen = value;
            case MAX_WORD_LEN -> draft.maxWordLen = value;
            default -> throw new IllegalArgumentException(row.name());
        }
    }

    /** Steps a number by {@code step}, switches the boost or a field, or starts typing the stop words forward. */
    private void change(Row row, int index, int step) {
        if (isNumber(row)) {
            int min = row == Row.MAX_QUERY_TERMS ? 1 : 0;
            setNumber(row, Math.clamp((long) number(row) + step, min, max(row)));
            return;
        }
        switch (row) {
            case BOOST -> draft.boost = !draft.boost;
            case STOP_WORDS -> {
                if (step > 0) {
                    editing = new LineInput(String.join(" ", draft.stopWords));
                }
            }
            case FIELD -> {
                int field = index - Row.FIELD.ordinal();
                if (field < draft.chosen.size()) {
                    draft.chosen.set(field, !draft.chosen.get(field));
                }
            }
            default -> {}
        }
    }

    /** The keys for the footer while the popup is open. */
    public String keys() {
        return editing != null
                ? " ⏎ take   Esc back   ←→ Ctrl+A/E/U/K/W edit"
                : " ⏎ apply   Esc cancel   ↑↓ select   ←→ Space change   0-9 number   a/n all/no fields";
    }

    public void render(Frame frame, Rect area) {
        int count = rowCount();
        int shown = Math.min(selected, count - 1);
        List<Line> lines = new ArrayList<>();
        int selectedLine = 0;
        for (int i = 0; i < count; i++) {
            Row row = row(i);
            if (row == Row.MIN_WORD_LEN) {
                lines.add(Line.empty());
                lines.add(Line.styled("Words left out, after analysis", Form.dim()));
            }
            if (i == Row.FIELD.ordinal()) {
                lines.add(Line.empty());
                lines.add(Line.styled("Fields to take terms from", Form.dim()));
            }
            String label;
            String value;
            switch (row) {
                case MIN_TERM_FREQ -> {
                    label = "Min term freq";
                    value = Integer.toString(draft.minTermFreq);
                }
                case MIN_DOC_FREQ -> {
                    label = "Min doc freq";
                    value = Integer.toString(draft.minDocFreq);
                }
                case MAX_DOC_FREQ -> {
                    label = "Max doc freq";
                    value = limit(draft.maxDocFreq);
                }
                case MAX_QUERY_TERMS -> {
                    label = "Max query terms";
                    value = Integer.toString(draft.maxQueryTerms);
                }
                case BOOST -> {
                    label = "Boost by score";
                    value = draft.boost ? "yes" : "no";
                }
                case MIN_WORD_LEN -> {
                    label = "Min word length";
                    value = limit(draft.minWordLen);
                }
                case MAX_WORD_LEN -> {
                    label = "Max word length";
                    value = limit(draft.maxWordLen);
                }
                case STOP_WORDS -> {
                    label = "Stop words";
                    value = draft.stopWords.isEmpty()
                            ? "none"
                            : LikeSettings.stopWordsText(draft.stopWords).replaceFirst("^stop words ", "");
                }
                default -> {
                    int field = i - Row.FIELD.ordinal();
                    label = "  " + TextFormat.escapeControl(draft.names.get(field));
                    value = draft.chosen.get(field) ? "[x]" : "[ ]";
                }
            }
            if (i == shown) {
                selectedLine = lines.size();
            }
            lines.add(Form.row(label, value, i == shown));
        }
        lines.add(Line.empty());
        if (noField) {
            lines.add(Line.styled("Choose at least one field", Style.EMPTY.red()));
        } else {
            lines.add(Line.styled("Terms: a document's term vector or analyzed", Form.dim()));
            lines.add(Line.styled("stored text, or the text, analyzed", Form.dim()));
        }
        Form.renderPopup(frame, area, " More like this ", lines, selectedLine, WIDTH);
        if (editing != null) {
            renderStopWords(frame, area, editing);
        }
    }

    /** A number, or {@code no limit} for 0. */
    private static String limit(int number) {
        return number > 0 ? Integer.toString(number) : "no limit";
    }

    /** The line that the stop words are typed in, over the popup. */
    private static void renderStopWords(Frame frame, Rect area, LineInput input) {
        int width = Math.min(area.width(), WIDTH + 20);
        int height = Math.min(3, area.height());
        Rect popup =
                new Rect(area.x() + (area.width() - width) / 2, area.y() + (area.height() - height) / 2, width, height);
        Block block = Block.builder()
                .borders(Borders.ALL)
                .title(" Stop words, as analyzed terms: a the with ")
                .build();
        Rect inner = block.inner(popup);
        frame.renderWidget(Clear.INSTANCE, popup);
        frame.renderWidget(block, popup);
        LineInput.Visible visible = input.visible(inner.width());
        frame.renderWidget(Paragraph.from(Line.from(visible.text())), inner);
        frame.setCursorPosition(inner.x() + visible.cursorColumn(), inner.y());
    }
}
