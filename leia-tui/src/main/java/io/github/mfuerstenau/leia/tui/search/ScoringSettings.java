package io.github.mfuerstenau.leia.tui.search;

import io.github.mfuerstenau.leia.protocol.index.DocValuesType;
import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.search.Bm25Similarity;
import io.github.mfuerstenau.leia.protocol.search.BooleanSimilarity;
import io.github.mfuerstenau.leia.protocol.search.ClassicSimilarity;
import io.github.mfuerstenau.leia.protocol.search.DocSort;
import io.github.mfuerstenau.leia.protocol.search.FieldSort;
import io.github.mfuerstenau.leia.protocol.search.ScoreSort;
import io.github.mfuerstenau.leia.protocol.search.Similarity;
import io.github.mfuerstenau.leia.protocol.search.SortKey;
import io.github.mfuerstenau.leia.protocol.search.SortMissing;
import io.github.mfuerstenau.leia.protocol.search.SortSelector;
import io.github.mfuerstenau.leia.protocol.search.SortType;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The similarity and the sort of the search screen (ADR 0012, ADR 0025): BM25 with its {@code k1}
 * and {@code b}, classic TF-IDF or boolean, and up to two sort levels, each by the score, the
 * document id or a field with doc values, with a selector for multi-valued fields and a place for
 * documents without a value. {@link ScoringForm} edits a copy.
 */
public final class ScoringSettings {

    /** How many sort levels the popup offers, as Luke does. */
    static final int MAX_LEVELS = 2;

    /** BM25's defaults in hundredths. */
    static final int DEFAULT_K1 = 120;

    static final int DEFAULT_B = 75;

    enum SimilarityKind {
        BM25,
        CLASSIC,
        BOOLEAN
    }

    /**
     * A field that can be sorted by: one with doc values other than binary.
     *
     * @param docValues numeric, sorted numeric, sorted or sorted set
     */
    record SortableField(String name, DocValuesType docValues) {

        /** Numeric or sorted numeric doc values; otherwise sorted or sorted set. */
        boolean numeric() {
            return docValues == DocValuesType.NUMERIC || docValues == DocValuesType.SORTED_NUMERIC;
        }

        /** The selectors its doc values take: only {@code MIN} for single-valued ones. */
        List<SortSelector> selectors() {
            return switch (docValues) {
                case SORTED_NUMERIC -> List.of(SortSelector.MIN, SortSelector.MAX);
                case SORTED_SET -> List.of(SortSelector.values());
                default -> List.of(SortSelector.MIN);
            };
        }
    }

    /** What a sort level sorts by. */
    sealed interface Target {
        record Score() implements Target {}

        record Doc() implements Target {}

        /** A field of {@link ScoringSettings#fields}, by its index. */
        record Field(int index) implements Target {}
    }

    /** One sort level: what it sorts by and, for a field, how to read it. */
    record SortLevel(Target target, SortType sortType, boolean reverse, SortSelector selector, SortMissing missing) {}

    SimilarityKind kind = SimilarityKind.BM25;

    /** BM25's {@code k1} and {@code b} in hundredths, so that the popup edits them as whole numbers. */
    int k1 = DEFAULT_K1;

    int b = DEFAULT_B;
    final List<SortableField> fields;

    /** The sort levels; null for none. The second level only with the first. */
    final SortLevel[] levels = new SortLevel[MAX_LEVELS];

    private ScoringSettings(List<SortableField> fields) {
        this.fields = fields;
    }

    /** BM25 with Lucene's defaults, sorted by score. */
    public static ScoringSettings of(List<IndexField> fields) {
        return new ScoringSettings(fields.stream()
                .filter(field ->
                        field.docValuesType() != DocValuesType.NONE && field.docValuesType() != DocValuesType.BINARY)
                .map(field -> new SortableField(field.name(), field.docValuesType()))
                .toList());
    }

    /** A copy that can be changed without changing this one. */
    ScoringSettings copy() {
        ScoringSettings copy = new ScoringSettings(fields);
        copy.kind = kind;
        copy.k1 = k1;
        copy.b = b;
        System.arraycopy(levels, 0, copy.levels, 0, MAX_LEVELS);
        return copy;
    }

    public Similarity similarity() {
        return switch (kind) {
            case BM25 ->
                new Similarity(
                        Optional.of(new Bm25Similarity(k1 / 100f, b / 100f)), Optional.empty(), Optional.empty());
            case CLASSIC -> new Similarity(Optional.empty(), Optional.of(new ClassicSimilarity()), Optional.empty());
            case BOOLEAN -> new Similarity(Optional.empty(), Optional.empty(), Optional.of(new BooleanSimilarity()));
        };
    }

    /** The sort keys, as {@code startSearch} takes them; empty to sort by score. */
    public List<SortKey> sortKeys() {
        List<SortKey> keys = new ArrayList<>();
        for (SortLevel level : levels) {
            if (level == null) {
                continue;
            }
            keys.add(
                    switch (level.target()) {
                        case Target.Score _ ->
                            new SortKey(
                                    Optional.empty(), Optional.of(new ScoreSort(level.reverse())), Optional.empty());
                        case Target.Doc _ ->
                            new SortKey(Optional.empty(), Optional.empty(), Optional.of(new DocSort(level.reverse())));
                        case Target.Field field ->
                            new SortKey(
                                    Optional.of(new FieldSort(
                                            fields.get(field.index()).name(),
                                            level.sortType(),
                                            level.reverse(),
                                            level.selector(),
                                            level.missing())),
                                    Optional.empty(),
                                    Optional.empty());
                    });
        }
        return keys;
    }

    /** The similarity, unless it is Lucene's default, for the details panel. */
    public Optional<String> similarityText() {
        return switch (kind) {
            case BM25 ->
                k1 == DEFAULT_K1 && b == DEFAULT_B
                        ? Optional.empty()
                        : Optional.of("BM25 k1 " + hundredths(k1) + " b " + hundredths(b));
            case CLASSIC -> Optional.of("classic TF-IDF");
            case BOOLEAN -> Optional.of("boolean");
        };
    }

    /**
     * The sort levels, such as {@code tags MAX last ↑ · price DOUBLE ↓ · score}; empty to sort by
     * score. A string sort and the guessed {@code LONG} leave the type out, {@code MIN} and Lucene's
     * missing values are left out too. The score comes best first, or {@code ↑} for the worst first.
     */
    public Optional<String> sortText() {
        List<String> texts = new ArrayList<>();
        for (SortLevel level : levels) {
            if (level == null) {
                continue;
            }
            String arrow = level.reverse() ? "↓" : "↑";
            switch (level.target()) {
                case Target.Score _ -> texts.add(level.reverse() ? "score ↑" : "score");
                case Target.Doc _ -> texts.add("doc " + arrow);
                case Target.Field field -> texts.add(fieldText(level, field, arrow));
            }
        }
        return texts.isEmpty() ? Optional.empty() : Optional.of(String.join(" · ", texts));
    }

    /** A level that sorts by a field: its name, and what isn't the default of its type, selector and missing values. */
    private String fieldText(SortLevel level, Target.Field field, String arrow) {
        List<String> parts = new ArrayList<>();
        parts.add(TextFormat.escapeControl(fields.get(field.index()).name()));
        if (level.sortType() != SortType.STRING && level.sortType() != SortType.LONG) {
            parts.add(level.sortType().name());
        }
        if (level.selector() != SortSelector.MIN) {
            parts.add(level.selector().name());
        }
        switch (level.missing()) {
            case FIRST -> parts.add("first");
            case LAST -> parts.add("last");
            case DEFAULT -> {}
        }
        parts.add(arrow);
        return String.join(" ", parts);
    }

    /** Hundredths as a decimal with two places: {@code 120} is {@code 1.20}. */
    static String hundredths(int n) {
        return n / 100 + "." + String.format("%02d", n % 100);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScoringSettings that
                && kind == that.kind
                && k1 == that.k1
                && b == that.b
                && fields.equals(that.fields)
                && Arrays.equals(levels, that.levels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, k1, b, fields, Arrays.hashCode(levels));
    }
}
