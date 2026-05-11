package com.drskunk.sh3dlasercut;

import java.util.ArrayList;
import java.util.List;

/**
 * The full geometry of an export, with every piece already translated into
 * its final SVG position. Consumed by both {@link SVGWriter} (to write the
 * file) and the options dialog's preview panel (to render on screen).
 *
 * Coordinates are millimeters of the output sheet (already scale-divided).
 */
public final class LayoutResult {
    /** Outer cut paths (piece profiles). Emitted after innerShapes in the file. */
    public final List<List<double[]>> shapes = new ArrayList<>();
    /**
     * Inner cut paths (floor slots, door/window openings).  Laser drivers
     * should cut these before the outer profiles so panels don't shift after
     * their boundary is freed.  Emitted first in SVG/DXF output.
     */
    public final List<List<double[]>> innerShapes = new ArrayList<>();
    /** Gray reference outlines (wall footprints on the floor), same color as labels. */
    public final List<List<double[]>> referenceShapes = new ArrayList<>();
    /** Cosmetic labels (engraved or text annotations). */
    public final List<Label> labels = new ArrayList<>();
    /** Total layout extents, in mm. */
    public double width;
    public double height;

    /**
     * Non-null when the floor baseplate does not fit within the specified
     * board size and automatic splitting has not been requested. The string
     * is a human-readable warning message suitable for display in the UI.
     */
    public String boardWarning = null;

    /**
     * Board outline rectangles in layout coordinates, populated when both
     * {@code boardWidth} and {@code boardHeight} are set.
     * Each entry is {@code {x, y, w, h}}.  Used by the preview panel to draw
     * board boundaries and by the SVG writer to emit board outline paths.
     */
    public final List<double[]> boardRects = new ArrayList<>();

    public static final class Label {
        public final String text;
        public final double x, y, size;
        public Label(String text, double x, double y, double size) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }
}
