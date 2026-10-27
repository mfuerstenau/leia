package io.github.mfuerstenau.leia.tui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The themes leia knows, in the order {@code T} cycles them (ADR 0096): the bundled {@code dark},
 * {@code light}, {@code mono} and {@code nord}, then the user's own from the themes folder.
 *
 * @param themes the themes; never empty, and {@code dark} among them
 */
public record Themes(List<Theme> themes) {

    private static final Logger LOG = Logger.getLogger(Themes.class.getName());

    /** The bundled themes, resource files beside this class. */
    static final List<String> BUNDLED = List.of("dark", "light", "mono", "nord");

    /** The default theme. */
    public static final String DARK = "dark";

    /** The theme that {@code NO_COLOR} chooses: no colour at all. */
    public static final String MONO = "mono";

    private static final String SUFFIX = ".theme";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private static final class Bundled {
        static final Themes THEMES = load();

        private static Themes load() {
            List<Theme> themes = new ArrayList<>();
            for (String id : BUNDLED) {
                String name = id + SUFFIX;
                try (InputStream in = Themes.class.getResourceAsStream(name)) {
                    if (in == null) {
                        throw new IllegalStateException("No bundled theme " + name);
                    }
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    themes.add(new Theme(id, ThemeParser.parse(name, text)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ThemeParser.Invalid e) {
                    throw new IllegalStateException("A bundled theme is broken: " + e.getMessage(), e);
                }
            }
            return new Themes(List.copyOf(themes));
        }
    }

    public Themes {
        themes = List.copyOf(themes);
    }

    /** The bundled themes. A bundled file that is broken is a bug of the build, and throws. */
    public static Themes bundled() {
        return Bundled.THEMES;
    }

    /**
     * These themes and those of {@code dir}, each a {@code <id>.theme} file, in the order of their
     * names. One with the id of a theme here replaces it in its place; the others come after. A
     * file that can't be read, or whose name is no id, is logged and left out: a broken theme
     * mustn't stop leia.
     */
    public Themes withUserDir(Optional<Path> dir) {
        if (dir.isEmpty() || !Files.isDirectory(dir.get())) {
            return this;
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(dir.get())) {
            files = listed.filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list the themes in " + dir.get(), e);
            return this;
        }
        List<Theme> merged = new ArrayList<>(themes);
        for (Path file : files) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - SUFFIX.length());
            if (!ID.matcher(id).matches()) {
                LOG.warning(() -> "Theme " + file + " left out: its name is to be lower-case letters, digits and -");
                continue;
            }
            Palette palette;
            try {
                palette = ThemeParser.parse(file.toString(), Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Theme " + file + " left out: it can't be read", e);
                continue;
            } catch (ThemeParser.Invalid e) {
                LOG.warning(() -> "Theme left out: " + e.getMessage());
                continue;
            }
            Theme theme = new Theme(id, palette);
            int at = indexOf(merged, id);
            if (at >= 0) {
                LOG.info(() -> "Theme " + id + " replaced by " + file);
                merged.set(at, theme);
            } else {
                LOG.info(() -> "Theme " + id + " loaded from " + file);
                merged.add(theme);
            }
        }
        return new Themes(merged);
    }

    /** The theme named {@code id}, if there is one. */
    public Optional<Theme> find(String id) {
        return themes.stream().filter(theme -> theme.id().equals(id)).findFirst();
    }

    /** {@code dark}, the default. */
    public Theme dark() {
        return find(DARK).orElseThrow();
    }

    /** The theme after {@code theme}, as {@code T} cycles them; after the last, the first. */
    public Theme next(Theme theme) {
        int at = indexOf(themes, theme.id());
        return themes.get((at + 1) % themes.size());
    }

    /** The ids, in order. */
    public List<String> ids() {
        return themes.stream().map(Theme::id).toList();
    }

    private static int indexOf(List<Theme> themes, String id) {
        for (int i = 0; i < themes.size(); i++) {
            if (themes.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
