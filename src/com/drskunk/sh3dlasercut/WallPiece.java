package com.drskunk.sh3dlasercut;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat-layout representation of one wall as it will appear on the cut sheet.
 *
 * Local coordinate frame:
 *   x = 0..length    (along the wall)
 *   y = 0..height    (vertical, bottom to top)
 *
 * Tabs on the bottom edge protrude DOWN past y=0 by {@code thickness}.
 * Tabs on side edges protrude OUTWARD past x=0 / x=length by {@code thickness}.
 */
public final class WallPiece {
    public final double length;
    public final double height;
    public final double thickness;
    public final List<TabPattern.Span> bottomTabs;
    /** Tabs on the start edge (x=0). May be null if edge is straight. */
    public final List<TabPattern.Span> leftTabs;
    /** Tabs on the end edge (x=length). May be null if edge is straight. */
    public final List<TabPattern.Span> rightTabs;
    /**
     * Door/window cutouts in wall-local coords, each as
     * {@code [xMin, yMin, xMax, yMax]}. Cuts inside the main rectangle.
     */
    public final List<double[]> cutouts;
    public final String label;

    public WallPiece(double length, double height, double thickness,
                     List<TabPattern.Span> bottomTabs,
                     List<TabPattern.Span> leftTabs,
                     List<TabPattern.Span> rightTabs,
                     List<double[]> cutouts,
                     String label) {
        this.length = length;
        this.height = height;
        this.thickness = thickness;
        this.bottomTabs = bottomTabs;
        this.leftTabs = leftTabs;
        this.rightTabs = rightTabs;
        this.cutouts = cutouts != null ? cutouts : new ArrayList<>();
        this.label = label;
    }

    /** Bounding box including tab protrusions. */
    public double[] bounds() {
        double minX = (leftTabs != null) ? -thickness : 0;
        double maxX = length + (rightTabs != null ? thickness : 0);
        double minY = -thickness; // bottom tabs always present
        double maxY = height;
        return new double[] { minX, minY, maxX, maxY };
    }

    /**
     * Build the closed polygon outline of this piece, going counter-clockwise:
     * bottom-left -> up the left edge -> across the top -> down the right edge ->
     * back across the bottom (with downward tabs) -> close.
     */
    public List<double[]> outline() {
        List<double[]> pts = new ArrayList<>();

        // Start at bottom-left of the main rectangle.
        pts.add(new double[] { 0, 0 });

        // Left edge: bottom -> top
        if (leftTabs == null) {
            pts.add(new double[] { 0, height });
        } else {
            // Tabs protrude to the left (negative x), height-direction polarity.
            double cursorY = 0;
            for (TabPattern.Span span : leftTabs) {
                if (span.start > cursorY) {
                    pts.add(new double[] { 0, span.start });
                }
                pts.add(new double[] { -thickness, span.start });
                pts.add(new double[] { -thickness, span.end });
                pts.add(new double[] { 0, span.end });
                cursorY = span.end;
            }
            if (cursorY < height) {
                pts.add(new double[] { 0, height });
            }
        }

        // Top edge: left -> right (straight)
        pts.add(new double[] { length, height });

        // Right edge: top -> bottom
        if (rightTabs == null) {
            pts.add(new double[] { length, 0 });
        } else {
            double cursorY = height;
            // Walk tabs in reverse (top to bottom).
            for (int i = rightTabs.size() - 1; i >= 0; i--) {
                TabPattern.Span span = rightTabs.get(i);
                if (span.end < cursorY) {
                    pts.add(new double[] { length, span.end });
                }
                pts.add(new double[] { length + thickness, span.end });
                pts.add(new double[] { length + thickness, span.start });
                pts.add(new double[] { length, span.start });
                cursorY = span.start;
            }
            if (cursorY > 0) {
                pts.add(new double[] { length, 0 });
            }
        }

        // Bottom edge: right -> left, with tabs going DOWN (negative y).
        double cursorX = length;
        for (int i = bottomTabs.size() - 1; i >= 0; i--) {
            TabPattern.Span span = bottomTabs.get(i);
            if (span.end < cursorX) {
                pts.add(new double[] { span.end, 0 });
            }
            pts.add(new double[] { span.end, -thickness });
            pts.add(new double[] { span.start, -thickness });
            pts.add(new double[] { span.start, 0 });
            cursorX = span.start;
        }
        if (cursorX > 0) {
            pts.add(new double[] { 0, 0 });
        }

        return pts;
    }
}
