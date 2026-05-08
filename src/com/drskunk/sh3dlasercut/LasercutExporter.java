package com.drskunk.sh3dlasercut;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Wall;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds wall + floor cut geometry from a Sweet Home 3D model and writes it
 * out as an SVG file ready for laser cutting.
 *
 * Sweet Home 3D stores positions in cm; this exporter converts to mm and then
 * divides by {@link ExportOptions#scaleDivisor} so the final SVG fits a sheet.
 * Material thickness, finger width, margins, and spacing remain physical mm.
 */
public final class LasercutExporter {

    private static final double CM_TO_MM = 10.0;

    /** Tolerance used for floating-point dimension comparisons, in mm. */
    private static final double SEAM_TOLERANCE = 1e-3;

    /**
     * Epsilon used when comparing individual coordinate values (e.g. to
     * detect whether a closing point duplicates the start point of a polygon).
     * Smaller than {@link #SEAM_TOLERANCE} because it operates on single
     * floating-point coordinates rather than accumulated dimensions.
     */
    private static final double COORD_EPSILON = 1e-9;

    private final Home home;
    private final ExportOptions options;
    private final Level targetLevel; // null = export every wall in the home

    public LasercutExporter(Home home, ExportOptions options) {
        this.home = home;
        this.options = options;
        this.targetLevel = home.getSelectedLevel();
    }

    public List<File> export(File outputFile) throws IOException {
        if (options.separateFilesPerBoard && isBoardConstrained()) {
            List<LayoutResult> boards = buildBoardLayouts();
            if (boards.isEmpty() || boards.get(0).shapes.isEmpty()) {
                throw new IOException("No walls found on the selected level.");
            }
            return writeBoardFiles(outputFile, boards);
        } else {
            LayoutResult layout = buildLayout();
            if (layout.shapes.isEmpty()) {
                throw new IOException("No walls found on the selected level.");
            }
            writeSvg(outputFile, layout);
            return Collections.singletonList(outputFile);
        }
    }

    /**
     * Build the full layout (floor + walls placed in their final SVG positions)
     * without writing it to disk. Used both by {@link #export} and by the
     * options dialog's live preview.
     */
    public LayoutResult buildLayout() {
        List<Wall> walls = collectWalls(home, targetLevel);
        if (walls.isEmpty()) {
            return new LayoutResult();
        }
        Set<Wall> wallSet = new HashSet<>(walls);

        Map<Wall, WallPiece> pieces = new LinkedHashMap<>();
        int idx = 1;
        for (Wall w : walls) {
            pieces.put(w, buildWallPiece(w, wallSet, idx++));
        }
        addTJunctionNotches(walls, wallSet, pieces);

        LayoutResult result = new LayoutResult();
        int[] gridSize = { 1, 1 }; // [numCols, numRows] of floor tile grid
        List<FloorPiece> floorTiles = buildFloorTiles(walls, pieces, result, gridSize);

        if (isBoardConstrained()) {
            List<BoardItem> items = gatherItems(walls, pieces, floorTiles);
            List<LayoutResult> boards = packOntoBoards(items);
            buildCombinedLayout(boards, result);
        } else {
            composeLayout(pieces, floorTiles, gridSize[0], result);
        }
        return result;
    }

    /**
     * Build per-board layouts for separate-file export.
     * Each returned result contains the pieces assigned to one board, in
     * board-local (0,0) coordinates.  Returns a single-element list when
     * board constraints are not set (all pieces on one unlimited "board").
     */
    public List<LayoutResult> buildBoardLayouts() {
        List<Wall> walls = collectWalls(home, targetLevel);
        if (walls.isEmpty()) {
            return Collections.singletonList(new LayoutResult());
        }
        Set<Wall> wallSet = new HashSet<>(walls);

        Map<Wall, WallPiece> pieces = new LinkedHashMap<>();
        int idx = 1;
        for (Wall w : walls) {
            pieces.put(w, buildWallPiece(w, wallSet, idx++));
        }
        addTJunctionNotches(walls, wallSet, pieces);

        LayoutResult dummy = new LayoutResult(); // boardWarning not needed here
        int[] gridSize = { 1, 1 };
        List<FloorPiece> floorTiles = buildFloorTiles(walls, pieces, dummy, gridSize);
        List<BoardItem> items = gatherItems(walls, pieces, floorTiles);
        return packOntoBoards(items);
    }

    /** Gather model dimensions for the live size preview in the options dialog. */
    public static ModelMetrics computeMetrics(Home home) {
        ModelMetrics metrics = new ModelMetrics();
        Level target = home.getSelectedLevel();
        List<Wall> walls = collectWalls(home, target);

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (Wall w : walls) {
            metrics.wallLengths_mm.add(rawWallLengthMm(w));
            metrics.wallHeights_mm.add(rawWallHeightMm(w, home));
            for (float[] p : w.getPoints()) {
                any = true;
                double x = p[0] * CM_TO_MM;
                double y = p[1] * CM_TO_MM;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (any) {
            metrics.floorWidth_mm = maxX - minX;
            metrics.floorHeight_mm = maxY - minY;
        }
        return metrics;
    }

    // ---- collection ----------------------------------------------------------

    private static List<Wall> collectWalls(Home home, Level targetLevel) {
        List<Wall> result = new ArrayList<>();
        for (Wall w : home.getWalls()) {
            if (targetLevel == null || w.getLevel() == targetLevel) {
                result.add(w);
            }
        }
        return result;
    }

    // ---- wall geometry -------------------------------------------------------

    private double scaleFactor() {
        return 1.0 / options.scaleDivisor;
    }

    /** Returns true when both board dimensions are positive (board packing is active). */
    private boolean isBoardConstrained() {
        return options.boardWidth > 0 && options.boardHeight > 0;
    }

    private WallPiece buildWallPiece(Wall wall, Set<Wall> levelWalls, int index) {
        double sf = scaleFactor();
        double heightAtStart = rawWallHeightMm(wall, home) * sf;
        double heightAtEnd   = rawWallEndHeightMm(wall, home) * sf;
        boolean sloping = isSloping(wall, home);
        double thickness = options.materialThickness;
        double tabWidth = options.tabWidth;

        List<TabPattern.Span> leftTabs = null;
        List<TabPattern.Span> rightTabs = null;
        boolean leftConnected = false, rightConnected = false;
        if (!options.smoothConnections) {
            Wall startNeighbor = wall.getWallAtStart();
            boolean skipStart = startNeighbor != null
                    && (sloping || isSloping(startNeighbor, home))
                    && options.slopingWallMode == ExportOptions.SlopingWallMode.SMOOTH;
            if (!skipStart && startNeighbor != null && levelWalls.contains(startNeighbor)) {
                leftConnected = true;
                boolean primary = isPrimary(wall, startNeighbor);
                double jointH = computeJointHeightScaled(wall, true, startNeighbor, sf);
                leftTabs = TabPattern.compute(jointH, tabWidth, !primary);
            }
            Wall endNeighbor = wall.getWallAtEnd();
            boolean skipEnd = endNeighbor != null
                    && (sloping || isSloping(endNeighbor, home))
                    && options.slopingWallMode == ExportOptions.SlopingWallMode.SMOOTH;
            if (!skipEnd && endNeighbor != null && levelWalls.contains(endNeighbor)) {
                rightConnected = true;
                boolean primary = isPrimary(wall, endNeighbor);
                double jointH = computeJointHeightScaled(wall, false, endNeighbor, sf);
                rightTabs = TabPattern.compute(jointH, tabWidth, !primary);
            }
        }

        // Each connected end is inset by half the material thickness so that the
        // finger tabs protrude from the adjacent wall's inner face to its outer
        // face (spanning exactly one material thickness), rather than from the
        // wall's centre-line (which would overshoot by t/2).
        double startOffset = leftConnected  ? thickness / 2.0 : 0;
        double endOffset   = rightConnected ? thickness / 2.0 : 0;
        double length = rawWallLengthMm(wall) * sf - startOffset - endOffset;

        // Bottom tabs always present.
        // startsWithTab = false → corners are flat, slots sit cleanly inside the floor outline.
        List<TabPattern.Span> bottomTabs = TabPattern.compute(length, tabWidth, false);

        // Door / window cutouts in panel-local coords (origin = panel x=0, which
        // is inset from the wall centre-line start by startOffset).
        // For sloping walls use the taller of the two end heights as the clamp so
        // that cutouts spanning the full height of either end are not incorrectly
        // truncated.
        double maxHeight = Math.max(heightAtStart, heightAtEnd);
        List<double[]> cutouts = findCutouts(wall, sf, length, maxHeight, startOffset);

        // Drop bottom tabs that sit underneath a doorway — otherwise the tab
        // would be a detached strip dangling in the opening.
        bottomTabs = filterBottomTabsByCutouts(bottomTabs, cutouts);

        return new WallPiece(length, heightAtStart, heightAtEnd, thickness,
                bottomTabs, leftTabs, rightTabs, cutouts, "W" + index);
    }

    /**
     * @param startOffset distance (in scaled mm) from the wall centre-line start
     *                    to the panel's local x=0.  Non-zero when the start end of
     *                    the wall has a finger-joint connection and the panel is
     *                    inset by half a material thickness.
     */
    private List<double[]> findCutouts(Wall wall, double sf, double scaledLength,
                                       double scaledHeight, double startOffset) {
        List<double[]> cutouts = new ArrayList<>();
        if (home.getFurniture() == null) return cutouts;

        double sxCm = wall.getXStart();
        double syCm = wall.getYStart();
        double exCm = wall.getXEnd();
        double eyCm = wall.getYEnd();
        double Lcm = Math.hypot(exCm - sxCm, eyCm - syCm);
        if (Lcm <= 0) return cutouts;
        double ux = (exCm - sxCm) / Lcm;
        double uy = (eyCm - syCm) / Lcm;
        // Tolerate pieces sitting up to 1.5× the wall thickness off-axis
        // (some doors have decorative frames slightly wider than the wall).
        double perpToleranceCm = wall.getThickness() * 1.5 + 5.0;

        Level wallLevel = wall.getLevel();
        for (HomePieceOfFurniture p : home.getFurniture()) {
            if (!p.isDoorOrWindow()) continue;
            if (wallLevel != null && p.getLevel() != null && p.getLevel() != wallLevel) continue;
            if (wallLevel == null && targetLevel != null && p.getLevel() != targetLevel) continue;

            // Project center to wall axis. Reject if not roughly on this wall.
            double cdx = p.getX() - sxCm;
            double cdy = p.getY() - syCm;
            double centerAlongCm = cdx * ux + cdy * uy;
            double centerPerpCm  = -cdx * uy + cdy * ux;
            if (centerAlongCm < -1 || centerAlongCm > Lcm + 1) continue;
            if (Math.abs(centerPerpCm) > perpToleranceCm) continue;

            // Use the piece's true rotated footprint to derive the along-wall span.
            float[][] corners = p.getPoints();
            if (corners == null || corners.length == 0) continue;
            double minAlongCm = Double.POSITIVE_INFINITY;
            double maxAlongCm = Double.NEGATIVE_INFINITY;
            for (float[] corner : corners) {
                double a = (corner[0] - sxCm) * ux + (corner[1] - syCm) * uy;
                if (a < minAlongCm) minAlongCm = a;
                if (a > maxAlongCm) maxAlongCm = a;
            }

            // Convert to panel-local x coordinates: subtract startOffset so that
            // x=0 corresponds to the panel's left edge (inset from the centre-line
            // start), not to the wall centre-line start.
            double xMin = minAlongCm * CM_TO_MM * sf - startOffset;
            double xMax = maxAlongCm * CM_TO_MM * sf - startOffset;
            double yMin = p.getElevation() * CM_TO_MM * sf;
            double yMax = (p.getElevation() + p.getHeight()) * CM_TO_MM * sf;

            // Clamp to wall bounds.
            if (xMin < 0) xMin = 0;
            if (xMax > scaledLength) xMax = scaledLength;
            if (yMin < 0) yMin = 0;
            if (yMax > scaledHeight) yMax = scaledHeight;
            if (xMax - xMin < 0.1 || yMax - yMin < 0.1) continue;

            cutouts.add(new double[] { xMin, yMin, xMax, yMax });
        }
        return cutouts;
    }

    /** Remove bottom tabs that sit under a cutout reaching the floor (doors). */
    private static List<TabPattern.Span> filterBottomTabsByCutouts(
            List<TabPattern.Span> tabs, List<double[]> cutouts) {
        if (cutouts.isEmpty()) return tabs;
        List<TabPattern.Span> out = new ArrayList<>(tabs.size());
        final double FLOOR_EPS = 0.5; // mm
        for (TabPattern.Span tab : tabs) {
            boolean covered = false;
            for (double[] cut : cutouts) {
                if (cut[1] > FLOOR_EPS) continue; // window, doesn't reach floor
                if (tab.end > cut[0] && tab.start < cut[2]) { covered = true; break; }
            }
            if (!covered) out.add(tab);
        }
        return out;
    }

    private static boolean isPrimary(Wall self, Wall other) {
        return System.identityHashCode(self) < System.identityHashCode(other);
    }

    /** Minimum height difference (mm) for a wall to be considered sloping. */
    private static final double SLOPING_THRESHOLD_MM = 0.5;

    private static double rawWallLengthMm(Wall wall) {
        double dx = (wall.getXEnd() - wall.getXStart()) * CM_TO_MM;
        double dy = (wall.getYEnd() - wall.getYStart()) * CM_TO_MM;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double rawWallHeightMm(Wall wall, Home home) {
        Float h = wall.getHeight();
        if (h != null) {
            return h * CM_TO_MM;
        }
        Level lvl = wall.getLevel();
        if (lvl != null) {
            return lvl.getHeight() * CM_TO_MM;
        }
        return home.getWallHeight() * CM_TO_MM;
    }

    /** Height of the wall at its end point, in mm.  Returns the same value as
     *  {@link #rawWallHeightMm} when the wall does not slope. */
    private static double rawWallEndHeightMm(Wall wall, Home home) {
        Float h = wall.getHeightAtEnd();
        if (h != null) {
            return h * CM_TO_MM;
        }
        return rawWallHeightMm(wall, home);
    }

    /** Returns true when the wall has a measurably different height at each end. */
    private static boolean isSloping(Wall wall, Home home) {
        return Math.abs(rawWallHeightMm(wall, home) - rawWallEndHeightMm(wall, home)) > SLOPING_THRESHOLD_MM;
    }

    /**
     * Returns the scaled tab height to use at the joint between {@code wall}
     * (at its start end when {@code atStart=true}, end end otherwise) and
     * {@code neighbor}.  Uses the minimum of the two walls' heights at the
     * junction so that the tab pattern fits within both panels.
     */
    private double computeJointHeightScaled(Wall wall, boolean atStart,
                                            Wall neighbor, double sf) {
        double selfH = atStart ? rawWallHeightMm(wall, home)
                               : rawWallEndHeightMm(wall, home);
        // Determine which end of the neighbor touches us.
        boolean neighborAtStart = (neighbor.getWallAtStart() == wall);
        double neighborH = neighborAtStart ? rawWallHeightMm(neighbor, home)
                                           : rawWallEndHeightMm(neighbor, home);
        return Math.min(selfH, neighborH) * sf;
    }

    // ---- floor geometry ------------------------------------------------------


    /**
     * Second pass: for every T-junction (where an inner wall's endpoint meets the
     * body of an outer wall, not at the outer wall's own endpoints), cut matching
     * notch slots through the outer wall's face so that the inner wall's finger
     * tabs have somewhere to fit.
     *
     * <p>Detection: wall B is a T-junction inner wall relative to outer wall A when
     * {@code B.getWallAtStart() == A} (or {@code B.getWallAtEnd() == A}) AND
     * neither {@code A.getWallAtStart()} nor {@code A.getWallAtEnd()} equals B.
     * The latter condition distinguishes a T-junction from a regular corner where
     * both walls mutually reference each other.</p>
     */
    private void addTJunctionNotches(List<Wall> walls, Set<Wall> wallSet,
                                     Map<Wall, WallPiece> pieces) {
        if (options.smoothConnections) return;
        double sf = scaleFactor();
        double t  = options.materialThickness;

        for (Wall outer : walls) {
            WallPiece outerPiece = pieces.get(outer);

            double oxs = outer.getXStart();
            double oys = outer.getYStart();
            double oxe = outer.getXEnd();
            double oye = outer.getYEnd();
            double outerRawLen = Math.hypot(oxe - oxs, oye - oys);
            if (outerRawLen <= 0) continue;
            double ux = (oxe - oxs) / outerRawLen;
            double uy = (oye - oys) / outerRawLen;

            // Outer panel's x=0 is inset from its centre-line start whenever it
            // has a left-edge finger-joint connection.
            double outerStartOffset = (outerPiece.leftTabs != null) ? t / 2.0 : 0;

            for (Wall inner : walls) {
                if (inner == outer) continue;
                if (!wallSet.contains(inner)) continue;

                // T-junction: inner's start connects to outer's body.
                boolean innerStartOnOuter =
                        inner.getWallAtStart() == outer
                        && outer.getWallAtStart() != inner
                        && outer.getWallAtEnd()   != inner;

                // T-junction: inner's end connects to outer's body.
                boolean innerEndOnOuter =
                        inner.getWallAtEnd() == outer
                        && outer.getWallAtStart() != inner
                        && outer.getWallAtEnd()   != inner;

                if (!innerStartOnOuter && !innerEndOnOuter) continue;

                WallPiece innerPiece = pieces.get(inner);
                // The tabs on the inner wall's connecting edge are the ones that
                // must pass through slots in the outer wall.
                List<TabPattern.Span> innerEdgeTabs =
                        innerStartOnOuter ? innerPiece.leftTabs : innerPiece.rightTabs;
                if (innerEdgeTabs == null || innerEdgeTabs.isEmpty()) continue;

                // Project the inner wall's connection point onto the outer wall's
                // axis to get the along-wall coordinate in cm.
                double cx = innerStartOnOuter ? inner.getXStart() : inner.getXEnd();
                double cy = innerStartOnOuter ? inner.getYStart() : inner.getYEnd();
                double alongCm = (cx - oxs) * ux + (cy - oys) * uy;

                // Convert to outer panel-local x (subtract panel start offset).
                double xCenter = alongCm * CM_TO_MM * sf - outerStartOffset;

                double half = t / 2.0;
                for (TabPattern.Span span : innerEdgeTabs) {
                    outerPiece.cutouts.add(new double[] {
                            xCenter - half, span.start,
                            xCenter + half, span.end
                    });
                }
            }
        }
    }

    /**
     * Build one or more {@link FloorPiece} tiles for the floor baseplate.
     *
     * <p>When no board size is configured, or the floor fits on a single
     * board, a single rectangular tile is returned.  When the floor is too
     * large AND {@link ExportOptions#splitFloor} is true the floor is split
     * along a regular grid of seams; adjacent tiles interlock via
     * box-joint style "puzzle" tabs so they can be assembled after cutting.
     * When splitting is requested but disabled ({@code splitFloor=false}) the
     * oversized single tile is still returned and {@code result.boardWarning}
     * is set to a human-readable message.
     *
     * @param gridSize  output parameter – {@code gridSize[0]} receives the
     *                  number of columns, {@code gridSize[1]} the number of rows
     *                  in the floor tile grid (both 1 for a single tile).
     */
    private List<FloorPiece> buildFloorTiles(List<Wall> walls, Map<Wall, WallPiece> pieces,
                                              LayoutResult result, int[] gridSize) {
        double sf = scaleFactor();

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Wall w : walls) {
            for (float[] p : w.getPoints()) {
                double x = p[0] * CM_TO_MM * sf;
                double y = p[1] * CM_TO_MM * sf;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        double m = options.floorMargin;
        minX -= m; minY -= m; maxX += m; maxY += m;
        double floorW = maxX - minX;
        double floorH = maxY - minY;

        List<double[][]> allSlots = buildAllSlots(walls, pieces, sf);

        double bw = options.boardWidth;
        double bh = options.boardHeight;
        boolean tooWide = bw > 0 && floorW > bw + SEAM_TOLERANCE;
        boolean tooTall = bh > 0 && floorH > bh + SEAM_TOLERANCE;

        if (!tooWide && !tooTall) {
            gridSize[0] = 1; gridSize[1] = 1;
            return Collections.singletonList(
                    new FloorPiece(buildRectOutline(minX, minY, maxX, maxY), allSlots));
        }

        if (!options.splitFloor) {
            result.boardWarning = String.format(Locale.US,
                    "Floor (%.0f \u00d7 %.0f mm) exceeds board size (%s \u00d7 %s mm). "
                            + "Enable \"Split floor with puzzle joints if too large\" to split automatically.",
                    floorW, floorH, fmtBoardDim(bw), fmtBoardDim(bh));
            gridSize[0] = 1; gridSize[1] = 1;
            return Collections.singletonList(
                    new FloorPiece(buildRectOutline(minX, minY, maxX, maxY), allSlots));
        }

        // Split into a grid of tiles with interlocking puzzle-joint seams.
        List<Double> xCoords = buildSeamCoords(minX, maxX, bw);
        List<Double> yCoords = buildSeamCoords(minY, maxY, bh);
        int numCols = xCoords.size() - 1;
        int numRows = yCoords.size() - 1;
        gridSize[0] = numCols;
        gridSize[1] = numRows;

        double tabDepth = options.materialThickness;
        List<FloorPiece> tiles = new ArrayList<>();
        for (int row = 0; row < numRows; row++) {
            for (int col = 0; col < numCols; col++) {
                double x0 = xCoords.get(col);
                double x1 = xCoords.get(col + 1);
                double y0 = yCoords.get(row);
                double y1 = yCoords.get(row + 1);

                boolean leftSeam   = col > 0;
                boolean rightSeam  = col < numCols - 1;
                boolean topSeam    = row > 0;
                boolean bottomSeam = row < numRows - 1;

                List<double[]> outline = buildTileOutline(
                        x0, y0, x1, y1,
                        leftSeam, rightSeam, topSeam, bottomSeam,
                        tabDepth, options.tabWidth);

                List<double[][]> tileSlots = filterSlotsForTile(allSlots, x0, y0, x1, y1);
                tiles.add(new FloorPiece(outline, tileSlots));
            }
        }
        return tiles;
    }

    /** Extract the wall-tab slot rectangles from all walls (in world / scaled coords). */
    private List<double[][]> buildAllSlots(List<Wall> walls, Map<Wall, WallPiece> pieces, double sf) {
        List<double[][]> slots = new ArrayList<>();
        double t = options.materialThickness;
        for (Wall wall : walls) {
            WallPiece piece = pieces.get(wall);
            // Wall start/end in scaled world coords (centre-line endpoints).
            double sx = wall.getXStart() * CM_TO_MM * sf;
            double sy = wall.getYStart() * CM_TO_MM * sf;
            double ex = wall.getXEnd()   * CM_TO_MM * sf;
            double ey = wall.getYEnd()   * CM_TO_MM * sf;
            // Unit vector along the wall from the raw (centre-to-centre) distance.
            // We must NOT divide by piece.length here because piece.length is the
            // corrected panel length (shorter than the centre-to-centre distance
            // whenever the wall has finger-joint connections at its ends).
            double rawLen = Math.hypot(ex - sx, ey - sy);
            if (rawLen <= 0) continue;
            double dx = (ex - sx) / rawLen;
            double dy = (ey - sy) / rawLen;
            double nx = -dy;
            double ny =  dx;

            // The panel's x=0 is inset from the centre-line start by half a
            // material thickness whenever the start end has a finger-joint
            // connection (piece.leftTabs != null).  Shift the slot origin to
            // match the panel's physical starting position in world space.
            double startOffset = (piece.leftTabs != null) ? t / 2.0 : 0;
            double pSx = sx + dx * startOffset;
            double pSy = sy + dy * startOffset;

            for (TabPattern.Span span : piece.bottomTabs) {
                double a = span.start;
                double b = span.end;
                double half = t / 2.0;
                double[][] rect = new double[][] {
                        { pSx + dx * a + nx * (-half), pSy + dy * a + ny * (-half) },
                        { pSx + dx * b + nx * (-half), pSy + dy * b + ny * (-half) },
                        { pSx + dx * b + nx * (+half), pSy + dy * b + ny * (+half) },
                        { pSx + dx * a + nx * (+half), pSy + dy * a + ny * (+half) },
                };
                slots.add(rect);
            }
        }
        return slots;
    }

    /** Format a board dimension for display: "none" when unconstrained (≤ 0), else "%.0f". */
    private static String fmtBoardDim(double mm) {
        return mm > 0 ? String.format(Locale.US, "%.0f", mm) : "none";
    }

    /**
     * Compute the boundary coordinates (start, seam1, seam2, …, end) for one
     * axis given a board dimension.  If {@code boardSize} is ≤ 0 the result is
     * just {@code [start, end]}.
     */
    private static List<Double> buildSeamCoords(double start, double end, double boardSize) {
        List<Double> coords = new ArrayList<>();
        coords.add(start);
        if (boardSize > 0 && end - start > boardSize + SEAM_TOLERANCE) {
            double pos = start + boardSize;
            while (pos < end - SEAM_TOLERANCE) {
                coords.add(pos);
                pos += boardSize;
            }
        }
        coords.add(end);
        return coords;
    }

    /** Build the outline of a plain axis-aligned rectangle (4 corners, no tabs). */
    private static List<double[]> buildRectOutline(double x0, double y0, double x1, double y1) {
        List<double[]> pts = new ArrayList<>(4);
        pts.add(new double[] { x0, y0 });
        pts.add(new double[] { x1, y0 });
        pts.add(new double[] { x1, y1 });
        pts.add(new double[] { x0, y1 });
        return pts;
    }

    /**
     * Build the closed-polygon outline for one floor tile, adding puzzle-joint
     * (box-joint style) tab protrusions on the seam edges.
     *
     * <p>Convention used throughout:
     * <ul>
     *   <li><b>Right seam (male)</b> – tabs protrude to {@code x = x1 + tabDepth}.
     *   <li><b>Left seam (female)</b> – the adjacent tile's male tabs insert here;
     *       the left edge outline traces the same {@code x1 + tabDepth} path,
     *       just traversed bottom-to-top, so the laser cuts the same shared line.</li>
     *   <li><b>Bottom seam (male)</b> – tabs protrude to {@code y = y1 + tabDepth}.</li>
     *   <li><b>Top seam (female)</b> – mirror of bottom, traversed right-to-left.</li>
     * </ul>
     * This means both sides of a seam produce identical cut paths and the resulting
     * pieces interlock perfectly without any fitting adjustment.
     */
    private List<double[]> buildTileOutline(
            double x0, double y0, double x1, double y1,
            boolean leftSeam, boolean rightSeam, boolean topSeam, boolean bottomSeam,
            double tabDepth, double tabWidth) {

        List<double[]> pts = new ArrayList<>();

        // Top edge: left → right (topSeam = female – same geometry as male, opposite traversal)
        List<double[]> top = topSeam
                ? buildHSeamPoints(y0, x0, x1, tabDepth, tabWidth, true)
                : buildRectEdgeH(x0, x1, y0);
        pts.addAll(top);

        // Right edge: top → bottom (rightSeam = male)
        List<double[]> right = rightSeam
                ? buildVSeamPoints(x1, y0, y1, tabDepth, tabWidth, true)
                : buildRectEdgeV(x1, y0, y1);
        pts.addAll(right.subList(1, right.size())); // first point already added

        // Bottom edge: right → left (bottomSeam = male)
        List<double[]> bottom = bottomSeam
                ? buildHSeamPoints(y1, x0, x1, tabDepth, tabWidth, false)
                : buildRectEdgeH(x1, x0, y1);
        pts.addAll(bottom.subList(1, bottom.size()));

        // Left edge: bottom → top (leftSeam = female)
        List<double[]> left = leftSeam
                ? buildVSeamPoints(x0, y0, y1, tabDepth, tabWidth, false)
                : buildRectEdgeV(x0, y1, y0);
        pts.addAll(left.subList(1, left.size()));

        // Drop closing point that duplicates the start (SVG Z closes the path).
        if (!pts.isEmpty()) {
            double[] first = pts.get(0);
            double[] last  = pts.get(pts.size() - 1);
            if (Math.abs(first[0] - last[0]) < COORD_EPSILON && Math.abs(first[1] - last[1]) < COORD_EPSILON) {
                pts.remove(pts.size() - 1);
            }
        }
        return pts;
    }

    /** Two-point horizontal edge: (xA, y) → (xB, y). */
    private static List<double[]> buildRectEdgeH(double xA, double xB, double y) {
        List<double[]> pts = new ArrayList<>(2);
        pts.add(new double[] { xA, y });
        pts.add(new double[] { xB, y });
        return pts;
    }

    /** Two-point vertical edge: (x, yA) → (x, yB). */
    private static List<double[]> buildRectEdgeV(double x, double yA, double yB) {
        List<double[]> pts = new ArrayList<>(2);
        pts.add(new double[] { x, yA });
        pts.add(new double[] { x, yB });
        return pts;
    }

    /**
     * Generate the outline points for one side of a <em>vertical</em> puzzle seam
     * at {@code x = xCut}, running from {@code y = yA} to {@code y = yB}.
     * Tabs protrude to {@code x = xCut + tabDepth}.
     *
     * <p>When {@code topToBottom=true} the path goes yA→yB (male right edge).
     * When {@code topToBottom=false} it goes yB→yA (female left edge of the
     * adjacent tile).  Both produce the exact same set of x/y coordinates; the
     * reversal means the laser cuts the identical shared path in both cases.
     */
    private static List<double[]> buildVSeamPoints(double xCut, double yA, double yB,
                                                    double tabDepth, double tabWidth,
                                                    boolean topToBottom) {
        double length = yB - yA;
        List<TabPattern.Span> tabs = TabPattern.compute(length, tabWidth, false);
        List<double[]> pts = new ArrayList<>();

        if (topToBottom) {
            pts.add(new double[] { xCut, yA });
            double cur = yA;
            for (TabPattern.Span span : tabs) {
                double sa = yA + span.start;
                double sb = yA + span.end;
                if (sa > cur) pts.add(new double[] { xCut, sa });
                pts.add(new double[] { xCut + tabDepth, sa });
                pts.add(new double[] { xCut + tabDepth, sb });
                pts.add(new double[] { xCut, sb });
                cur = sb;
            }
            if (cur < yB) pts.add(new double[] { xCut, yB });
        } else {
            pts.add(new double[] { xCut, yB });
            double cur = yB;
            for (int i = tabs.size() - 1; i >= 0; i--) {
                TabPattern.Span span = tabs.get(i);
                double sa = yA + span.start;
                double sb = yA + span.end;
                if (sb < cur) pts.add(new double[] { xCut, sb });
                pts.add(new double[] { xCut + tabDepth, sb });
                pts.add(new double[] { xCut + tabDepth, sa });
                pts.add(new double[] { xCut, sa });
                cur = sa;
            }
            if (cur > yA) pts.add(new double[] { xCut, yA });
        }
        return pts;
    }

    /**
     * Generate the outline points for one side of a <em>horizontal</em> puzzle
     * seam at {@code y = yCut}, running from {@code x = xA} to {@code x = xB}.
     * Tabs protrude to {@code y = yCut + tabDepth}.
     *
     * <p>{@code leftToRight=true} → xA→xB (male bottom edge).
     * {@code leftToRight=false} → xB→xA (female top edge of the adjacent tile).
     */
    private static List<double[]> buildHSeamPoints(double yCut, double xA, double xB,
                                                    double tabDepth, double tabWidth,
                                                    boolean leftToRight) {
        double length = xB - xA;
        List<TabPattern.Span> tabs = TabPattern.compute(length, tabWidth, false);
        List<double[]> pts = new ArrayList<>();

        if (leftToRight) {
            pts.add(new double[] { xA, yCut });
            double cur = xA;
            for (TabPattern.Span span : tabs) {
                double sa = xA + span.start;
                double sb = xA + span.end;
                if (sa > cur) pts.add(new double[] { sa, yCut });
                pts.add(new double[] { sa, yCut + tabDepth });
                pts.add(new double[] { sb, yCut + tabDepth });
                pts.add(new double[] { sb, yCut });
                cur = sb;
            }
            if (cur < xB) pts.add(new double[] { xB, yCut });
        } else {
            pts.add(new double[] { xB, yCut });
            double cur = xB;
            for (int i = tabs.size() - 1; i >= 0; i--) {
                TabPattern.Span span = tabs.get(i);
                double sa = xA + span.start;
                double sb = xA + span.end;
                if (sb < cur) pts.add(new double[] { sb, yCut });
                pts.add(new double[] { sb, yCut + tabDepth });
                pts.add(new double[] { sa, yCut + tabDepth });
                pts.add(new double[] { sa, yCut });
                cur = sa;
            }
            if (cur > xA) pts.add(new double[] { xA, yCut });
        }
        return pts;
    }

    /**
     * Return only the slots whose centre point falls within the tile bounds
     * {@code [x0, x1] × [y0, y1]}.  Since slots are very narrow (≈ material
     * thickness), this is equivalent to checking whether the slot belongs to
     * this tile.
     */
    private static List<double[][]> filterSlotsForTile(List<double[][]> allSlots,
                                                        double x0, double y0,
                                                        double x1, double y1) {
        List<double[][]> out = new ArrayList<>();
        for (double[][] slot : allSlots) {
            if (slot.length == 0) continue;
            double cx = 0, cy = 0;
            for (double[] p : slot) { cx += p[0]; cy += p[1]; }
            cx /= slot.length;
            cy /= slot.length;
            // Half-open intervals [x0, x1) × [y0, y1) ensure a slot whose centre
            // sits exactly on a seam is assigned to exactly one tile.
            if (cx >= x0 && cx < x1 && cy >= y0 && cy < y1) {
                out.add(slot);
            }
        }
        return out;
    }

    // ---- layout + write ------------------------------------------------------

    /**
     * A piece (floor tile or wall) normalized to a local (0,0) origin, ready
     * for board-packing.
     */
    private static final class BoardItem {
        final List<List<double[]>> shapes;
        final List<LayoutResult.Label> labels;
        final double width;
        final double height;

        BoardItem(List<List<double[]>> shapes, List<LayoutResult.Label> labels,
                  double width, double height) {
            this.shapes = shapes;
            this.labels = labels;
            this.width  = width;
            this.height = height;
        }
    }

    /**
     * Extract all pieces (floor tiles then walls) as board-packable
     * {@link BoardItem}s, each normalized to a local (0,0) origin.
     * Wall-identifier labels for the assembler are embedded into the
     * appropriate floor-tile item.
     */
    private List<BoardItem> gatherItems(List<Wall> walls,
                                         Map<Wall, WallPiece> pieces,
                                         List<FloorPiece> floorTiles) {
        double sf = scaleFactor();
        int numTiles = floorTiles.size();
        List<BoardItem> items = new ArrayList<>();

        // ---- floor tiles ------------------------------------------------
        for (int i = 0; i < numTiles; i++) {
            FloorPiece tile = floorTiles.get(i);
            double w = tile.bounds[2] - tile.bounds[0];
            double h = tile.bounds[3] - tile.bounds[1];
            double offX = -tile.bounds[0];
            double offY = -tile.bounds[1];

            List<List<double[]>> shapes = new ArrayList<>();
            shapes.add(translated(tile.outline, offX, offY));
            for (double[][] slot : tile.slots) {
                shapes.add(translated(asPolygon(slot), offX, offY));
            }

            List<LayoutResult.Label> labels = new ArrayList<>();
            String floorLabel = numTiles > 1
                    ? "FLOOR " + (i + 1) + "/" + numTiles
                    : "FLOOR";
            labels.add(new LayoutResult.Label(floorLabel, 4, 8, 6));

            // Wall midpoint labels on this floor tile.
            for (Map.Entry<Wall, WallPiece> entry : pieces.entrySet()) {
                Wall wall  = entry.getKey();
                WallPiece piece = entry.getValue();
                double sx = wall.getXStart() * CM_TO_MM * sf;
                double sy = wall.getYStart() * CM_TO_MM * sf;
                double ex = wall.getXEnd()   * CM_TO_MM * sf;
                double ey = wall.getYEnd()   * CM_TO_MM * sf;
                double midWX = (sx + ex) / 2.0;
                double midWY = (sy + ey) / 2.0;
                if (midWX >= tile.bounds[0] && midWX <= tile.bounds[2]
                        && midWY >= tile.bounds[1] && midWY <= tile.bounds[3]) {
                    labels.add(new LayoutResult.Label(
                            piece.label, midWX + offX, midWY + offY, 4));
                }
            }

            items.add(new BoardItem(shapes, labels, w, h));
        }

        // ---- walls -------------------------------------------------------
        for (WallPiece piece : pieces.values()) {
            double[] b = piece.bounds();
            double w  = b[2] - b[0];
            double h  = b[3] - b[1];
            double offX = -b[0];
            double offY = -b[1];

            List<List<double[]>> shapes = new ArrayList<>();
            shapes.add(translated(piece.outline(), offX, offY));
            for (double[] cut : piece.cutouts) {
                List<double[]> rect = new ArrayList<>(4);
                rect.add(new double[] { cut[0] + offX, cut[1] + offY });
                rect.add(new double[] { cut[2] + offX, cut[1] + offY });
                rect.add(new double[] { cut[2] + offX, cut[3] + offY });
                rect.add(new double[] { cut[0] + offX, cut[3] + offY });
                shapes.add(rect);
            }

            List<LayoutResult.Label> labels = new ArrayList<>();
            labels.add(new LayoutResult.Label(piece.label, 2, 8, 6));

            items.add(new BoardItem(shapes, labels, w, h));
        }

        return items;
    }

    /**
     * Pack items onto boards using a simple strip-packing algorithm.
     *
     * <p>When no board constraints are set (boardWidth or boardHeight ≤ 0),
     * all items are placed on a single unlimited board using vertical stacking
     * that matches the legacy layout order.  When board constraints are active,
     * items are packed greedily onto {@code boardWidth × boardHeight} boards,
     * starting a new row when the current row would overflow the board width
     * and a new board when the current column of rows would overflow the board
     * height.
     *
     * @return one {@link LayoutResult} per board; pieces in board-local coords
     *         (origin = (0,0) in the usable area, before the spacing margin).
     */
    private List<LayoutResult> packOntoBoards(List<BoardItem> items) {
        double spacing  = options.layoutSpacing;
        double bw       = options.boardWidth;
        double bh       = options.boardHeight;
        boolean constrained = bw > 0 && bh > 0;
        double usableW = constrained ? bw - 2 * spacing : Double.MAX_VALUE;
        double usableH = constrained ? bh - 2 * spacing : Double.MAX_VALUE;

        List<LayoutResult> boards = new ArrayList<>();
        LayoutResult current = new LayoutResult();
        boards.add(current);
        double cursorX = 0;
        double cursorY = 0;
        double rowMaxH = 0;
        double maxW    = 0;

        for (BoardItem item : items) {
            double pw = item.width;
            double ph = item.height;

            // Start a new row if this item doesn't fit horizontally.
            // SEAM_TOLERANCE accounts for small floating-point accumulated errors
            // in cursor positions so that items at precisely the board edge are
            // not incorrectly wrapped to a new row.
            if (cursorX > 0 && cursorX + pw > usableW + SEAM_TOLERANCE) {
                double rowW = cursorX - spacing;
                if (rowW > maxW) maxW = rowW;
                cursorX = 0;
                cursorY += rowMaxH + spacing;
                rowMaxH = 0;
            }

            // Start a new board if this item doesn't fit vertically.
            if (constrained && cursorY + ph > usableH + SEAM_TOLERANCE) {
                double rowW = cursorX > 0 ? cursorX - spacing : 0;
                if (rowW > maxW) maxW = rowW;
                current.width  = maxW;
                current.height = Math.max(0, cursorY + rowMaxH);
                current = new LayoutResult();
                boards.add(current);
                cursorX = 0;
                cursorY = 0;
                rowMaxH = 0;
                maxW    = 0;
            }

            // Place item at (cursorX, cursorY) on the current board.
            for (List<double[]> shape : item.shapes) {
                current.shapes.add(translated(shape, cursorX, cursorY));
            }
            for (LayoutResult.Label label : item.labels) {
                current.labels.add(new LayoutResult.Label(
                        label.text, label.x + cursorX, label.y + cursorY, label.size));
            }

            cursorX += pw + spacing;
            if (ph > rowMaxH) rowMaxH = ph;
        }

        // Finalize last board.
        double rowW = cursorX > 0 ? cursorX - spacing : 0;
        if (rowW > maxW) maxW = rowW;
        current.width  = maxW;
        current.height = Math.max(0, cursorY + rowMaxH);

        return boards;
    }

    /**
     * Merge per-board results into a single combined {@link LayoutResult}.
     *
     * <p>Boards are stacked vertically with {@code layoutSpacing} between them.
     * When board constraints are set, a board outline rectangle is added at
     * each board's position (stored in {@link LayoutResult#boardRects}) and
     * each board's content is inset by {@code layoutSpacing}.
     */
    private void buildCombinedLayout(List<LayoutResult> boards, LayoutResult result) {
        double spacing    = options.layoutSpacing;
        double bw         = options.boardWidth;
        double bh         = options.boardHeight;
        boolean constrained = bw > 0 && bh > 0;
        double offsetY = 0;
        double maxW    = 0;

        for (LayoutResult board : boards) {
            double displayW = constrained ? bw : board.width;
            double displayH = constrained ? bh : board.height;

            if (constrained) {
                result.boardRects.add(new double[] { 0, offsetY, displayW, displayH });
            }

            // Items are placed at board-local coords starting at (0,0).
            // In the combined view, shift them into the board's bounding box
            // (with a spacing-sized margin when constrained).
            double itemOffX = constrained ? spacing : 0;
            double itemOffY = offsetY + (constrained ? spacing : 0);

            for (List<double[]> shape : board.shapes) {
                result.shapes.add(translated(shape, itemOffX, itemOffY));
            }
            for (LayoutResult.Label label : board.labels) {
                result.labels.add(new LayoutResult.Label(
                        label.text, label.x + itemOffX, label.y + itemOffY, label.size));
            }

            if (displayW > maxW) maxW = displayW;
            offsetY += displayH + spacing;
        }

        result.width  = maxW;
        result.height = Math.max(0, offsetY - spacing);
    }

    /**
     * Translate all pieces into their final SVG positions and store them in
     * {@code result}.
     *
     * <p>Floor tiles are arranged in a grid ({@code numCols} columns, as many
     * rows as needed).  Walls are stacked vertically below the floor grid.
     * Wall-identifier labels are placed on each floor tile at the midpoint of
     * the corresponding wall so the assembler knows which panel goes where.
     */
    private void composeLayout(Map<Wall, WallPiece> pieces,
                                List<FloorPiece> floorTiles, int numCols,
                                LayoutResult result) {
        double spacing = options.layoutSpacing;
        double sf      = scaleFactor();
        double cursorY = 0;
        double maxW    = 0;

        int numTiles = floorTiles.size();
        int numRows  = numCols > 0 ? (numTiles + numCols - 1) / numCols : 1;

        // tilePos[i] = { layoutX, layoutY, worldMinX, worldMinY }
        // Used below to map wall world-coords to layout-coords for labels.
        double[][] tilePos = new double[numTiles][4];

        // ---- floor tile grid ------------------------------------------------
        for (int row = 0; row < numRows; row++) {
            double cursorX  = 0;
            double rowMaxH  = 0;
            for (int col = 0; col < numCols; col++) {
                int i = row * numCols + col;
                if (i >= numTiles) break;
                FloorPiece tile = floorTiles.get(i);

                double tw = tile.bounds[2] - tile.bounds[0];
                double th = tile.bounds[3] - tile.bounds[1];

                double tOffX = cursorX - tile.bounds[0];
                double tOffY = cursorY - tile.bounds[1];

                result.shapes.add(translated(tile.outline, tOffX, tOffY));
                for (double[][] slot : tile.slots) {
                    result.shapes.add(translated(asPolygon(slot), tOffX, tOffY));
                }

                String floorLabel = numTiles > 1
                        ? "FLOOR " + (i + 1) + "/" + numTiles
                        : "FLOOR";
                result.labels.add(new LayoutResult.Label(floorLabel, cursorX + 4, cursorY + 8, 6));

                tilePos[i][0] = cursorX;        // layout X origin of this tile
                tilePos[i][1] = cursorY;        // layout Y origin of this tile
                tilePos[i][2] = tile.bounds[0]; // world minX
                tilePos[i][3] = tile.bounds[1]; // world minY

                cursorX += tw + spacing;
                if (th > rowMaxH) rowMaxH = th;
            }
            if (cursorX - spacing > maxW) maxW = cursorX - spacing;
            cursorY += rowMaxH + spacing;
        }

        // ---- wall-identifier labels on the floor tiles ----------------------
        // Each wall's label is placed at the wall's midpoint on whichever tile
        // that midpoint falls on, so the assembler can match panels to slots.
        for (Map.Entry<Wall, WallPiece> entry : pieces.entrySet()) {
            Wall wall = entry.getKey();
            WallPiece piece = entry.getValue();
            double sx = wall.getXStart() * CM_TO_MM * sf;
            double sy = wall.getYStart() * CM_TO_MM * sf;
            double ex = wall.getXEnd()   * CM_TO_MM * sf;
            double ey = wall.getYEnd()   * CM_TO_MM * sf;
            double midWX = (sx + ex) / 2.0;
            double midWY = (sy + ey) / 2.0;

            for (int i = 0; i < numTiles; i++) {
                FloorPiece tile = floorTiles.get(i);
                if (midWX >= tile.bounds[0] && midWX <= tile.bounds[2]
                        && midWY >= tile.bounds[1] && midWY <= tile.bounds[3]) {
                    double labelX = midWX + (tilePos[i][0] - tilePos[i][2]);
                    double labelY = midWY + (tilePos[i][1] - tilePos[i][3]);
                    result.labels.add(new LayoutResult.Label(piece.label, labelX, labelY, 4));
                    break;
                }
            }
        }

        // ---- walls stacked vertically below the floor grid ------------------
        for (WallPiece piece : pieces.values()) {
            double[] b   = piece.bounds();
            double offX  = -b[0];
            double offY  = cursorY - b[1];
            result.shapes.add(translated(piece.outline(), offX, offY));
            for (double[] cut : piece.cutouts) {
                List<double[]> rect = new ArrayList<>(4);
                rect.add(new double[] { cut[0] + offX, cut[1] + offY });
                rect.add(new double[] { cut[2] + offX, cut[1] + offY });
                rect.add(new double[] { cut[2] + offX, cut[3] + offY });
                rect.add(new double[] { cut[0] + offX, cut[3] + offY });
                result.shapes.add(rect);
            }
            double pw = b[2] - b[0];
            double ph = b[3] - b[1];
            if (pw > maxW) maxW = pw;
            result.labels.add(new LayoutResult.Label(piece.label, 2, cursorY + 8, 6));
            cursorY += ph + spacing;
        }

        result.width  = maxW;
        result.height = Math.max(0, cursorY - spacing);
    }

    private void writeSvg(File outputFile, LayoutResult layout) throws IOException {
        SVGWriter svg = new SVGWriter(options.svgStrokeWidth, options.cutStrokeColor);
        for (double[] r : layout.boardRects) {
            svg.addBoardOutline(r[0], r[1], r[2], r[3]);
        }
        for (List<double[]> shape : layout.shapes) {
            svg.addPolygon(shape, 0, 0);
        }
        for (LayoutResult.Label label : layout.labels) {
            svg.addLabel(label.text, label.x, label.y, label.size);
        }
        svg.write(outputFile);
    }

    /**
     * Write a single per-board SVG.  A board outline is drawn at (0,0) and
     * items are inset by {@link ExportOptions#layoutSpacing}.
     */
    private void writeSvgBoard(File outputFile, LayoutResult boardLayout) throws IOException {
        double bw      = options.boardWidth;
        double bh      = options.boardHeight;
        double spacing = options.layoutSpacing;

        SVGWriter svg = new SVGWriter(options.svgStrokeWidth, options.cutStrokeColor);
        svg.addBoardOutline(0, 0, bw, bh);
        for (List<double[]> shape : boardLayout.shapes) {
            svg.addPolygon(translated(shape, spacing, spacing), 0, 0);
        }
        for (LayoutResult.Label label : boardLayout.labels) {
            svg.addLabel(label.text, label.x + spacing, label.y + spacing, label.size);
        }
        svg.write(outputFile);
    }

    /**
     * Write one SVG file per board.
     *
     * <p>When there is more than one board the output filenames are
     * {@code <base>-board1.svg}, {@code <base>-board2.svg}, etc.
     * When there is exactly one board the {@code baseFile} name is used as-is.
     *
     * @return list of files actually written (in board order)
     */
    private List<File> writeBoardFiles(File baseFile, List<LayoutResult> boards) throws IOException {
        String name = baseFile.getName();
        if (name.toLowerCase(Locale.ROOT).endsWith(".svg")) {
            name = name.substring(0, name.length() - 4);
        }
        File dir = baseFile.getParentFile();
        if (dir == null) dir = new File(".");

        List<File> written = new ArrayList<>(boards.size());
        for (int i = 0; i < boards.size(); i++) {
            String suffix = boards.size() > 1 ? "-board" + (i + 1) : "";
            File boardFile = new File(dir, name + suffix + ".svg");
            writeSvgBoard(boardFile, boards.get(i));
            written.add(boardFile);
        }
        return written;
    }

    private static List<double[]> translated(List<double[]> pts, double dx, double dy) {
        List<double[]> r = new ArrayList<>(pts.size());
        for (double[] p : pts) {
            r.add(new double[] { p[0] + dx, p[1] + dy });
        }
        return r;
    }

    private static List<double[]> asPolygon(double[][] pts) {
        List<double[]> r = new ArrayList<>(pts.length);
        for (double[] p : pts) r.add(p);
        return r;
    }
}
