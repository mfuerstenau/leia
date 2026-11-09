package io.github.mfuerstenau.leia.tui.prompt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Completes typed paths, and expands {@code ~} in them: typed paths have no shell to do it. */
public final class PathCompletion {

    /**
     * What a completion came to.
     *
     * @param input the completed input
     * @param base the directory part of the input that was completed, which the candidates follow
     * @param candidates the names that matched, if more than one did
     */
    public record Completion(String input, String base, List<Candidate> candidates) {

        public Completion {
            candidates = List.copyOf(candidates);
        }
    }

    /** A directory, or a file that the caller accepts, that matched the last component. */
    public record Candidate(String name, boolean directory) {

        /** What the list shows and inserts: a directory with its separator. */
        public String text() {
            return directory ? name + File.separator : name;
        }
    }

    private PathCompletion() {}

    /** Whether {@code c} separates path components: {@code /} works on Windows too. */
    public static boolean isSeparator(char c) {
        return c == '/' || c == File.separatorChar;
    }

    /**
     * Where the path component before the cursor starts, for Ctrl+W. Separators right before the
     * cursor belong to that component, so repeated Ctrl+W walks up the path.
     */
    public static int componentStart(String before) {
        int end = before.length();
        while (end > 0 && isSeparator(before.charAt(end - 1))) {
            end--;
        }
        for (int i = end - 1; i >= 0; i--) {
            if (isSeparator(before.charAt(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Replaces a leading {@code ~} with {@code home}. */
    public static String expandHome(String path, @Nullable Path home) {
        if (!path.startsWith("~") || home == null) {
            return path;
        }
        String rest = path.substring(1);
        if (rest.isEmpty() || isSeparator(rest.charAt(0))) {
            return home + rest;
        }
        return path;
    }

    /**
     * Completes the last component of {@code input} to the directories that start with it. One
     * match is completed with a trailing separator; several are completed to their common prefix
     * and returned as candidates. Hidden directories only match a component that starts with
     * {@code .}.
     */
    public static Completion complete(String input, @Nullable Path home) {
        return completeWithFiles(input, home, name -> false);
    }

    /**
     * Completes as {@link #complete} does, and to the files whose name {@code file} accepts too. A
     * file that is the one match is completed without a separator.
     */
    public static Completion completeWithFiles(String input, @Nullable Path home, Predicate<String> file) {
        String dir = base(input);
        if (input.equals("~") && home != null) {
            return new Completion("~" + File.separator, dir, List.of());
        }
        List<Candidate> matches = matches(input, home, file);
        if (matches.isEmpty()) {
            return new Completion(input, dir, List.of());
        }
        if (matches.size() == 1) {
            return new Completion(dir + matches.getFirst().text(), dir, List.of());
        }
        String common = matches.getFirst().name();
        for (Candidate match : matches) {
            common = commonPrefix(common, match.name());
        }
        return new Completion(dir + common, dir, matches);
    }

    /** The directory part of {@code input}: everything up to and including its last separator. */
    public static String base(String input) {
        return input.substring(0, lastSeparator(input) + 1);
    }

    /**
     * The directories, and the files that {@code file} accepts, that start with the last component
     * of {@code input}, sorted by name. Hidden ones only match a component that starts with {@code
     * .}. Empty if the directory can't be listed.
     */
    public static List<Candidate> matches(String input, @Nullable Path home, Predicate<String> file) {
        String dir = base(input);
        String prefix = input.substring(dir.length());
        try {
            Path listed = Path.of(dir.isEmpty() ? "." : expandHome(dir, home));
            try (Stream<Path> entries = Files.list(listed)) {
                return entries.map(entry -> candidate(entry, file))
                        .flatMap(Optional::stream)
                        .filter(match -> match.name().startsWith(prefix)
                                && (prefix.startsWith(".") || !match.name().startsWith(".")))
                        .sorted(Comparator.comparing(Candidate::name))
                        .toList();
            }
        } catch (IOException | InvalidPathException _) {
            return List.of();
        }
    }

    private static int lastSeparator(String input) {
        for (int i = input.length() - 1; i >= 0; i--) {
            if (isSeparator(input.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** A directory, or a file that {@code file} accepts. {@link Files#isDirectory} follows links. */
    private static Optional<Candidate> candidate(Path entry, Predicate<String> file) {
        Path fileName = entry.getFileName();
        if (fileName == null) {
            return Optional.empty();
        }
        String name = fileName.toString();
        if (Files.isDirectory(entry)) {
            return Optional.of(new Candidate(name, true));
        }
        if (Files.isRegularFile(entry) && file.test(name)) {
            return Optional.of(new Candidate(name, false));
        }
        return Optional.empty();
    }

    /** The longest common prefix, by code points. */
    private static String commonPrefix(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.codePointAt(i) == b.codePointAt(i)) {
            i += Character.charCount(a.codePointAt(i));
        }
        return a.substring(0, i);
    }
}
