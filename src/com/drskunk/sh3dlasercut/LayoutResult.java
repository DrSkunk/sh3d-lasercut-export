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
    /** Cosmetic labels (engraved or text annotations). */
    public final List<Label> labels = new ArrayList<>();
    /** Total layout extents, in mm. */
    public double width;
    public double height;

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
