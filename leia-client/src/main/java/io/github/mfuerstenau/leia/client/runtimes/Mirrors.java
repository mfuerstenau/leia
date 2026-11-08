package io.github.mfuerstenau.leia.client.runtimes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The mirrors of Maven Central that the runtimes' Lucene jars are downloaded from (ADR 0124): leia's
 * own list, the resource {@value #BUILT_IN}, and the user's own, from {@code -Dleia.download.url},
 * {@code --download-url}, the {@code mirrors} file and the mirror chosen last.
 *
 * <p>A line of the list, or of the {@code mirrors} file, is a URL and an optional name, split at the
 * first whitespace; {@code #} starts a comment, and blank lines are skipped.
 */
public final class Mirrors {

    /** Maven Central, the first of leia's own list. */
    public static final URI CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    /** leia's own list, beside this class. */
    static final String BUILT_IN = "mirrors.txt";

    /** The system property that lets a mirror be {@code http}. */
    public static final String INSECURE = "leia.download.insecure";

    private static final Logger LOG = Logger.getLogger(Mirrors.class.getName());

    private Mirrors() {}

    /**
     * Mirrors read from lines, and the lines that were refused.
     *
     * @param mirrors in the order of the lines
     * @param problems a sentence per refused line, such as {@code line 3: … must be https}
     */
    public record Parsed(List<Mirror> mirrors, List<String> problems) {

        public Parsed {
            mirrors = List.copyOf(mirrors);
            problems = List.copyOf(problems);
        }
    }

    /**
     * The mirrors to try, in order, and what was refused on the way.
     *
     * @param mirrors never empty: leia's own list is always there
     * @param problems a sentence per refused source, such as a bad line of the {@code mirrors} file
     */
    public record Loaded(List<Mirror> mirrors, List<String> problems) {

        public Loaded {
            mirrors = List.copyOf(mirrors);
            problems = List.copyOf(problems);
        }
    }

    /** Why {@code url} can't be a mirror, or empty if it can; {@code http} only if {@code insecure}. */
    public static Optional<String> check(String url, boolean insecure) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.of("'" + url + "' is not a URL: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null) {
            return Optional.of("'" + url + "' is not an absolute URL with a host, such as " + CENTRAL);
        }
        if (scheme.equals("http") && !insecure) {
            return Optional.of("'" + url + "' is http, which a proxy may rewrite; use https, or start leia with -D"
                    + INSECURE + "=true");
        }
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Optional.of("'" + url + "' must be https");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of("'" + url + "' holds credentials, which leia would print; use a mirror without");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return Optional.of("'" + url + "' has a query or a fragment, which a Maven path can't follow");
        }
        return Optional.empty();
    }

    /** {@link #check(String, boolean)}, with {@code http} allowed by {@code -Dleia.download.insecure=true}. */
    public static Optional<String> check(String url) {
        return check(url, Boolean.getBoolean(INSECURE));
    }

    /** The mirrors of {@code lines}, each from {@code origin}. */
    public static Parsed parse(List<String> lines, Mirror.Origin origin, boolean insecure) {
        List<Mirror> mirrors = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int hash = line.indexOf('#');
            String content = (hash < 0 ? line : line.substring(0, hash)).strip();
            if (content.isEmpty()) {
                continue;
            }
            String[] parts = content.split("\\s+", 2);
            Optional<String> problem = check(parts[0], insecure);
            if (problem.isPresent()) {
                problems.add("line " + (i + 1) + ": " + problem.get());
                continue;
            }
            URI uri = URI.create(parts[0]);
            mirrors.add(parts.length > 1 ? new Mirror(uri, parts[1].strip(), origin) : Mirror.of(uri, origin));
        }
        return new Parsed(mirrors, problems);
    }

    /** leia's own list. */
    public static List<Mirror> builtIn() {
        try (InputStream in = Mirrors.class.getResourceAsStream(BUILT_IN)) {
            if (in == null) {
                throw new IllegalStateException("leia.jar lacks " + BUILT_IN);
            }
            List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .toList();
            Parsed parsed = parse(lines, Mirror.Origin.BUILT_IN, false);
            if (!parsed.problems().isEmpty()) {
                throw new IllegalStateException(BUILT_IN + ": " + parsed.problems());
            }
            return parsed.mirrors();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The mirrors to try, in order, each once: {@code -Dleia.download.url}, the URLs of {@code
     * --download-url}, the mirror chosen last ({@code saved}, a file with its URL), the lines of the
     * {@code mirrors} file, then leia's own list. A mirror known twice keeps its first place, and
     * the name of leia's own list if it is on it.
     *
     * @param property {@code -Dleia.download.url}, or null
     * @param options the URLs of {@code --download-url}, which {@link io.github.mfuerstenau.leia.tui}'s
     *     command line has checked
     * @param saved the file with the mirror chosen last
     * @param mirrorsFile the user's {@code mirrors} file
     */
    public static Loaded resolve(
            String property, List<String> options, Optional<Path> saved, Optional<Path> mirrorsFile, boolean insecure) {
        List<Mirror> all = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (property != null && !property.isBlank()) {
            check(property.strip(), insecure)
                    .ifPresentOrElse(
                            problem -> problems.add("-Dleia.download.url: " + problem),
                            () -> all.add(Mirror.of(URI.create(property.strip()), Mirror.Origin.PROPERTY)));
        }
        for (String option : options) {
            check(option, insecure)
                    .ifPresentOrElse(
                            problem -> problems.add("--download-url: " + problem),
                            () -> all.add(Mirror.of(URI.create(option), Mirror.Origin.OPTION)));
        }
        saved.filter(Files::isRegularFile).ifPresent(file -> {
            Parsed parsed = read(file, Mirror.Origin.SAVED, insecure, problems);
            parsed.mirrors().stream().findFirst().ifPresent(all::add);
        });
        mirrorsFile
                .filter(Files::isRegularFile)
                .ifPresent(file -> all.addAll(
                        read(file, Mirror.Origin.FILE, insecure, problems).mirrors()));
        List<Mirror> builtIn = builtIn();
        all.addAll(builtIn);
        return new Loaded(distinct(all, builtIn), problems);
    }

    /** {@link #resolve}, with {@code http} allowed by {@code -Dleia.download.insecure=true}. */
    public static Loaded resolve(
            String property, List<String> options, Optional<Path> saved, Optional<Path> mirrorsFile) {
        return resolve(property, options, saved, mirrorsFile, Boolean.getBoolean(INSECURE));
    }

    /** {@code mirrors} with {@code first} first, and without its other place in the list. */
    public static List<Mirror> withFirst(List<Mirror> mirrors, Mirror first) {
        List<Mirror> ordered = new ArrayList<>();
        ordered.add(first);
        mirrors.stream().filter(mirror -> !mirror.uri().equals(first.uri())).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    /**
     * Writes {@code mirror} as the one chosen last, its URL and name on one line, through a
     * temporary file. A failed write is logged and otherwise ignored: the choice is a convenience.
     */
    public static void save(Path file, Mirror mirror) {
        Path temporary = file.resolveSibling(
                file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(temporary, mirror.uri() + " " + mirror.name() + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warning(() -> "Cannot save the mirror to " + file + ": " + e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException _) {
                // Nothing else to do: the choice is a convenience.
            }
        }
    }

    private static Parsed read(Path file, Mirror.Origin origin, boolean insecure, List<String> problems) {
        try {
            Parsed parsed = parse(Files.readAllLines(file, StandardCharsets.UTF_8), origin, insecure);
            parsed.problems().forEach(problem -> problems.add(file + ", " + problem));
            return parsed;
        } catch (IOException e) {
            problems.add("cannot read " + file + ": " + e.getMessage());
            return new Parsed(List.of(), List.of());
        }
    }

    /** Each mirror once, by URI, in its first place; leia's own name wins over a host name. */
    private static List<Mirror> distinct(List<Mirror> all, List<Mirror> builtIn) {
        Map<URI, Mirror> names = new LinkedHashMap<>();
        builtIn.forEach(mirror -> names.put(mirror.uri(), mirror));
        Map<URI, Mirror> seen = new LinkedHashMap<>();
        for (Mirror mirror : all) {
            if (seen.containsKey(mirror.uri())) {
                continue;
            }
            Mirror known = names.get(mirror.uri());
            boolean unnamed = mirror.name().equals(mirror.uri().getHost());
            seen.put(
                    mirror.uri(),
                    known != null && unnamed && mirror.origin() != Mirror.Origin.BUILT_IN
                            ? new Mirror(mirror.uri(), known.name(), mirror.origin())
                            : mirror);
        }
        return List.copyOf(seen.values());
    }
}
