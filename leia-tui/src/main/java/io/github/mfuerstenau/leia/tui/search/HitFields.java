package io.github.mfuerstenau.leia.tui.search;

import io.github.mfuerstenau.leia.protocol.index.IndexField;
import io.github.mfuerstenau.leia.tui.text.TextFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The stored fields that the search screen loads per hit (ADR 0010, ADR 0012). The index doesn't
 * record which fields are stored, so every field is offered; {@link FieldsForm} chooses them.
 *
 * @param names every field, in field number order
 * @param shown whether each field is shown
 */
public record HitFields(List<String> names, List<Boolean> shown) {

    public HitFields {
        names = List.copyOf(names);
        shown = List.copyOf(shown);
    }

    /** Every field, in field number order. */
    public static HitFields of(List<IndexField> fields) {
        return new HitFields(fields.stream().map(IndexField::name).toList(), Collections.nCopies(fields.size(), true));
    }

    /** The fields to load, as {@code startSearch} takes them: empty while every field is shown. */
    public Optional<List<String>> param() {
        if (!shown.contains(false)) {
            return Optional.empty();
        }
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (shown.get(i).booleanValue()) {
                fields.add(names.get(i));
            }
        }
        return Optional.of(fields);
    }

    /** How many fields the hits show, unless it is all of them, for the details panel. */
    public Optional<String> text() {
        long count = shown.stream().filter(Boolean::booleanValue).count();
        if (count == names.size()) {
            return Optional.empty();
        }
        if (count == 0) {
            return Optional.of("none");
        }
        if (count <= 3) {
            return Optional.of(param().orElse(List.of()).stream()
                    .map(TextFormat::escapeControl)
                    .collect(Collectors.joining(" · ")));
        }
        return Optional.of(count + " of " + names.size());
    }
}
