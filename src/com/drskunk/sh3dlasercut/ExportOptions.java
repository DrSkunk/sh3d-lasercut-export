package com.drskunk.sh3dlasercut;

import java.awt.Color;

public class ExportOptions {
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
     * Physical width of the laser-cutting board in mm (0 = no constraint).
     * If the floor baseplate is wider than this value, a warning is shown and
     * (when {@link #splitFloor} is true) the floor is split into tiles.
     */
    public double boardWidth = 0.0;

    /**
     * Physical height of the laser-cutting board in mm (0 = no constraint).
     */
    public double boardHeight = 0.0;

    /**
     * When the floor baseplate does not fit on the specified board, split it
     * automatically into interlocking tiles using box-joint style seams.
     * Has no effect when both {@link #boardWidth} and {@link #boardHeight}
     * are zero or the floor already fits on a single board.
     */
    public boolean splitFloor = false;

    /** Stroke width of cut lines in the SVG, in mm (0 for hairline). */
    public double svgStrokeWidth = 0.1;

    /**
     * Stroke color used for cut lines. Defaults to red (#FF0000) — the
     * convention most laser-cutter drivers expect for vector cuts.
     */
    public Color cutStrokeColor = Color.RED;
}
