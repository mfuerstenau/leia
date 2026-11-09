package io.github.mfuerstenau.leia.server.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The runtimes leia knows, from the catalog {@value #RESOURCE} (ADR 0078): for each its folder in
 * {@code runtimes/}, its name and the majors of the indexes it reads, newest first. The catalog is
 * part of leia, so a catalog that can't be read is a bug of the build, and fails.
 *
 * <p>Read with a small JSON reader of its own, since the server has only the JDK; it knows objects,
 * arrays, strings, integers, booleans and null, which is all the catalog needs.
 */
public final class RuntimeCatalog {

    private static final String RUNTIMES = "runtimes";

    /** The catalog, as a resource. */
    public static final String RESOURCE = "META-INF/leia/runtimes.json";

    private static final Pattern FOLDER = Pattern.compile("[a-z0-9]+");
    private static final Set<String> ENTRY_KEYS = Set.of("folder", "name", "indexMajors");

    private RuntimeCatalog() {}

    /**
     * The runtimes in the catalog that {@code loader} sees.
     *
     * @throws IllegalStateException if there is none, or it can't be read
     */
    public static List<RuntimeFolders.Known> load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("leia lacks its runtime catalog " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the runtime catalog " + RESOURCE + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("the runtime catalog " + RESOURCE + " is broken: " + e.getMessage(), e);
        }
    }

    /**
     * The runtimes of a catalog.
     *
     * @throws IllegalArgumentException if it isn't JSON, or not a catalog
     */
    static List<RuntimeFolders.Known> parse(String json) {
        Object root = new Reader(json).document();
        Map<String, Object> catalog = object(root, "the catalog");
        keys(catalog, Set.of(RUNTIMES), "the catalog");
        List<Object> entries = array(catalog.get(RUNTIMES), RUNTIMES);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("runtimes is empty");
        }
        List<RuntimeFolders.Known> known = new ArrayList<>();
        Set<String> folders = new HashSet<>();
        Map<Integer, String> majors = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String where = "runtimes[" + i + "]";
            Map<String, Object> entry = object(entries.get(i), where);
            keys(entry, ENTRY_KEYS, where);
            String folder = string(entry.get("folder"), where + ".folder");
            if (!FOLDER.matcher(folder).matches()) {
                throw new IllegalArgumentException(where + ".folder is not of a-z and 0-9: \"" + folder + "\"");
            }
            if (!folders.add(folder)) {
                throw new IllegalArgumentException("the folder " + folder + " is there twice");
            }
            String name = string(entry.get("name"), where + ".name");
            if (name.isBlank()) {
                throw new IllegalArgumentException(where + ".name is blank");
            }
            List<Integer> indexMajors = indexMajors(entry, where, folder, majors);
            known.add(new RuntimeFolders.Known(folder, name, indexMajors));
        }
        return List.copyOf(known);
    }

    /**
     * The majors whose indexes the runtime of {@code entry} reads; {@code majors} holds the folder of
     * each major seen, and a major that another runtime reads fails.
     */
    private static List<Integer> indexMajors(
            Map<String, Object> entry, String where, String folder, Map<Integer, String> majors) {
        List<Object> values = array(entry.get("indexMajors"), where + ".indexMajors");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(where + ".indexMajors is empty");
        }
        List<Integer> indexMajors = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Long major) || major < 1 || major > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(where + ".indexMajors has " + value + ", not a major");
            }
            String other = majors.putIfAbsent(major.intValue(), folder);
            if (other != null) {
                throw new IllegalArgumentException(
                        "the indexes of " + major + " are read by both " + other + " and " + folder);
            }
            indexMajors.add(major.intValue());
        }
        return List.copyOf(indexMajors);
    }

    private static void keys(Map<String, Object> object, Set<String> expected, String where) {
        for (String key : object.keySet()) {
            if (!expected.contains(key)) {
                throw new IllegalArgumentException(where + " has the unknown key \"" + key + "\"");
            }
        }
        for (String key : expected) {
            if (!object.containsKey(key)) {
                throw new IllegalArgumentException(where + " lacks \"" + key + "\"");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(@Nullable Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(where + " is not an object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(@Nullable Object value, String where) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException(where + " is not an array");
    }

    private static String string(@Nullable Object value, String where) {
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalArgumentException(where + " is not a string");
    }

    /**
     * A JSON document as objects ({@link LinkedHashMap}), arrays ({@link List}), strings, integers
     * ({@link Long}), booleans and {@link #NULL}.
     */
    private static final class Reader {

        private static final Object NULL = new Object();

        private final String json;
        private int pos;

        Reader(String json) {
            this.json = json;
        }

        Object document() {
            Object value = value();
            space();
            if (pos < json.length()) {
                throw error("more after the document");
            }
            return value;
        }

        private Object value() {
            space();
            if (pos >= json.length()) {
                throw error("the document ends early");
            }
            char c = json.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", NULL);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected '" + c + "'");
                }
            };
        }

        private Map<String, Object> object() {
            pos++;
            Map<String, Object> object = new LinkedHashMap<>();
            space();
            if (peek('}')) {
                pos++;
                return object;
            }
            while (true) {
                space();
                if (!peek('"')) {
                    throw error("expected a key");
                }
                String key = string();
                space();
                expect(':');
                if (object.put(key, value()) != null) {
                    throw error("the key \"" + key + "\" is there twice");
                }
                space();
                if (peek(',')) {
                    pos++;
                } else {
                    expect('}');
                    return object;
                }
            }
        }

        private List<Object> array() {
            pos++;
            List<Object> array = new ArrayList<>();
            space();
            if (peek(']')) {
                pos++;
                return array;
            }
            while (true) {
                array.add(value());
                space();
                if (peek(',')) {
                    pos++;
                } else {
                    expect(']');
                    return array;
                }
            }
        }

        private String string() {
            pos++;
            StringBuilder string = new StringBuilder();
            while (true) {
                if (pos >= json.length()) {
                    throw error("a string doesn't end");
                }
                char c = json.charAt(pos++);
                if (c == '"') {
                    return string.toString();
                } else if (c < 0x20) {
                    throw error("a control character in a string");
                } else if (c != '\\') {
                    string.append(c);
                } else if (pos >= json.length()) {
                    throw error("a string doesn't end");
                } else {
                    string.append(escape());
                }
            }
        }

        /** The character of the escape after a backslash. */
        private char escape() {
            char escaped = json.charAt(pos++);
            return switch (escaped) {
                case '"', '\\', '/' -> escaped;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    if (pos + 4 > json.length()) {
                        throw error("a \\u escape ends early");
                    }
                    try {
                        char unicode = (char) Integer.parseInt(json.substring(pos, pos + 4), 16);
                        pos += 4;
                        yield unicode;
                    } catch (NumberFormatException _) {
                        throw error("not a \\u escape");
                    }
                }
                default -> throw error("the escape \\" + escaped);
            };
        }

        private Long number() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
                pos++;
            }
            if (pos < json.length() && "eE.".indexOf(json.charAt(pos)) >= 0) {
                throw error("only integers");
            }
            try {
                return Long.parseLong(json.substring(start, pos));
            } catch (NumberFormatException _) {
                throw error("not a number: " + json.substring(start, pos));
            }
        }

        private Object literal(String word, Object value) {
            if (!json.startsWith(word, pos)) {
                throw error("unexpected '" + json.charAt(pos) + "'");
            }
            pos += word.length();
            return value;
        }

        private void space() {
            while (pos < json.length() && " \t\r\n".indexOf(json.charAt(pos)) >= 0) {
                pos++;
            }
        }

        private boolean peek(char c) {
            return pos < json.length() && json.charAt(pos) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("not JSON at offset " + pos + ": " + message);
        }
    }
}
