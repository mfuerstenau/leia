package io.github.mfuerstenau.leia.tui.prompt;

import dev.tamboui.text.CharWidth;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.function.ToIntFunction;

/**
 * A one-line text editor, shared by the open-index prompt and, later, the search query: the cursor
 * moves by character, and Ctrl+A/E/U/K/W edit as in a shell.
 */
public final class LineInput {

    /** What a key did to the input. */
    public enum Edit {
        /** The text changed. */
        CHANGED,
        /** Only the cursor moved, or the key did nothing. */
        UNCHANGED
    }

    /**
     * The part of the text that fits, and the cursor's column in it.
     *
     * @param text the text from the first shown character on
     * @param cursorColumn the cursor's column, in cells
     */
    public record Visible(String text, int cursorColumn) {}

    private final StringBuilder text;

    /** Index of the cursor in {@link #text}, in chars, never inside a surrogate pair. */
    private int cursor;

    /** The input with the cursor at the end. */
    public LineInput(String text) {
        this.text = new StringBuilder(text);
        this.cursor = text.length();
    }

    public String text() {
        return text.toString();
    }

    /** Replaces the text and puts the cursor at the end. */
    public void set(String text) {
        this.text.setLength(0);
        this.text.append(text);
        cursor = text.length();
    }

    /**
     * Moves the cursor or edits the text. Ctrl+W deletes back to {@code wordStart(text before the
     * cursor)}. Keys that don't edit, such as Enter, Esc and Tab, are left to the caller.
     */
    public Edit handleKey(KeyEvent key, ToIntFunction<String> wordStart) {
        String typed = typed(key);
        if (typed != null) {
            text.insert(cursor, typed);
            cursor += typed.length();
            return Edit.CHANGED;
        }
        boolean ctrl = key.hasCtrl();
        if (ctrl && key.code() == KeyCode.CHAR) {
            switch (key.codePoint()) {
                case 'a' -> cursor = 0;
                case 'e' -> cursor = text.length();
                case 'u' -> {
                    return delete(0, cursor);
                }
                case 'k' -> {
                    return delete(cursor, text.length());
                }
                case 'w' -> {
                    return delete(wordStart.applyAsInt(text.substring(0, cursor)), cursor);
                }
                default -> {}
            }
            return Edit.UNCHANGED;
        }
        switch (key.code()) {
            case LEFT -> cursor = previous();
            case RIGHT -> cursor = next();
            case HOME -> cursor = 0;
            case END -> cursor = text.length();
            case BACKSPACE -> {
                return delete(previous(), cursor);
            }
            case DELETE -> {
                return delete(cursor, next());
            }
            default -> {}
        }
        return Edit.UNCHANGED;
    }

    private Edit delete(int from, int to) {
        if (from >= to) {
            return Edit.UNCHANGED;
        }
        text.delete(from, to);
        cursor = from;
        return Edit.CHANGED;
    }

    private int previous() {
        return cursor == 0 ? 0 : text.offsetByCodePoints(cursor, -1);
    }

    private int next() {
        return cursor == text.length() ? cursor : text.offsetByCodePoints(cursor, 1);
    }

    /**
     * The part of the text that fits into {@code width} cells with the cursor visible, and the
     * cursor's column in it. Long text scrolls so that the cursor stays in the last cell.
     */
    public Visible visible(int width) {
        int cells = Math.max(width, 1);
        String before = text.substring(0, cursor);
        int start = 0;
        while (CharWidth.of(before.substring(start)) >= cells) {
            start = before.offsetByCodePoints(start, 1);
        }
        return new Visible(text.substring(start), CharWidth.of(before.substring(start)));
    }

    /**
     * The text that {@code key} types, or null if it types none. Ctrl or Alt alone make a shortcut,
     * but both together are AltGr: Windows reports {@code \} or {@code @} on a German layout, typed
     * or pasted, with both.
     */
    public static String typed(KeyEvent key) {
        if (key.code() != KeyCode.CHAR || key.hasCtrl() != key.hasAlt()) {
            return null;
        }
        return Character.toString(key.codePoint());
    }

    /**
     * Where the word before the cursor starts, for Ctrl+W: after the last whitespace, ignoring
     * whitespace right before the cursor.
     */
    public static int wordStart(String before) {
        int i = before.length();
        while (i > 0 && isWhitespace(before.codePointBefore(i))) {
            i -= Character.charCount(before.codePointBefore(i));
        }
        while (i > 0 && !isWhitespace(before.codePointBefore(i))) {
            i -= Character.charCount(before.codePointBefore(i));
        }
        return i;
    }

    /** Unicode's White_Space, as Rust's {@code char::is_whitespace}: no-break spaces too. */
    private static boolean isWhitespace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
    }
}
