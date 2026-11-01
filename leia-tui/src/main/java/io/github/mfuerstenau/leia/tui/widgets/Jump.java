package io.github.mfuerstenau.leia.tui.widgets;

import java.util.Arrays;
import java.util.Optional;

/**
 * Where the {@code g} menu goes from a screen (ADR 0120), each with the same letter on every screen.
 * What a jump means depends on the screen and its selection: {@link GoMenu.Target} says it.
 */
public enum Jump {
    TERMS('t'),
    SEARCH('s'),
    ANALYSIS('a'),
    COMMIT('c'),
    DOCUMENT('d'),
    SIMILAR('m'),
    NEAREST('n');

    private final char letter;

    Jump(char letter) {
        this.letter = letter;
    }

    /** The letter after {@code g}. */
    public char letter() {
        return letter;
    }

    /** The jump of {@code letter}; empty for a letter that is none. */
    public static Optional<Jump> of(int letter) {
        return Arrays.stream(values()).filter(jump -> jump.letter == letter).findFirst();
    }
}
