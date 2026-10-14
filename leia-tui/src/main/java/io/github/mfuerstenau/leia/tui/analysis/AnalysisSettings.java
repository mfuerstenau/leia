package io.github.mfuerstenau.leia.tui.analysis;

import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.analysis.AutoStopWords;
import io.github.mfuerstenau.leia.protocol.analysis.FieldAnalyzer;
import io.github.mfuerstenau.leia.protocol.analysis.FieldStopWords;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The analysis that the analysis screen tries and the search screen parses and likes with (ADR
 * 0039): the default analyzer, the fields with an analyzer of their own, and the auto stop words of
 * fields, which belong to the index they were found in. Immutable; each change makes a new one.
 *
 * @param defaultAnalyzer the analyzer of every field without one of its own
 * @param fields the fields with their own analyzer, by name
 * @param stopWords the auto stop words; empty for none
 * @param stopWordsBeforeReopen whether the stop words were found before the index was reopened
 */
public record AnalysisSettings(
        AnalyzerSettings defaultAnalyzer,
        Map<String, AnalyzerSettings> fields,
        Optional<AutoStopWords> stopWords,
        boolean stopWordsBeforeReopen) {

    public AnalysisSettings {
        fields = Collections.unmodifiableMap(new TreeMap<>(fields));
    }

    /** The standard analyzer for every field, and no stop words. */
    public AnalysisSettings() {
        this(new AnalyzerSettings(), Map.of(), Optional.empty(), false);
    }

    /** The analyzer of {@code field}: its own, or the default one; the default one for no field. */
    public AnalyzerSettings analyzer(Optional<String> field) {
        return field.map(fields::get).orElse(defaultAnalyzer);
    }

    /** Whether {@code field} has an analyzer of its own. */
    public boolean hasOwn(String field) {
        return fields.containsKey(field);
    }

    /** With {@code analyzer} for {@code field}, or as the default for no field. */
    public AnalysisSettings withAnalyzer(Optional<String> field, AnalyzerSettings analyzer) {
        if (field.isEmpty()) {
            return new AnalysisSettings(analyzer, fields, stopWords, stopWordsBeforeReopen);
        }
        Map<String, AnalyzerSettings> changed = new TreeMap<>(fields);
        changed.put(field.get(), analyzer);
        return new AnalysisSettings(defaultAnalyzer, changed, stopWords, stopWordsBeforeReopen);
    }

    /** Without the own analyzer of {@code field}, which then has the default one. */
    public AnalysisSettings withoutAnalyzer(String field) {
        Map<String, AnalyzerSettings> changed = new TreeMap<>(fields);
        changed.remove(field);
        return new AnalysisSettings(defaultAnalyzer, changed, stopWords, stopWordsBeforeReopen);
    }

    /** With these stop words, just found; empty for none. */
    public AnalysisSettings withStopWords(Optional<AutoStopWords> found) {
        return new AnalysisSettings(defaultAnalyzer, fields, found, false);
    }

    /**
     * After the index changed: another index drops the stop words, which belong to the old one; a
     * reopen keeps them and marks them as found before it.
     */
    public AnalysisSettings forIndex(boolean reopen) {
        if (stopWords.isEmpty()) {
            return this;
        }
        return reopen
                ? new AnalysisSettings(defaultAnalyzer, fields, stopWords, true)
                : new AnalysisSettings(defaultAnalyzer, fields, Optional.empty(), false);
    }

    /** The stop words of {@code field}; none if it has none. */
    public List<String> stopWords(String field) {
        return stopWords.stream()
                .flatMap(found -> found.fields().stream())
                .filter(words -> words.field().equals(field))
                .findFirst()
                .map(FieldStopWords::words)
                .orElse(List.of());
    }

    /** The fields that have stop words, and those words. */
    private List<FieldStopWords> fieldsWithStopWords() {
        return stopWords.stream()
                .flatMap(found -> found.fields().stream())
                .filter(words -> !words.words().isEmpty())
                .toList();
    }

    /** The analysis as a request sends it. */
    public Analyzer analyzer() {
        Analyzer base = defaultAnalyzer.analyzer();
        return new Analyzer(
                base.standard(),
                base.preset(),
                base.custom(),
                base.shingles(),
                base.limitTokenCount(),
                fields.entrySet().stream()
                        .map(entry -> new FieldAnalyzer(
                                entry.getKey(), entry.getValue().analyzer()))
                        .toList(),
                fieldsWithStopWords());
    }

    /** Whether this is the default: the standard analyzer for every field, without wrappers or stop words. */
    public boolean isStandard() {
        return defaultAnalyzer.isStandard()
                && fields.isEmpty()
                && fieldsWithStopWords().isEmpty();
    }

    /**
     * The analysis on one line: the default analyzer, then how many fields have their own and how many
     * stop words there are, such as {@code EnglishAnalyzer · 2 fields own · 14 auto stop words in 2
     * fields}.
     */
    public String text() {
        StringBuilder text = new StringBuilder(defaultAnalyzer.text());
        if (!fields.isEmpty()) {
            text.append(" · ").append(count(fields.size(), "field")).append(" own");
        }
        text.append(stopWordsText());
        return text.toString();
    }

    /** {@code  · 14 auto stop words in 2 fields}, and whether they are from before a reopen; blank for none. */
    String stopWordsText() {
        List<FieldStopWords> withWords = fieldsWithStopWords();
        if (withWords.isEmpty()) {
            return "";
        }
        int words = withWords.stream().mapToInt(field -> field.words().size()).sum();
        return " · " + count(words, "auto stop word") + " in " + count(withWords.size(), "field")
                + (stopWordsBeforeReopen ? " (from before the reopen)" : "");
    }

    /** The fields with an analyzer of their own, each with it, one per line. */
    List<String> fieldLines() {
        return fields.entrySet().stream()
                .map(entry -> TextFormat.escapeControl(entry.getKey()) + ": "
                        + entry.getValue().text())
                .toList();
    }

    static String count(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    @Override
    public String toString() {
        return text();
    }
}
