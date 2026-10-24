package io.github.mfuerstenau.leia.tui.search;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.event.KeyEvent;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import io.github.mfuerstenau.leia.tui.widgets.Form;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The popup that chooses the {@link HitFields}: a draft of the choice, applied with Enter. */
public final class FieldsForm {

    private final List<String> names;
    private final List<Boolean> shown;
    private int selected;

    public FieldsForm(HitFields fields) {
        this.names = fields.names();
        this.shown = new ArrayList<>(fields.shown());
    }

    private HitFields draft() {
        return new HitFields(names, shown);
    }

    /** ↑↓ select a field, Space shows or hides it, {@code a} shows all, {@code n} none; Enter applies, Esc cancels. */
    public Form.Event<HitFields> handleKey(KeyEvent key) {
        int last = Math.max(names.size() - 1, 0);
        switch (key.code()) {
            case ENTER -> {
                return new Form.Event.Apply<>(draft());
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
            case LEFT, RIGHT -> toggle();
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'k' -> selected = Math.max(selected - 1, 0);
                    case 'j' -> selected = Math.min(selected + 1, last);
                    case 'g' -> selected = 0;
                    case 'G' -> selected = last;
                    case ' ' -> toggle();
                    case 'a' -> Collections.fill(shown, true);
                    case 'n' -> Collections.fill(shown, false);
                    default -> {}
                }
            }
            default -> {}
        }
        return new Form.Event.None<>();
    }

    private void toggle() {
        if (selected < shown.size()) {
            shown.set(selected, !shown.get(selected));
        }
    }

    /** The keys for the footer while the popup is open. */
    public static String keys() {
        return " ⏎ apply   Esc cancel   ↑↓ select   Space show/hide   a all   n none";
    }

    public void render(Frame frame, Rect area) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            lines.add(Form.row(
                    TextFormat.escapeControl(names.get(i)),
                    shown.get(i).booleanValue() ? "[x]" : "[ ]",
                    i == selected));
        }
        Form.renderPopup(frame, area, " Stored fields per hit ", lines, selected, 40);
    }
}
