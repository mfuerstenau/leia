package io.github.mfuerstenau.leia.tui.search;

import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.protocol.index.IndexOptions;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.FieldDateResolution;
import io.github.mfuerstenau.leia.protocol.search.ParserOptions;
import io.github.mfuerstenau.leia.protocol.search.PointField;
import io.github.mfuerstenau.leia.protocol.search.PointType;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The query parser options of the search screen (ADR 0011, ADR 0026, ADR 0035): the options both
 * parsers share, the classic parser's own, how range bounds are read as dates, also per field, and
 * the type of each point field for the standard parser. {@link OptionsForm} edits a copy.
 */
public final class ParserSettings {

    /** The edit distance of fuzzy terms that give none: Lucene's largest. */
    static final int MAX_FUZZY_EDITS = 2;

    /**
     * A point field and the type its values are read as; a null type leaves the field to text.
     *
     * @param bytes 4 or 8
     */
    record PointSetting(String field, int bytes, @Nullable PointType pointType) {}

    boolean allowLeadingWildcard;
    int phraseSlop;
    int fuzzyPrefixLength;
    boolean enablePositionIncrements = true;

    @Nullable
    DateResolution dateResolution;

    @Nullable
    String locale;

    @Nullable
    String timeZone;

    /** The resolutions of single fields, which win over {@link #dateResolution}. */
    final Map<String, DateResolution> fieldDateResolutions = new HashMap<>();

    /** Classic only. */
    int fuzzyMaxEdits = MAX_FUZZY_EDITS;

    /** Classic only. */
    boolean splitOnWhitespace;

    /** Classic only; needs {@link #splitOnWhitespace}. */
    boolean autoGeneratePhraseQueries;

    /** The indexed fields, in the index's order: the ones a date resolution of their own fits. */
    final List<String> dateFields;

    /** The one-dimensional point fields of the index, for the standard parser. */
    final List<PointSetting> points;

    private ParserSettings(List<String> dateFields, List<PointSetting> points) {
        this.dateFields = List.copyOf(dateFields);
        this.points = new ArrayList<>(points);
    }

    /**
     * Lucene's defaults. Every one-dimensional point field of 4 or 8 bytes is guessed to hold ints or
     * longs, since the index doesn't say (ADR 0011).
     */
    public static ParserSettings of(List<IndexField> fields) {
        List<String> dateFields = new ArrayList<>();
        List<PointSetting> points = new ArrayList<>();
        for (IndexField field : fields) {
            if (field.indexOptions() != IndexOptions.NONE) {
                dateFields.add(field.name());
            }
            if (field.pointDimensionCount() != 1) {
                continue;
            }
            switch (field.pointNumBytes()) {
                case 4 -> points.add(new PointSetting(field.name(), 4, PointType.INT));
                case 8 -> points.add(new PointSetting(field.name(), 8, PointType.LONG));
                default -> {}
            }
        }
        return new ParserSettings(dateFields, points);
    }

    /** A copy that can be changed without changing this one. */
    ParserSettings copy() {
        ParserSettings copy = new ParserSettings(dateFields, points);
        copy.allowLeadingWildcard = allowLeadingWildcard;
        copy.phraseSlop = phraseSlop;
        copy.fuzzyPrefixLength = fuzzyPrefixLength;
        copy.enablePositionIncrements = enablePositionIncrements;
        copy.dateResolution = dateResolution;
        copy.locale = locale;
        copy.timeZone = timeZone;
        copy.fieldDateResolutions.putAll(fieldDateResolutions);
        copy.fuzzyMaxEdits = fuzzyMaxEdits;
        copy.splitOnWhitespace = splitOnWhitespace;
        copy.autoGeneratePhraseQueries = autoGeneratePhraseQueries;
        return copy;
    }

    /** The options both parsers take, as {@code startSearch} sends them. */
    public ParserOptions options() {
        return new ParserOptions(
                allowLeadingWildcard,
                phraseSlop,
                fuzzyPrefixLength,
                enablePositionIncrements,
                Optional.ofNullable(dateResolution),
                dateFields.stream()
                        .filter(fieldDateResolutions::containsKey)
                        .map(field -> new FieldDateResolution(field, fieldDateResolutions.get(field)))
                        .toList(),
                Optional.ofNullable(locale),
                Optional.ofNullable(timeZone));
    }

    public int fuzzyMaxEdits() {
        return fuzzyMaxEdits;
    }

    public boolean splitOnWhitespace() {
        return splitOnWhitespace;
    }

    public boolean autoGeneratePhraseQueries() {
        return autoGeneratePhraseQueries;
    }

    /** The point fields with a type, as {@code startSearch} takes them. */
    public List<PointField> pointFields() {
        return points.stream()
                .filter(point -> point.pointType() != null)
                .map(point -> new PointField(point.field(), point.pointType()))
                .toList();
    }

    /** The options that differ from Lucene's defaults, for the details panel; the classic ones only for the classic parser. */
    public List<String> changed(boolean classic) {
        List<String> changed = new ArrayList<>();
        if (allowLeadingWildcard) {
            changed.add("leading wildcards");
        }
        if (phraseSlop != 0) {
            changed.add("phrase slop " + phraseSlop);
        }
        if (classic && fuzzyMaxEdits != MAX_FUZZY_EDITS) {
            changed.add("fuzzy max edits " + fuzzyMaxEdits);
        }
        if (fuzzyPrefixLength != 0) {
            changed.add("fuzzy prefix " + fuzzyPrefixLength);
        }
        if (!enablePositionIncrements) {
            changed.add("no position increments");
        }
        if (classic && splitOnWhitespace) {
            changed.add("split on whitespace");
        }
        if (classic && autoGeneratePhraseQueries) {
            changed.add("auto phrases");
        }
        if (dateResolution != null) {
            changed.add("dates " + dateResolution.name());
        }
        options()
                .fieldDateResolutions()
                .forEach(field -> changed.add("dates " + TextFormat.escapeControl(field.field()) + ":"
                        + field.resolution().name()));
        if (locale != null) {
            changed.add("locale " + TextFormat.escapeControl(locale));
        }
        if (timeZone != null) {
            changed.add("zone " + TextFormat.escapeControl(timeZone));
        }
        return changed;
    }

    /** The point fields and their types, for the details panel. */
    public String pointsText() {
        if (points.isEmpty()) {
            return "no one-dimensional point fields";
        }
        return points.stream()
                .map(point -> TextFormat.escapeControl(point.field()) + " " + typeName(point.pointType()))
                .collect(Collectors.joining(" · "));
    }

    static String typeName(@Nullable PointType pointType) {
        return pointType == null ? "text" : pointType.name();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ParserSettings that
                && options().equals(that.options())
                && fuzzyMaxEdits == that.fuzzyMaxEdits
                && splitOnWhitespace == that.splitOnWhitespace
                && autoGeneratePhraseQueries == that.autoGeneratePhraseQueries
                && points.equals(that.points);
    }

    @Override
    public int hashCode() {
        return Objects.hash(options(), fuzzyMaxEdits, splitOnWhitespace, autoGeneratePhraseQueries, points);
    }
}
