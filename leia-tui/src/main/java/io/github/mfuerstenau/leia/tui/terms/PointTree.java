package io.github.mfuerstenau.leia.tui.terms;

import dev.tamboui.widgets.table.TableState;
import io.github.mfuerstenau.leia.protocol.points.GetPointCellResult;
import io.github.mfuerstenau.leia.protocol.points.GetPointTreeResult;
import io.github.mfuerstenau.leia.protocol.points.LeafPoint;
import io.github.mfuerstenau.leia.protocol.points.PointCell;
import io.github.mfuerstenau.leia.protocol.points.PointSegment;
import io.github.mfuerstenau.leia.protocol.points.PointValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A point field's BKD tree on the terms screen (ADR 0041): a row per segment, and below an expanded
 * one its cells, fetched a level at a time; and the points of the leaf last opened.
 */
final class PointTree {

    /** A segment's root cell, or a cell below it. */
    static final class Node {

        final int segment;

        /** The child positions from the segment's root; empty for the root. */
        final List<Integer> path;

        /** The segment's name, for a root; null for the others. */
        final @Nullable String name;

        final PointValue min;
        final PointValue max;
        final long size;

        /** Whether the cell is a leaf; null until the server says, which it does for children. */
        @Nullable
        Boolean leaf;

        /** The children once fetched; null before. */
        List<Node> children;

        /** The points of a leaf once fetched; null before. */
        List<LeafPoint> points;

        boolean pointsCut;
        boolean expanded;
        boolean loading;

        @Nullable
        String error;

        Node(int segment, List<Integer> path, @Nullable String name, PointCell cell) {
            this(segment, path, name, cell.min(), cell.max(), cell.size(), cell.leaf());
        }

        Node(
                int segment,
                List<Integer> path,
                @Nullable String name,
                PointValue min,
                PointValue max,
                long size,
                @Nullable Boolean leaf) {
            this.segment = segment;
            this.path = List.copyOf(path);
            this.name = name;
            this.min = min;
            this.max = max;
            this.size = size;
            this.leaf = leaf;
        }

        int depth() {
            return path.size();
        }

        /** Takes the cell's children, or its points if it is a leaf. */
        void loaded(GetPointCellResult result) {
            loading = false;
            error = null;
            leaf = result.cell().leaf();
            if (result.cell().leaf()) {
                points = List.copyOf(result.points());
                pointsCut = result.pointsCut();
                return;
            }
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < result.children().size(); i++) {
                List<Integer> childPath = new ArrayList<>(path);
                childPath.add(i);
                nodes.add(new Node(segment, childPath, null, result.children().get(i)));
            }
            children = List.copyOf(nodes);
            expanded = true;
        }
    }

    /** The serial of the {@code getPointTree} call this tree came from; replies for another are dropped. */
    final long serial;

    final String field;

    /** The tree; null if it could not be fetched. */
    final GetPointTreeResult tree;

    final @Nullable String error;

    final List<Node> segments;

    /** The selected visible row. */
    int selected;

    final TableState table = new TableState();

    /** The leaf whose points are shown; null before one is opened. */
    Node leaf;

    int pointSelected;
    final TableState pointTable = new TableState();

    PointTree(long serial, String field, GetPointTreeResult tree) {
        this.serial = serial;
        this.field = field;
        this.tree = tree;
        this.error = null;
        this.segments = tree.segments().stream().map(PointTree::root).toList();
    }

    PointTree(long serial, String field, String error) {
        this.serial = serial;
        this.field = field;
        this.tree = null;
        this.error = error;
        this.segments = List.of();
    }

    private static Node root(PointSegment segment) {
        return new Node(
                segment.segment(),
                List.of(),
                segment.name().orElse(null),
                segment.min(),
                segment.max(),
                segment.size(),
                null);
    }

    /** The rows shown: the segments, each followed by its cells as far as it is expanded. */
    List<Node> visible() {
        List<Node> rows = new ArrayList<>();
        for (Node segment : segments) {
            addVisible(segment, rows);
        }
        return rows;
    }

    private static void addVisible(Node node, List<Node> rows) {
        rows.add(node);
        if (node.expanded && node.children != null) {
            for (Node child : node.children) {
                addVisible(child, rows);
            }
        }
    }

    Optional<Node> selectedNode() {
        List<Node> rows = visible();
        return selected < rows.size() ? Optional.of(rows.get(selected)) : Optional.empty();
    }

    /** The node of a segment at a path, if it is in the tree as far as fetched. */
    Optional<Node> find(int segment, List<Integer> path) {
        Node node =
                segments.stream().filter(s -> s.segment == segment).findFirst().orElse(null);
        for (int i = 0; node != null && i < path.size(); i++) {
            int child = path.get(i);
            node = node.children != null && child < node.children.size() ? node.children.get(child) : null;
        }
        return Optional.ofNullable(node);
    }

    /** The selected point of the shown leaf. */
    Optional<LeafPoint> selectedPoint() {
        return leaf != null && pointSelected < leaf.points.size()
                ? Optional.of(leaf.points.get(pointSelected))
                : Optional.empty();
    }

    /** Shows the points of {@code node}, a fetched leaf. */
    void showLeaf(Node node) {
        leaf = node;
        pointSelected = 0;
    }
}
