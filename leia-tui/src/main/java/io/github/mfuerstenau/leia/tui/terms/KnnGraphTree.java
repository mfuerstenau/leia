package io.github.mfuerstenau.leia.tui.terms;

import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodeResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphNodesResult;
import io.github.mfuerstenau.leia.protocol.knn.GetKnnGraphResult;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphLevel;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphNeighbour;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphNode;
import io.github.mfuerstenau.leia.protocol.knn.KnnGraphSegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The HNSW graphs of a KNN vector field on the terms screen (ADR 0085): a row per segment and, below
 * an expanded one, its entry node and its levels, top first, each of which expands to its nodes,
 * fetched a page at a time. On the right, the neighbours of the node last opened, by level; a walk
 * from neighbour to neighbour keeps the nodes it went through, for {@code <} to go back.
 */
final class KnnGraphTree {

    /** A segment's graph. */
    static final class Segment {

        final KnnGraphSegment info;

        /** Its levels, top first; empty without a graph. */
        final List<Level> levels;

        boolean expanded;

        Segment(KnnGraphSegment info) {
            this.info = info;
            List<Level> levels = new ArrayList<>();
            info.shape().ifPresent(shape -> {
                for (int level = shape.levelSizes().size() - 1; level >= 0; level--) {
                    levels.add(
                            new Level(info.segment(), level, shape.levelSizes().get(level)));
                }
            });
            this.levels = List.copyOf(levels);
        }
    }

    /** A level of a segment's graph, and its nodes as far as they are fetched. */
    static final class Level {

        final int segment;
        final int level;
        final int total;

        /** The nodes fetched, in ordinal order; empty before the first page. */
        final List<KnnGraphNode> nodes = new ArrayList<>();

        boolean expanded;
        boolean loading;
        Optional<String> error = Optional.empty();

        Level(int segment, int level, int total) {
            this.segment = segment;
            this.level = level;
            this.total = total;
        }

        boolean fetched() {
            return !nodes.isEmpty() || total == 0;
        }

        boolean more() {
            return nodes.size() < total && error.isEmpty();
        }

        void loaded(GetKnnGraphNodesResult result) {
            loading = false;
            error = Optional.empty();
            nodes.addAll(result.nodes());
            expanded = true;
        }
    }

    /** A row of the middle column. */
    sealed interface Item {

        /** A segment, which expands to its entry node and levels. */
        record SegmentItem(Segment segment) implements Item {}

        /** The entry node of a segment's graph. */
        record EntryItem(Segment segment, KnnGraphNode node) implements Item {}

        /** A level, which expands to its nodes. */
        record LevelItem(Level level) implements Item {}

        /** A node of a level. */
        record NodeItem(Level level, KnnGraphNode node) implements Item {}

        /** The row after the last fetched node of a level that has more. */
        record MoreItem(Level level) implements Item {}
    }

    /** A node whose neighbours are shown, and the selected one among them. */
    static final class Shown {

        final GetKnnGraphNodeResult node;

        /** The selected neighbour, an index into {@link #neighbours()}. */
        int selected;

        final TableState table = new TableState();

        Shown(GetKnnGraphNodeResult node) {
            this.node = node;
        }

        /** The neighbours of every level, top level first, as the right column lists them. */
        List<KnnGraphNeighbour> neighbours() {
            List<KnnGraphNeighbour> all = new ArrayList<>();
            for (KnnGraphLevel level : node.levels()) {
                all.addAll(level.neighbours());
            }
            return all;
        }

        Optional<KnnGraphNeighbour> selectedNeighbour() {
            List<KnnGraphNeighbour> all = neighbours();
            return selected < all.size() ? Optional.of(all.get(selected)) : Optional.empty();
        }
    }

    /** The serial of the {@code getKnnGraph} call this tree came from; replies for another are dropped. */
    final long serial;

    final String field;

    /** The graphs; null if they could not be fetched. */
    final GetKnnGraphResult graph;

    final Optional<String> error;

    final List<Segment> segments;

    /** The selected visible row. */
    int selected;

    final TableState table = new TableState();

    /** The nodes walked through, the last one shown; empty before one is opened. */
    final List<Shown> path = new ArrayList<>();

    /** Why the last node asked for could not be fetched; cleared by the next one. */
    Optional<String> nodeError = Optional.empty();

    KnnGraphTree(long serial, String field, GetKnnGraphResult graph) {
        this.serial = serial;
        this.field = field;
        this.graph = graph;
        this.error = Optional.empty();
        this.segments = graph.segments().stream().map(Segment::new).toList();
    }

    KnnGraphTree(long serial, String field, String error) {
        this.serial = serial;
        this.field = field;
        this.graph = null;
        this.error = Optional.of(error);
        this.segments = List.of();
    }

    /** The rows shown: the segments, each followed by what of it is expanded. */
    List<Item> visible() {
        List<Item> rows = new ArrayList<>();
        for (Segment segment : segments) {
            rows.add(new Item.SegmentItem(segment));
            if (!segment.expanded || segment.info.shape().isEmpty()) {
                continue;
            }
            rows.add(new Item.EntryItem(segment, segment.info.shape().get().entry()));
            for (Level level : segment.levels) {
                rows.add(new Item.LevelItem(level));
                if (level.expanded) {
                    level.nodes.forEach(node -> rows.add(new Item.NodeItem(level, node)));
                    if (level.more()) {
                        rows.add(new Item.MoreItem(level));
                    }
                }
            }
        }
        return rows;
    }

    Optional<Item> selectedItem() {
        List<Item> rows = visible();
        return selected < rows.size() ? Optional.of(rows.get(selected)) : Optional.empty();
    }

    /** Level {@code level} of segment {@code segment}'s graph. */
    Optional<Level> level(int segment, int level) {
        return segments.stream()
                .filter(s -> s.info.segment() == segment)
                .flatMap(s -> s.levels.stream())
                .filter(l -> l.level == level)
                .findFirst();
    }

    /** The node whose neighbours are shown. */
    Optional<Shown> shown() {
        return path.isEmpty() ? Optional.empty() : Optional.of(path.getLast());
    }
}
