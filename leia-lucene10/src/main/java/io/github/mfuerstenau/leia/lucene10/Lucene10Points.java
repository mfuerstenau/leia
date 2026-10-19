package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.ReadOnlyIndex;
import io.github.mfuerstenau.leia.runtime.Points;
import java.io.IOException;
import java.util.List;

/** The BKD trees of point fields, read by leia-core's {@code Points} (ADR 0041, ADR 0065). */
final class Lucene10Points implements Points {

    private final ReadOnlyIndex index;

    Lucene10Points(ReadOnlyIndex index) {
        this.index = index;
    }

    @Override
    public Tree tree(String field) throws IOException {
        io.github.mfuerstenau.leia.core.Points.Tree tree = index.pointTree(field);
        return new Tree(
                tree.field(),
                tree.dimensions(),
                tree.indexDimensions(),
                tree.bytesPerDimension(),
                tree.size(),
                tree.docCount(),
                tree.min().map(Lucene10Index::bytes),
                tree.max().map(Lucene10Index::bytes),
                tree.segments().stream()
                        .map(segment -> new Segment(
                                segment.segment(),
                                segment.name(),
                                segment.docBase(),
                                segment.maxDoc(),
                                segment.size(),
                                segment.docCount(),
                                Lucene10Index.bytes(segment.min()),
                                Lucene10Index.bytes(segment.max())))
                        .toList());
    }

    @Override
    public CellContents cell(String field, int segment, List<Integer> path) throws IOException {
        io.github.mfuerstenau.leia.core.Points.CellContents contents = index.pointCell(field, segment, path);
        return new CellContents(
                cell(contents.cell()),
                contents.children().stream().map(Lucene10Points::cell).toList(),
                contents.points().stream()
                        .map(point -> new LeafPoint(point.docId(), point.deleted(), Lucene10Index.bytes(point.value())))
                        .toList(),
                contents.pointsCut());
    }

    private static Cell cell(io.github.mfuerstenau.leia.core.Points.Cell cell) {
        return new Cell(Lucene10Index.bytes(cell.min()), Lucene10Index.bytes(cell.max()), cell.size(), cell.leaf());
    }
}
