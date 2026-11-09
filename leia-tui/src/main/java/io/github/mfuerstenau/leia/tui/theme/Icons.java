package io.github.mfuerstenau.leia.tui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where a theme's icons come from (ADR 0099): the user's themes folder, then leia's own, resource
 * files beside this class. Each file is looked up on its own, so a colours file of the user's can
 * colour the characters of one of leia's.
 *
 * @param dir the user's themes folder; null for leia's own icons only
 */
public record Icons(@Nullable Path dir) {

    private static final String CHARS = ".chars";
    private static final String COLOURS = ".colours";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** leia's own icons. */
    public static Icons bundled() {
        return new Icons(null);
    }

    /** The user's icons of {@code dir}, then leia's own. */
    public Icons withUserDir(Path dir) {
        return new Icons(dir);
    }

    /**
     * The icon {@code name}: {@code <name>.colours}, and the chars file it names, else
     * {@code <name>.chars}.
     *
     * @param where where the theme names it, as a message starts
     * @throws ThemeParser.Invalid if the name is no name, a file is missing or can't be read, or
     *     the icon is broken
     */
    public Icon find(String where, String name) throws ThemeParser.Invalid {
        String coloursFile = name + COLOURS;
        String colours = read(where, name, coloursFile);
        String charsName = Icon.charsOf(colours).orElse(name);
        String charsFile = charsName + CHARS;
        String chars = read(where, charsName, charsFile);
        try {
            return Icon.parse(charsFile, chars, coloursFile, colours);
        } catch (ThemeParser.Invalid e) {
            throw new ThemeParser.Invalid(where + "icon " + name + ": " + e.getMessage());
        }
    }

    private String read(String where, String name, String file) throws ThemeParser.Invalid {
        if (!NAME.matcher(name).matches()) {
            throw new ThemeParser.Invalid(
                    where + "no icon '" + name + "': its name is lower-case letters, digits and -");
        }
        if (dir != null && Files.isRegularFile(dir.resolve(file))) {
            try {
                return Files.readString(dir.resolve(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ThemeParser.Invalid(where + dir.resolve(file) + " can't be read: " + e.getMessage());
            }
        }
        try (InputStream in = Icons.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new ThemeParser.Invalid(where + "no icon file " + file + (dir == null ? "" : " in " + dir + " or")
                        + " among leia's own");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
