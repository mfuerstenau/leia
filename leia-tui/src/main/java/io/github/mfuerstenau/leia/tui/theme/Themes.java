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
 * {@code light}, {@code mono}, {@code nord}, {@code solarized-dark} and {@code solarized-light}, then the user's own from the themes folder.
 *
 * @param themes the themes; never empty, and {@code dark} among them
 * @param leftOut the theme files of the user's that were read and refused, with why (ADR 0121)
 */
public record Themes(List<Theme> themes, List<LeftOut> leftOut) {

    /**
     * A theme file of the user's that leia refused.
     *
     * @param id the theme it would have been, from the file's name
     * @param reason why, naming the file
     */
    public record LeftOut(String id, String reason) {}

    private static final Logger LOG = Logger.getLogger(Themes.class.getName());

    /** The bundled themes, resource files beside this class. */
    static final List<String> BUNDLED = List.of("dark", "light", "mono", "nord", "solarized-dark", "solarized-light");

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
        leftOut = List.copyOf(leftOut);
    }

    /** These themes, none of the user's left out. */
    public Themes(List<Theme> themes) {
        this(themes, List.of());
    }

    /** The bundled themes. A bundled file that is broken is a bug of the build, and throws. */
    public static Themes bundled() {
        return Bundled.THEMES;
    }

    /**
     * These themes and those of {@code dir}, each a {@code <id>.theme} file, in the order of their
     * names, whose icons come from {@code dir} or leia's own (ADR 0099). One with the id of a theme here replaces it in its place; the others come after. A
     * file that can't be read, or whose name is no id, is logged and left out, and named in {@link #leftOut}: a broken theme
     * mustn't stop leia.
     */
    public Themes withUserDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return this;
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(dir)) {
            files = listed.filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list the themes in " + dir, e);
            return this;
        }
        Icons icons = Icons.bundled().withUserDir(dir);
        List<Theme> merged = new ArrayList<>(themes);
        List<LeftOut> refused = new ArrayList<>(leftOut);
        for (Path file : files) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - SUFFIX.length());
            if (!ID.matcher(id).matches()) {
                String reason = file + ": its name is to be lower-case letters, digits and -";
                LOG.warning(() -> "Theme left out: " + reason);
                refused.add(new LeftOut(id, reason));
                continue;
            }
            Palette palette;
            try {
                palette = ThemeParser.parse(file.toString(), Files.readString(file, StandardCharsets.UTF_8), icons);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Theme " + file + " left out: it can't be read", e);
                refused.add(new LeftOut(id, file + ": it can't be read"));
                continue;
            } catch (ThemeParser.Invalid e) {
                LOG.warning(() -> "Theme left out: " + e.getMessage());
                refused.add(new LeftOut(id, e.getMessage()));
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
        return new Themes(merged, refused);
    }

    /**
     * These themes and those of {@code dirs}, which are folders of theme files in order of
     * precedence, the one that wins a clash of names first (ADR 0122): applied from the last, so that
     * an earlier folder's theme replaces a later one's.
     */
    public Themes withDirs(List<Path> dirs) {
        Themes merged = this;
        for (Path dir : dirs.reversed()) {
            merged = merged.withUserDir(dir);
        }
        return merged;
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
