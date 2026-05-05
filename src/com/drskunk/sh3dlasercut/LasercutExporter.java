package com.drskunk.sh3dlasercut;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Wall;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

        return new WallPiece(length, height, thickness, bottomTabs, leftTabs, rightTabs, "W" + index);
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
            // Wall start/end in scaled world coords.
            double sx = wall.getXStart() * CM_TO_MM * sf;
            double sy = wall.getYStart() * CM_TO_MM * sf;
            double ex = wall.getXEnd() * CM_TO_MM * sf;
            double ey = wall.getYEnd() * CM_TO_MM * sf;
            double L = piece.length;
            if (L <= 0) continue;
            double dx = (ex - sx) / L;
            double dy = (ey - sy) / L;
            double nx = -dy;
            double ny = dx;

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
