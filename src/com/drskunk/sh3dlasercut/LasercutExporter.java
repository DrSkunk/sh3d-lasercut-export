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

    private final Home home;
    private final ExportOptions options;
    private final Level targetLevel; // null = export every wall in the home

    public LasercutExporter(Home home, ExportOptions options) {
        this.home = home;
        this.options = options;
        this.targetLevel = home.getSelectedLevel();
    }

    public void export(File outputFile) throws IOException {
        LayoutResult layout = buildLayout();
        if (layout.shapes.isEmpty()) {
            throw new IOException("No walls found on the selected level.");
        }
        writeSvg(outputFile, layout);
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

        LayoutResult result = new LayoutResult();
        int[] gridSize = { 1, 1 }; // [numCols, numRows] of floor tile grid
        List<FloorPiece> floorTiles = buildFloorTiles(walls, pieces, result, gridSize);
        composeLayout(pieces, floorTiles, gridSize[0], result);
        return result;
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

    private WallPiece buildWallPiece(Wall wall, Set<Wall> levelWalls, int index) {
        double sf = scaleFactor();
        double length = rawWallLengthMm(wall) * sf;
        double height = rawWallHeightMm(wall, home) * sf;
        double thickness = options.materialThickness;
        double tabWidth = options.tabWidth;

        // Bottom tabs always present.
        // startsWithTab = false → corners are flat, slots sit cleanly inside the floor outline.
        List<TabPattern.Span> bottomTabs = TabPattern.compute(length, tabWidth, false);

        List<TabPattern.Span> leftTabs = null;
        List<TabPattern.Span> rightTabs = null;
        if (!options.smoothConnections) {
            Wall startNeighbor = wall.getWallAtStart();
            if (startNeighbor != null && levelWalls.contains(startNeighbor)) {
                boolean primary = isPrimary(wall, startNeighbor);
                leftTabs = TabPattern.compute(height, tabWidth, !primary);
            }
            Wall endNeighbor = wall.getWallAtEnd();
            if (endNeighbor != null && levelWalls.contains(endNeighbor)) {
                boolean primary = isPrimary(wall, endNeighbor);
                rightTabs = TabPattern.compute(height, tabWidth, !primary);
            }
        }

        // Door / window cutouts (in scaled wall-local coords).
        List<double[]> cutouts = findCutouts(wall, sf, length, height);

        // Drop bottom tabs that sit underneath a doorway — otherwise the tab
        // would be a detached strip dangling in the opening.
        bottomTabs = filterBottomTabsByCutouts(bottomTabs, cutouts);

        return new WallPiece(length, height, thickness, bottomTabs, leftTabs, rightTabs,
                cutouts, "W" + index);
    }

    private List<double[]> findCutouts(Wall wall, double sf, double scaledLength, double scaledHeight) {
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

            double xMin = minAlongCm * CM_TO_MM * sf;
            double xMax = maxAlongCm * CM_TO_MM * sf;
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

    // ---- floor geometry ------------------------------------------------------

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
        boolean tooWide = bw > 0 && floorW > bw + 1e-3;
        boolean tooTall = bh > 0 && floorH > bh + 1e-3;

        if (!tooWide && !tooTall) {
            gridSize[0] = 1; gridSize[1] = 1;
            return Collections.singletonList(
                    new FloorPiece(buildRectOutline(minX, minY, maxX, maxY), allSlots));
        }

        if (!options.splitFloor) {
            String bwStr = bw > 0 ? String.format(Locale.US, "%.0f", bw) : "none";
            String bhStr = bh > 0 ? String.format(Locale.US, "%.0f", bh) : "none";
            result.boardWarning = String.format(Locale.US,
                    "Floor (%.0f \u00d7 %.0f mm) exceeds board size (%s \u00d7 %s mm). "
                            + "Enable \"Split floor with puzzle joints\" to split automatically.",
                    floorW, floorH, bwStr, bhStr);
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
            double sx = wall.getXStart() * CM_TO_MM * sf;
            double sy = wall.getYStart() * CM_TO_MM * sf;
            double ex = wall.getXEnd()   * CM_TO_MM * sf;
            double ey = wall.getYEnd()   * CM_TO_MM * sf;
            double L = piece.length;
            if (L <= 0) continue;
            double dx = (ex - sx) / L;
            double dy = (ey - sy) / L;
            double nx = -dy;
            double ny =  dx;
            for (TabPattern.Span span : piece.bottomTabs) {
                double a = span.start;
                double b = span.end;
                double half = t / 2.0;
                double[][] rect = new double[][] {
                        { sx + dx * a + nx * (-half), sy + dy * a + ny * (-half) },
                        { sx + dx * b + nx * (-half), sy + dy * b + ny * (-half) },
                        { sx + dx * b + nx * (+half), sy + dy * b + ny * (+half) },
                        { sx + dx * a + nx * (+half), sy + dy * a + ny * (+half) },
                };
                slots.add(rect);
            }
        }
        return slots;
    }

    /**
     * Compute the boundary coordinates (start, seam1, seam2, …, end) for one
     * axis given a board dimension.  If {@code boardSize} is ≤ 0 the result is
     * just {@code [start, end]}.
     */
    private static List<Double> buildSeamCoords(double start, double end, double boardSize) {
        List<Double> coords = new ArrayList<>();
        coords.add(start);
        if (boardSize > 0 && end - start > boardSize + 1e-3) {
            double pos = start + boardSize;
            while (pos < end - 1e-3) {
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
            if (Math.abs(first[0] - last[0]) < 1e-9 && Math.abs(first[1] - last[1]) < 1e-9) {
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
            double cx = 0, cy = 0;
            for (double[] p : slot) { cx += p[0]; cy += p[1]; }
            cx /= slot.length;
            cy /= slot.length;
            if (cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1) {
                out.add(slot);
            }
        }
        return out;
    }

    // ---- layout + write ------------------------------------------------------

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
        for (List<double[]> shape : layout.shapes) {
            svg.addPolygon(shape, 0, 0);
        }
        for (LayoutResult.Label label : layout.labels) {
            svg.addLabel(label.text, label.x, label.y, label.size);
        }
        svg.write(outputFile);
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
