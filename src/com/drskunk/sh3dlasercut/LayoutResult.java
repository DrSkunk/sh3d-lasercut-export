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
    /** Each shape is a closed polygon, ordered list of {x, y}. */
    public final List<List<double[]>> shapes = new ArrayList<>();
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
