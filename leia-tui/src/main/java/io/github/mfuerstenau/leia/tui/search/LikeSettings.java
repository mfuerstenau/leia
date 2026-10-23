package io.github.mfuerstenau.leia.tui.search;

import io.github.mfuerstenau.leia.protocol.analysis.Analyzer;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.search.MoreLikeThisQuery;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * The More-Like-This settings of the search screen (ADR 0013, ADR 0028): the fields to take terms
 * from, Lucene's term and document frequency limits, how many terms the query has, whether they are
 * boosted, and which words are left out by length or as stop words. {@link LikeForm} edits a copy.
 */
public final class LikeSettings {

    /** Lucene's defaults. */
    static final int DEFAULT_MIN_TERM_FREQ = 2;

    static final int DEFAULT_MIN_DOC_FREQ = 5;
    static final int DEFAULT_MAX_QUERY_TERMS = 25;

    /** Where the terms of a More-Like-This query come from. */
    public sealed interface LikeSource {
        /** A document of the index, by its id. */
        record Doc(int docId) implements LikeSource {}

        /** A text, analyzed for each field. */
        record Text(String text) implements LikeSource {}
    }

    /** The indexed fields, in field number order. */
    final List<String> names;

    final List<Boolean> chosen;
    int minTermFreq = DEFAULT_MIN_TERM_FREQ;
    int minDocFreq = DEFAULT_MIN_DOC_FREQ;

    /** 0 for no limit. */
    int maxDocFreq;

    int maxQueryTerms = DEFAULT_MAX_QUERY_TERMS;
    boolean boost;

    /** 0 for no limit. */
    int minWordLen;

    /** 0 for no limit. */
    int maxWordLen;

    List<String> stopWords = List.of();

    private LikeSettings(List<String> names, List<Boolean> chosen) {
        this.names = List.copyOf(names);
        this.chosen = new ArrayList<>(chosen);
    }

    /**
     * Lucene's defaults, with the fields that have positions, which are most likely text; the first
     * indexed field if none has.
     */
    public static LikeSettings of(List<IndexField> fields) {
        List<IndexField> indexed = fields.stream()
                .filter(field -> field.indexOptions() != IndexOptions.NONE)
                .toList();
        List<Boolean> chosen = new ArrayList<>(indexed.stream()
                .map(field -> field.indexOptions() == IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
                        || field.indexOptions() == IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)
                .toList());
        if (!chosen.contains(true) && !chosen.isEmpty()) {
            chosen.set(0, true);
        }
        return new LikeSettings(indexed.stream().map(IndexField::name).toList(), chosen);
    }

    /** A copy that can be changed without changing this one. */
    LikeSettings copy() {
        LikeSettings copy = new LikeSettings(names, chosen);
        copy.minTermFreq = minTermFreq;
        copy.minDocFreq = minDocFreq;
        copy.maxDocFreq = maxDocFreq;
        copy.maxQueryTerms = maxQueryTerms;
        copy.boost = boost;
        copy.minWordLen = minWordLen;
        copy.maxWordLen = maxWordLen;
        copy.stopWords = stopWords;
        return copy;
    }

    private List<String> fields() {
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (chosen.get(i)) {
                fields.add(names.get(i));
            }
        }
        return fields;
    }

    /**
     * The query for documents like {@code source}, whose stored text or text {@code analyzer}
     * analyzes; empty without a field to take terms from.
     */
    public Optional<MoreLikeThisQuery> query(LikeSource source, Analyzer analyzer) {
        List<String> fields = fields();
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MoreLikeThisQuery(
                source instanceof LikeSource.Doc doc ? OptionalInt.of(doc.docId()) : OptionalInt.empty(),
                source instanceof LikeSource.Text text ? Optional.of(text.text()) : Optional.empty(),
                fields,
                minTermFreq,
                minDocFreq,
                maxDocFreq > 0 ? OptionalInt.of(maxDocFreq) : OptionalInt.empty(),
                maxQueryTerms,
                boost,
                analyzer,
                minWordLen,
                maxWordLen > 0 ? OptionalInt.of(maxWordLen) : OptionalInt.empty(),
                stopWords));
    }

    /** The chosen fields, such as {@code fields title · body}, or how many of them there are. */
    public String fieldsText() {
        List<String> fields = fields();
        String shown = fields.stream().map(TextFormat::escapeControl).collect(Collectors.joining(" · "));
        return switch (fields.size()) {
            case 0 -> "no field";
            case 1 -> "field " + shown;
            case 2, 3 -> "fields " + shown;
            default -> fields.size() + " fields";
        };
    }

    /**
     * The limits, such as {@code term freq ≥ 2 · doc freq 5 to 100 · 25 terms · boost · words 3 to 20
     * · stop words a with}.
     */
    public String limitsText() {
        String docFreq = maxDocFreq > 0 ? "doc freq " + minDocFreq + " to " + maxDocFreq : "doc freq ≥ " + minDocFreq;
        StringBuilder text =
                new StringBuilder("term freq ≥ " + minTermFreq + " · " + docFreq + " · " + maxQueryTerms + " terms");
        if (boost) {
            text.append(" · boost");
        }
        if (minWordLen > 0 && maxWordLen > 0) {
            text.append(" · words ").append(minWordLen).append(" to ").append(maxWordLen);
        } else if (minWordLen > 0) {
            text.append(" · words ≥ ").append(minWordLen);
        } else if (maxWordLen > 0) {
            text.append(" · words ≤ ").append(maxWordLen);
        }
        if (!stopWords.isEmpty()) {
            text.append(" · ").append(stopWordsText(stopWords));
        }
        return text.toString();
    }

    /** The stop words, such as {@code stop words a with}, or how many there are. */
    static String stopWordsText(List<String> words) {
        if (words.size() <= 4) {
            return "stop words " + words.stream().map(TextFormat::escapeControl).collect(Collectors.joining(" "));
        }
        return words.size() + " stop words";
    }

    /** The stop words typed in a line, split on whitespace and commas, each once. */
    static List<String> splitStopWords(String line) {
        List<String> words = new ArrayList<>();
        for (String word : line.split("[\\s\\p{Z},]+")) {
            if (!word.isEmpty() && !words.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LikeSettings that
                && names.equals(that.names)
                && chosen.equals(that.chosen)
                && minTermFreq == that.minTermFreq
                && minDocFreq == that.minDocFreq
                && maxDocFreq == that.maxDocFreq
                && maxQueryTerms == that.maxQueryTerms
                && boost == that.boost
                && minWordLen == that.minWordLen
                && maxWordLen == that.maxWordLen
                && stopWords.equals(that.stopWords);
    }

    @Override
    public int hashCode() {
        return Objects.hash(names, chosen, minTermFreq, minDocFreq, maxDocFreq, maxQueryTerms, stopWords);
    }

    @Override
    public String toString() {
        return fieldsText() + " · " + limitsText();
    }
}
