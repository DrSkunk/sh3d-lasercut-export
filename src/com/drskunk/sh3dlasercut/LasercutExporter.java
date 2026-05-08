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
        addTJunctionNotches(walls, wallSet, pieces);
        FloorPiece floor = buildFloorPiece(walls, pieces);

        return composeLayout(pieces, floor);
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

        // Bottom tabs connect the wall panel to the floor plate.
        // In smooth mode all connections are suppressed — the pieces are glued —
        // so no tabs are needed and the floor plate stays plain.
        // startsWithTab = false → corners are flat, slots sit cleanly inside the
        // floor outline.
        List<TabPattern.Span> bottomTabs;
        if (options.smoothConnections) {
            bottomTabs = Collections.emptyList();
        } else {
            bottomTabs = TabPattern.compute(length, tabWidth, false);
        }

        // Door / window cutouts in panel-local coords (origin = panel x=0, which
        // is inset from the wall centre-line start by startOffset).
        // For sloping walls use the taller of the two end heights as the clamp so
        // that cutouts spanning the full height of either end are not incorrectly
        // truncated.
        double maxHeight = Math.max(heightAtStart, heightAtEnd);
        List<double[]> cutouts = findCutouts(wall, sf, length, maxHeight, startOffset);

        // Drop bottom tabs that sit underneath a doorway — otherwise the tab
        // would be a detached strip dangling in the opening.
        if (!bottomTabs.isEmpty()) {
            bottomTabs = filterBottomTabsByCutouts(bottomTabs, cutouts);
        }

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

    private FloorPiece buildFloorPiece(List<Wall> walls, Map<Wall, WallPiece> pieces) {
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
        double m = options.floorMargin; // unscaled physical mm
        minX -= m; minY -= m; maxX += m; maxY += m;

        List<double[]> outline = new ArrayList<>();
        outline.add(new double[] { minX, minY });
        outline.add(new double[] { maxX, minY });
        outline.add(new double[] { maxX, maxY });
        outline.add(new double[] { minX, maxY });

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

        return new FloorPiece(outline, slots);
    }

    // ---- layout + write ------------------------------------------------------

    private LayoutResult composeLayout(Map<Wall, WallPiece> pieces, FloorPiece floor) {
        double spacing = options.layoutSpacing;
        double cursorY = 0;
        double maxW = 0;

        LayoutResult result = new LayoutResult();

        // Floor: outline + slot rectangles.
        double fOffX = -floor.bounds[0];
        double fOffY = -floor.bounds[1];
        result.shapes.add(translated(floor.outline, fOffX, fOffY));
        for (double[][] slot : floor.slots) {
            result.shapes.add(translated(asPolygon(slot), fOffX, fOffY));
        }
        double fw = floor.bounds[2] - floor.bounds[0];
        double fh = floor.bounds[3] - floor.bounds[1];
        if (fw > maxW) maxW = fw;
        result.labels.add(new LayoutResult.Label("FLOOR", 4, 8, 6));
        cursorY = fh + spacing;

        // Walls: stacked vertically.
        for (WallPiece piece : pieces.values()) {
            double[] b = piece.bounds();
            double offX = -b[0];
            double offY = cursorY - b[1];
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

        result.width = maxW;
        result.height = Math.max(0, cursorY - spacing);
        return result;
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
