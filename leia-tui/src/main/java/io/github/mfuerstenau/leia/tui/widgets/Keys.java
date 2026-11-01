package io.github.mfuerstenau.leia.tui.widgets;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The keys of a screen, in the groups that every screen shares (ADR 0120), so that the same
 * function sits in the same group everywhere. An entry is in the footer, or only in the key list
 * that {@code ?} opens.
 *
 * @param sections the groups in the order shown; one group may come twice only through {@link #plus}
 */
public record Keys(List<Section> sections) {

    /** The groups, in the order a screen shows them. */
    public enum Group {
        MOVE("Move"),
        FIND("Find"),
        SHOW("Show"),
        SORT("Sort"),
        DO("Do"),
        GO_TO("Go to"),
        APP("App");

        private final String title;

        Group(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /**
     * One key, such as {@code s/S} with {@code sort}.
     *
     * @param footer whether the footer shows it; the key list shows every key
     */
    public record Key(String key, String does, boolean footer) {

        /** The key and what it does, as the footer shows them. */
        public String text() {
            return key + " " + does;
        }
    }

    /** The keys of one group. */
    public record Section(Group group, List<Key> keys) {
        public Section {
            keys = List.copyOf(keys);
        }
    }

    /** Between two entries of a group in the footer. */
    static final String GAP = "   ";

    /** Between two groups in the footer, with the bar in the middle. */
    static final String GROUP_GAP = "  │  ";

    public static final Keys NONE = new Keys(List.of());

    /**
     * What moves on every screen and in the viewer, for the key list: a screen's own moves, and Tab
     * where it has panels, go in its {@link Group#MOVE}.
     */
    public static final Keys COMMON = builder()
            .more(Group.MOVE, "↑↓ j k", "move")
            .more(Group.MOVE, "PgUp/PgDn", "page")
            .more(Group.MOVE, "Home/End g g/G", "first / last")
            .build();

    public Keys {
        sections = List.copyOf(sections);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * These keys, then {@code more}'s. A group that ends these and starts {@code more} becomes one;
     * the others keep their places, so an Esc that stops something can come first.
     */
    public Keys plus(Keys more) {
        List<Section> all = new ArrayList<>(sections);
        for (Section section : more.sections) {
            int last = all.size() - 1;
            if (last >= 0 && all.get(last).group() == section.group()) {
                List<Key> keys = new ArrayList<>(all.get(last).keys());
                keys.addAll(section.keys());
                all.set(last, new Section(section.group(), keys));
            } else {
                all.add(section);
            }
        }
        return new Keys(all);
    }

    /** Every key, each group once and in the order of {@link Group}, for the key list. */
    public Keys merged() {
        Map<Group, List<Key>> keys = new EnumMap<>(Group.class);
        for (Section section : sections) {
            keys.computeIfAbsent(section.group(), _ -> new ArrayList<>()).addAll(section.keys());
        }
        return new Keys(keys.entrySet().stream()
                .map(entry -> new Section(entry.getKey(), entry.getValue()))
                .toList());
    }

    /** The groups with the keys that the footer shows, without those left empty. */
    public List<Section> footer() {
        List<Section> shown = new ArrayList<>();
        for (Section section : sections) {
            List<Key> keys = section.keys().stream().filter(Key::footer).toList();
            if (!keys.isEmpty()) {
                shown.add(new Section(section.group(), keys));
            }
        }
        return shown;
    }

    /** Whether the footer shows {@code key}, as in {@code s/S}. */
    public boolean shows(String key) {
        return footer().stream()
                .flatMap(section -> section.keys().stream())
                .anyMatch(k -> k.key().equals(key));
    }

    /** The footer as plain text on one line, as {@link KeyLegend} shows it where it fits. */
    public String text() {
        return footer().stream()
                .map(section -> section.keys().stream().map(Key::text).collect(Collectors.joining(GAP)))
                .collect(Collectors.joining(GROUP_GAP, " ", ""));
    }

    /** Collects keys by their group; {@link #build} puts the groups in the order of {@link Group}. */
    public static final class Builder {

        private final Map<Group, List<Key>> keys = new EnumMap<>(Group.class);

        private Builder() {}

        /** A key that the footer shows. */
        public Builder key(Group group, String key, String does) {
            return add(group, new Key(key, does, true));
        }

        /** A key that only the key list shows. */
        public Builder more(Group group, String key, String does) {
            return add(group, new Key(key, does, false));
        }

        /** {@link #key}, if {@code shown}. */
        public Builder keyIf(boolean shown, Group group, String key, String does) {
            return shown ? key(group, key, does) : this;
        }

        /** {@link #more}, if {@code shown}. */
        public Builder moreIf(boolean shown, Group group, String key, String does) {
            return shown ? more(group, key, does) : this;
        }

        private Builder add(Group group, Key key) {
            keys.computeIfAbsent(group, _ -> new ArrayList<>()).add(key);
            return this;
        }

        public Keys build() {
            return new Keys(keys.entrySet().stream()
                    .map(entry -> new Section(entry.getKey(), entry.getValue()))
                    .toList());
        }
    }
}
