package io.github.mfuerstenau.leia.runtime.support;

import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec.StemDictionary;
import io.github.mfuerstenau.leia.runtime.Search.AnalyzerSpec.WordSet;
import io.github.mfuerstenau.leia.runtime.Search.InvalidAnalyzerException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The word sets and stem override dictionaries of a preset analyzer, read from their files as
 * leia-core reads them on Lucene 10 (ADR 0067), with leia-core's messages.
 */
public final class WordFiles {

    private WordFiles() {}

    /**
     * The words of {@code set}: given, or read from its file as Lucene's {@code
     * WordlistLoader.getWordSet} reads it, each line trimmed and blank lines skipped.
     *
     * @param what names the set in a message, such as {@code stop words}
     * @throws InvalidAnalyzerException if the file can't be read or isn't UTF-8
     */
    public static List<String> words(String what, WordSet set) throws InvalidAnalyzerException {
        return switch (set) {
            case WordSet.Words words -> words.words();
            case WordSet.File file -> {
                List<String> words = new ArrayList<>();
                for (String line : lines(what, file.path())) {
                    String word = line.trim();
                    if (!word.isEmpty()) {
                        words.add(word);
                    }
                }
                yield words;
            }
        };
    }

    /**
     * The words and their stems of {@code dictionary}: given, or read from its file as {@code
     * StemmerOverrideFilterFactory} reads it, {@code word<TAB>stem} per line, blank lines and lines
     * starting with {@code #} skipped.
     *
     * @throws InvalidAnalyzerException if the file can't be read, isn't UTF-8 or has a line that isn't
     *     {@code word<TAB>stem}, or a word or stem is empty
     */
    public static Map<String, String> stemOverrides(StemDictionary dictionary) throws InvalidAnalyzerException {
        String what = "stem overrides";
        Map<String, String> entries = switch (dictionary) {
            case StemDictionary.Entries given -> given.entries();
            case StemDictionary.File file -> {
                Map<String, String> lines = new LinkedHashMap<>();
                for (String line : lines(what, file.path())) {
                    String trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    String[] entry = trimmed.split("\t", 2);
                    if (entry.length != 2) {
                        throw new InvalidAnalyzerException(
                                what + ": " + file.path() + ": '" + trimmed + "' is not word<TAB>stem", null);
                    }
                    lines.put(entry[0], entry[1]);
                }
                yield lines;
            }
        };
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getValue().isEmpty()) {
                throw new InvalidAnalyzerException(
                        what + ": an empty word or stem in '" + entry.getKey() + "' → '" + entry.getValue() + "'",
                        null);
            }
        }
        return entries;
    }

    /** The lines of the UTF-8 file at {@code path}; {@code what} names it in a message. */
    private static List<String> lines(String what, Path path) throws InvalidAnalyzerException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> lines = new ArrayList<>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lines.add(line);
            }
            return lines;
        } catch (IOException | UncheckedIOException | SecurityException e) {
            throw unreadable(what, path, e);
        }
    }

    private static InvalidAnalyzerException unreadable(String what, Path path, Throwable e) {
        return switch (e) {
            case UncheckedIOException unchecked -> unreadable(what, path, unchecked.getCause());
            case NoSuchFileException _ -> new InvalidAnalyzerException(what + ": no file " + path, e);
            case AccessDeniedException _ -> new InvalidAnalyzerException(what + ": cannot read " + path, e);
            case CharacterCodingException _ -> new InvalidAnalyzerException(what + ": " + path + " is not UTF-8", e);
            default -> new InvalidAnalyzerException(what + ": " + path + ": " + Failures.message(e), e);
        };
    }
}
