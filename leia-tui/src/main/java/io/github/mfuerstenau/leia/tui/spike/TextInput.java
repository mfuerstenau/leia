package io.github.mfuerstenau.leia.tui.spike;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/** A one-line text input with the keys of {@code tui/src/input.rs}. The cursor is a char index. */
final class TextInput {

    private final StringBuilder text = new StringBuilder();
    private int cursor;

    String text() {
        return text.toString();
    }

    int cursor() {
        return cursor;
    }

    /** Edits the text or moves the cursor; returns whether {@code key} was one of the input's keys. */
    boolean handleKey(KeyEvent key) {
        String typed = typed(key);
        if (typed != null) {
            text.insert(cursor, typed);
            cursor += typed.length();
            return true;
        }
        boolean ctrl = key.hasCtrl() && !key.hasAlt();
        if (ctrl && key.code() == KeyCode.CHAR) {
            switch (key.codePoint()) {
                case 'a' -> cursor = 0;
                case 'e' -> cursor = text.length();
                case 'u' -> delete(0, cursor);
                case 'k' -> delete(cursor, text.length());
                case 'w' -> delete(wordStart(), cursor);
                default -> {
                    return false;
                }
            }
            return true;
        }
        switch (key.code()) {
            case LEFT -> cursor = previous();
            case RIGHT -> cursor = next();
            case HOME -> cursor = 0;
            case END -> cursor = text.length();
            case BACKSPACE -> delete(previous(), cursor);
            case DELETE -> delete(cursor, next());
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * The text that {@code key} types, if it types one. Ctrl or Alt alone make a shortcut, but both
     * together are AltGr, as {@code typed_char} in {@code tui/src/input.rs} has it.
     */
    static String typed(KeyEvent key) {
        if (key.code() != KeyCode.CHAR || key.hasCtrl() != key.hasAlt()) {
            return null;
        }
        return Character.toString(key.codePoint());
    }

    private void delete(int from, int to) {
        text.delete(from, to);
        cursor = from;
    }

    private int previous() {
        return cursor == 0 ? 0 : text.offsetByCodePoints(cursor, -1);
    }

    private int next() {
        return cursor == text.length() ? cursor : text.offsetByCodePoints(cursor, 1);
    }

    /** Where the word before the cursor starts, ignoring whitespace right before the cursor. */
    private int wordStart() {
        int i = cursor;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }
}
