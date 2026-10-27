package io.github.mfuerstenau.leia.tui.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * The theme chosen last (ADR 0095), bundled or the user's own (ADR 0096), kept as its id on one line of a file in the platform's config
 * folder, beside the default folder of jars.
 *
 * @param file where the theme is kept; empty keeps it nowhere
 */
public record ThemeFile(Optional<Path> file) {

    /** A theme file that keeps nothing. */
    public static ThemeFile none() {
        return new ThemeFile(Optional.empty());
    }

    /**
     * The theme of {@code themes} that the file names. A missing or unreadable file, or an id that
     * isn't among them, is none.
     */
    public Optional<Theme> load(Themes themes) {
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            return themes.find(
                    Files.readString(file.get(), StandardCharsets.UTF_8).strip());
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    /**
     * Writes {@code theme} to the file, through a temporary one. A failed write is ignored: the
     * theme is a convenience, and the TUI has nowhere to report it.
     */
    public void save(Theme theme) {
        if (file.isEmpty()) {
            return;
        }
        Path target = file.get();
        Path temporary = target.resolveSibling(
                target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Path dir = target.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(temporary, theme.id() + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException _) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException _) {
                // Nothing else to do: the theme is a convenience.
            }
        }
    }

    /**
     * The theme of {@code themes} to start with: {@code option}, the theme of {@code --theme}, which
     * the caller checked to be among them; then {@code NO_COLOR} (no-color.org: set and not empty
     * means {@code mono}); then this file; then {@code dark}.
     */
    public Theme resolve(Optional<Theme> option, String noColor, Themes themes) {
        if (option.isPresent()) {
            return option.get();
        }
        if (noColor != null && !noColor.isEmpty()) {
            return themes.find(Themes.MONO).orElseThrow();
        }
        return load(themes).orElseGet(themes::dark);
    }
}
