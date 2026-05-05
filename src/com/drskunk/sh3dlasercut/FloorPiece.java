package com.drskunk.sh3dlasercut;

import java.util.List;

/**
 * Flat-layout representation of the floor plate.
 *
 * Coordinates are in world (plan-view) mm. The piece holds an outline polygon
 * plus a list of axis-aligned slot rectangles (in the plane, possibly rotated)
 * that get cut out of the interior — these are where each wall's bottom tabs
 * poke through.
 */
public final class FloorPiece {
    /** Outline polygon, in floor-local mm. */
    public final List<double[]> outline;
    /** Each slot is a 4-point polygon (4 corners in order). */
    public final List<double[][]> slots;
    /** Bounds: minX, minY, maxX, maxY of the outline. */
    public final double[] bounds;

    public FloorPiece(List<double[]> outline, List<double[][]> slots) {
        this.outline = outline;
        this.slots = slots;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double[] p : outline) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        this.bounds = new double[] { minX, minY, maxX, maxY };
    }
}
