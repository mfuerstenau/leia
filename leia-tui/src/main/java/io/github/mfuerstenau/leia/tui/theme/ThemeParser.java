package io.github.mfuerstenau.leia.tui.theme;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a theme file (ADR 0096): a {@code role = value} per line, where a role is a component of
 * {@link Palette} and every one must be set. A style is {@code none}, or {@code [colour] [on colour]
 * [modifier…]}; a colour is a name such as {@code dark-gray}, an index of the 256 colours or
 * {@code #RRGGBB}. {@code logo} names an {@link Icon} (ADR 0099). A line starting with {@code #} is
 * a comment.
 */
public final class ThemeParser {

    /** A theme file that leia can't read: where, and why. */
    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    /** The role that names the logo's icon. */
    private static final String LOGO = "logo";

    /** The roles that ADR 0097 had for the logo's colours, which the icon's colours replace. */
    private static final Pattern OLD_LOGO = Pattern.compile("logo[0-5]");

    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern INDEX = Pattern.compile("[0-9]{1,3}");

    private static final Map<String, Color> NAMED = Map.ofEntries(
            Map.entry("black", Color.BLACK),
            Map.entry("red", Color.RED),
            Map.entry("green", Color.GREEN),
            Map.entry("yellow", Color.YELLOW),
            Map.entry("blue", Color.BLUE),
            Map.entry("magenta", Color.MAGENTA),
            Map.entry("cyan", Color.CYAN),
            Map.entry("white", Color.WHITE),
            Map.entry("gray", Color.GRAY),
            Map.entry("dark-gray", Color.DARK_GRAY),
            Map.entry("light-red", Color.LIGHT_RED),
            Map.entry("light-green", Color.LIGHT_GREEN),
            Map.entry("light-yellow", Color.LIGHT_YELLOW),
            Map.entry("light-blue", Color.LIGHT_BLUE),
            Map.entry("light-magenta", Color.LIGHT_MAGENTA),
            Map.entry("light-cyan", Color.LIGHT_CYAN),
            Map.entry("bright-white", Color.BRIGHT_WHITE));

    private static final Map<String, Modifier> MODIFIERS = Map.of(
            "bold", Modifier.BOLD,
            "dim", Modifier.DIM,
            "italic", Modifier.ITALIC,
            "underlined", Modifier.UNDERLINED,
            "reversed", Modifier.REVERSED);

    /** The style roles, in the order of {@link Palette}'s components. */
    private static final List<String> ROLES = Arrays.stream(Palette.class.getRecordComponents())
            .filter(component -> component.getType() == Style.class)
            .map(RecordComponent::getName)
            .toList();

    private ThemeParser() {}

    /** The palette of a theme file whose logo is one of leia's own icons. */
    public static Palette parse(String source, String text) throws Invalid {
        return parse(source, text, Icons.bundled());
    }

    /**
     * The palette of a theme file.
     *
     * @param source the file, as the message of an {@link Invalid} names it
     * @param icons where the icon of {@code logo} comes from
     * @throws Invalid if a line can't be read, a role is set twice or not at all, or the icon is
     *     missing or broken
     */
    public static Palette parse(String source, String text, Icons icons) throws Invalid {
        Map<String, Style> styles = new HashMap<>();
        Icon logo = null;
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = source + ":" + (i + 1) + ": ";
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw new Invalid(where + "expected role = value");
            }
            String key = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (OLD_LOGO.matcher(key).matches()) {
                throw new Invalid(
                        where + "unknown role '" + key + "': the logo is logo = <icon>, which has colours of its own");
            } else if (key.equals(LOGO)) {
                if (logo != null) {
                    throw new Invalid(where + key + " is set twice");
                }
                logo = icons.find(where, value);
            } else if (ROLES.contains(key)) {
                if (styles.containsKey(key)) {
                    throw new Invalid(where + key + " is set twice");
                }
                styles.put(key, style(where, value));
            } else {
                throw new Invalid(where + "unknown role '" + key + "'");
            }
        }
        List<String> missing = new ArrayList<>(
                ROLES.stream().filter(role -> !styles.containsKey(role)).toList());
        if (logo == null) {
            missing.add(LOGO);
        }
        if (!missing.isEmpty()) {
            throw new Invalid(source + ": missing " + String.join(", ", missing));
        }
        return new Palette(
                styles.get("base"),
                styles.get("error"),
                styles.get("warning"),
                styles.get("success"),
                styles.get("accent"),
                styles.get("jsonKey"),
                styles.get("jsonString"),
                styles.get("jsonNumber"),
                styles.get("jsonLiteral"),
                styles.get("match"),
                styles.get("muted"),
                styles.get("border"),
                styles.get("borderFocused"),
                logo);
    }

    private static Style style(String where, String value) throws Invalid {
        if (value.equals("none")) {
            return Style.EMPTY;
        }
        if (value.isEmpty()) {
            throw new Invalid(where + "no style; none is the terminal's own");
        }
        Style style = Style.EMPTY;
        List<String> words = Arrays.asList(value.split("\\s+"));
        int i = 0;
        if (!words.get(i).equals("on") && !MODIFIERS.containsKey(words.get(i))) {
            style = style.fg(colour(where, words.get(i)));
            i++;
        }
        if (i < words.size() && words.get(i).equals("on")) {
            if (i + 1 == words.size()) {
                throw new Invalid(where + "no colour after on");
            }
            style = style.bg(colour(where, words.get(i + 1)));
            i += 2;
        }
        for (; i < words.size(); i++) {
            Modifier modifier = MODIFIERS.get(words.get(i));
            if (modifier == null) {
                throw new Invalid(where + "unknown modifier '" + words.get(i) + "': "
                        + String.join(", ", MODIFIERS.keySet().stream().sorted().toList()));
            }
            style = style.addModifier(modifier);
        }
        return style;
    }

    static Color colour(String where, String word) throws Invalid {
        Color named = NAMED.get(word.toLowerCase(Locale.ROOT));
        if (named != null) {
            return named;
        }
        if (HEX.matcher(word).matches()) {
            return Color.hex(word);
        }
        if (INDEX.matcher(word).matches() && Integer.parseInt(word) <= 255) {
            return Color.indexed(Integer.parseInt(word));
        }
        throw new Invalid(where + "unknown colour '" + word + "': a name such as dark-gray, 0 to 255 or #RRGGBB");
    }
}
