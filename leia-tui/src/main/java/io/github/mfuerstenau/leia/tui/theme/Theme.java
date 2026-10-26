package io.github.mfuerstenau.leia.tui.theme;

import java.util.Arrays;
import java.util.Optional;

/** The colour themes of leia (ADR 0095): {@code dark} is the default. */
public enum Theme {
    DARK("dark"),
    LIGHT("light"),
    MONO("mono"),
    NORD("nord");

    private final String id;

    Theme(String id) {
        this.id = id;
    }

    /** The name that {@code --theme} and the theme file use. */
    public String id() {
        return id;
    }

    /** The theme named {@code id}, if there is one. */
    public static Optional<Theme> parse(String id) {
        return Arrays.stream(values()).filter(theme -> theme.id.equals(id)).findFirst();
    }

    /** The theme after this one, as {@code T} cycles them; after the last, the first. */
    public Theme next() {
        Theme[] themes = values();
        return themes[(ordinal() + 1) % themes.length];
    }

    /** The styles of this theme. */
    public Palette palette() {
        return switch (this) {
            case DARK -> Palette.DARK;
            case LIGHT -> Palette.LIGHT;
            case MONO -> Palette.MONO;
            case NORD -> Palette.NORD;
        };
    }
}
