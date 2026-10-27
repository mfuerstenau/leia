package io.github.mfuerstenau.leia.tui.theme;

/**
 * A colour theme of leia (ADR 0095, ADR 0096): one of {@link Themes}, bundled or the user's own.
 *
 * @param id the name that {@code --theme} and the theme file use, and the file's name without
 *     {@code .theme}
 * @param palette the styles of this theme
 */
public record Theme(String id, Palette palette) {}
