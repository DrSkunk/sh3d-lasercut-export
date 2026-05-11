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

    /** Output file format. */
    public enum ExportFormat { SVG, DXF, BOTH }

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
     * If true, no finger joints are generated anywhere -- neither wall-to-wall
     * nor wall-to-floor.  Wall panels are plain rectangles (or trapezoids for
     * sloping walls) and the floor plate is a plain outline with no slots.
     * Pieces are intended to be assembled with glue or snap-fit rather than
     * interlocking tabs.
     */
    public boolean smoothConnections = false;

    /**
     * Controls how connections involving sloping walls (walls whose height
     * differs at the two ends) are exported.
     */
    public SlopingWallMode slopingWallMode = SlopingWallMode.COMPENSATE;

    /**
     * Physical width of the laser-cutting board in mm (0 = no constraint).
     * If the floor baseplate is wider than this value, a warning is shown and
     * (when {@link #splitFloor} is true) the floor is split into tiles.
     */
    public double boardWidth = 0.0;

    /** Physical height of the laser-cutting board in mm (0 = no constraint). */
    public double boardHeight = 0.0;

    /**
     * When the floor baseplate does not fit on the specified board, split it
     * automatically into interlocking tiles using box-joint style seams.
     * Has no effect when both {@link #boardWidth} and {@link #boardHeight}
     * are zero or the floor already fits on a single board.
     */
    public boolean splitFloor = false;

    /**
     * When true and both {@link #boardWidth} and {@link #boardHeight} are set,
     * write one SVG file per board instead of a single combined SVG.
     */
    public boolean separateFilesPerBoard = false;

    /** Stroke width of cut lines in the SVG, in mm (0 for hairline). */
    public double svgStrokeWidth = 0.1;

    /** Stroke color used for cut lines. Default red is the laser-driver convention. */
    public Color cutStrokeColor = Color.RED;

    /**
     * Laser kerf width in mm (0 = disabled).  Half the kerf is applied per side:
     * outer profiles expand by kerfMm/2, inner cuts shrink by kerfMm/2, so
     * physical pieces match the designed dimensions.
     */
    public double kerfMm = 0.0;

    /**
     * Width of holding bridges inserted in outer cut profiles, in mm.
     * Zero disables bridges.  Bridges keep pieces inside the sheet until
     * the operator snaps them out -- useful to prevent pieces falling through
     * the laser bed mid-job.  Edges shorter than 3x bridgeWidth are not bridged.
     */
    public double bridgeWidth = 0.0;

    /**
     * Number of bridges per long edge.  Only used when {@link #bridgeWidth} > 0.
     */
    public int bridgesPerEdge = 2;

    /** Which file format(s) to write. */
    public ExportFormat exportFormat = ExportFormat.SVG;
}
