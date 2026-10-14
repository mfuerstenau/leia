package io.github.mfuerstenau.leia.protocol.points;

/**
 * A cell of a BKD tree (ADR 0041): the points of a box of the value space.
 *
 * @param min the box's smallest value of each index dimension
 * @param max the box's largest value of each index dimension
 * @param size the points in the cell
 * @param leaf whether the cell holds its points itself, and has no children
 */
public record PointCell(PointValue min, PointValue max, long size, boolean leaf) {

    /** The most points {@code getPointCell} returns of a leaf. */
    public static final int MAX_POINTS = 10_000;
}
