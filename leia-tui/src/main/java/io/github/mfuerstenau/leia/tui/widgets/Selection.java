package io.github.mfuerstenau.leia.tui.widgets;

import dev.tamboui.tui.event.KeyEvent;
import java.util.OptionalInt;
import java.util.stream.LongStream;

/** How the selection of a list moves, and how wide its number columns are. */
public final class Selection {

    /** Rows that PageUp and PageDown move. */
    public static final int PAGE = 10;

    private Selection() {}

    /**
     * Where a key moves the selection {@code selected} of a list of {@code len} rows: ↑↓ and {@code
     * j}/{@code k} by a row, PageUp and PageDown by {@link #PAGE}, Home and {@code g} to the first,
     * End and {@code G} to the last. Empty for any other key.
     */
    public static OptionalInt moved(int selected, int len, KeyEvent key) {
        int last = Math.max(len - 1, 0);
        long row;
        switch (key.code()) {
            case DOWN -> row = selected + 1L;
            case UP -> row = selected - 1L;
            case PAGE_DOWN -> row = (long) selected + PAGE;
            case PAGE_UP -> row = (long) selected - PAGE;
            case HOME -> row = 0;
            case END -> row = last;
            case CHAR -> {
                switch (key.codePoint()) {
                    case 'j' -> row = selected + 1L;
                    case 'k' -> row = selected - 1L;
                    case 'g' -> row = 0;
                    case 'G' -> row = last;
                    default -> {
                        return OptionalInt.empty();
                    }
                }
            }
            default -> {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(Math.clamp(row, 0, last));
    }

    /** The width of a column of {@code numbers}: the widest one, and at least 4. */
    public static int numberWidth(LongStream numbers) {
        return Math.max(
                numbers.mapToInt(number -> Long.toString(number).length()).max().orElse(0), 4);
    }
}
