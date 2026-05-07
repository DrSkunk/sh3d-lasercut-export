package com.drskunk.sh3dlasercut;

import java.awt.Color;

public class ExportOptions {

    /**
     * How connections between sloping walls (walls whose height differs at the
     * two ends) and their neighbours are treated.
     */
    public enum SlopingWallMode {
        /**
         * No finger joints are generated at any end of a sloping wall, nor at
         * any end of a wall that connects to a sloping wall.  Those edges are
         * rendered as straight cuts and are suitable for gluing.
         */
        SMOOTH,
        /**
         * Finger joints are generated normally but are clipped to the actual
         * physical height at the junction (the minimum of the two walls' heights
         * at that end).  The sloping wall panel is rendered as a trapezoid.
         */
        COMPENSATE
    }
    /**
     * Scale divisor: 1:N where N is this value. The model is shrunk by this
     * factor in the SVG. E.g. a 5 m wall at scaleDivisor=50 becomes 100 mm.
     *
     * Material thickness, finger width, floor margin, and layout spacing
     * remain physical sheet millimeters and are NOT divided by the scale.
     */
    public double scaleDivisor = 50.0;

    /** Consolidated wall + floor material thickness, in mm. */
    public double materialThickness = 3.0;

    /** Target width of each finger / slot in a box joint, in mm. */
    public double tabWidth = 15.0;

    /** Padding around the floor outline, in mm. */
    public double floorMargin = 20.0;

    /** Spacing between pieces in the SVG layout, in mm. */
    public double layoutSpacing = 10.0;

    /**
     * If true, walls do not interlock with each other — only with the floor.
     * Wall-to-wall edges are rendered as straight cuts, suitable for gluing
     * or other smooth corner treatments.
     */
    public boolean smoothConnections = false;

    /**
     * Controls how connections involving sloping walls (walls whose height
     * differs at the two ends) are exported.
     * <ul>
     *   <li>{@link SlopingWallMode#SMOOTH} – no finger joints on any end
     *       that belongs to, or touches, a sloping wall.</li>
     *   <li>{@link SlopingWallMode#COMPENSATE} – finger joints are clipped
     *       to the actual height at the junction; the panel is trapezoidal.</li>
     * </ul>
     */
    public SlopingWallMode slopingWallMode = SlopingWallMode.COMPENSATE;

    /** Stroke width of cut lines in the SVG, in mm (0 for hairline). */
    public double svgStrokeWidth = 0.1;

    /**
     * Stroke color used for cut lines. Defaults to red (#FF0000) — the
     * convention most laser-cutter drivers expect for vector cuts.
     */
    public Color cutStrokeColor = Color.RED;
}
