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
        if (collectWalls(home, targetLevel).isEmpty()) {
            throw new IOException("No walls found on the selected level.");
        }
        if (options.separateFilesPerBoard && isBoardConstrained()) {
            List<LayoutResult> boards = buildBoardLayouts();
            return writeBoardFiles(outputFile, boards);
        } else {
            LayoutResult layout = buildLayout();
            String base = stripExtension(outputFile.getName());
            File dir = outputFile.getParentFile();
            if (dir == null) dir = new File(".");
            ExportOptions.ExportFormat fmt = options.exportFormat;
            List<File> written = new ArrayList<>();
            if (fmt != ExportOptions.ExportFormat.DXF) {
                File f = new File(dir, base + ".svg");
                writeSvg(f, layout);
                written.add(f);
            }
            if (fmt != ExportOptions.ExportFormat.SVG) {
                File f = new File(dir, base + ".dxf");
                writeDxf(f, layout);
                written.add(f);
            }
            return written;
        }
    }

    /**
     * Build the full layout (floor + walls placed in their final SVG positions)
     * without writing it to disk. Used both by {@link #export} and by the
     * options dialog's live preview.
     */
    public LayoutResult buildLayout() throws IOException {
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
        addCrossJunctionNotches(walls, pieces);

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
    public List<LayoutResult> buildBoardLayouts() throws IOException {
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
        addCrossJunctionNotches(walls, pieces);

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
        double leftAngle  = Math.PI / 2; // angle between wall directions at start junction
        double rightAngle = Math.PI / 2; // angle between wall directions at end junction
        if (!options.smoothConnections) {
            Wall startNeighbor = wall.getWallAtStart();
            boolean skipStart = startNeighbor != null
                    && (sloping || isSloping(startNeighbor, home))
                    && options.slopingWallMode == ExportOptions.SlopingWallMode.SMOOTH;
            if (!skipStart && startNeighbor != null && levelWalls.contains(startNeighbor)) {
                leftConnected = true;
                leftAngle = angleAtJunction(wall, true, startNeighbor);
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
                rightAngle = angleAtJunction(wall, false, endNeighbor);
                boolean primary = isPrimary(wall, endNeighbor);
                double jointH = computeJointHeightScaled(wall, false, endNeighbor, sf);
                rightTabs = TabPattern.compute(jointH, tabWidth, !primary);
            }
        }

        // Inset each connected end by T/(2*sin(theta)) so that the finger tabs
        // span exactly the mating panel's thickness.  For 90-degree corners
        // sin(theta)=1 so the inset is simply T/2 (the previous fixed value).
        // Capping sin at 0.2 prevents absurdly deep tabs at near-collinear walls.
        double sinLeft  = Math.max(0.2, Math.abs(Math.sin(leftAngle)));
        double sinRight = Math.max(0.2, Math.abs(Math.sin(rightAngle)));
        double leftTabDepth  = leftConnected  ? thickness / sinLeft  : thickness;
        double rightTabDepth = rightConnected ? thickness / sinRight : thickness;
        double startOffset   = leftConnected  ? thickness / (2.0 * sinLeft)  : 0;
        double endOffset     = rightConnected ? thickness / (2.0 * sinRight) : 0;
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

        String label = "W" + index;
        return new WallPiece(length, heightAtStart, heightAtEnd, thickness,
                bottomTabs, leftTabs, rightTabs, cutouts, label, leftTabDepth, rightTabDepth);
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

    /**
     * Puzzle-joint seam proportions.  The neck (slot opening at the seam face)
     * is narrower than the head (tab tip), so assembled tiles cannot be pulled
     * apart perpendicular to the seam — they must slide in from the end.
     * Values are fractions of the tab span width.
     */
    private static final double PUZZLE_NECK_FRACTION = 0.55; // slot opening = 55 % of span
    private static final double PUZZLE_HEAD_FRACTION = 0.90; // tab tip      = 90 % of span

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

    /**
     * Computes the angle (radians) between the two wall centerline directions at
     * their shared junction.  Both direction vectors point AWAY from the junction
     * along their respective walls.  Returns PI/2 for degenerate (zero-length)
     * walls.  The sine of this angle drives the tab-depth correction for
     * non-orthogonal corners.
     */
    private static double angleAtJunction(Wall self, boolean atStart, Wall neighbor) {
        double sax = (self.getXEnd() - self.getXStart()) * CM_TO_MM;
        double say = (self.getYEnd() - self.getYStart()) * CM_TO_MM;
        double sLen = Math.hypot(sax, say);
        if (sLen < 1e-10) return Math.PI / 2;
        sax /= sLen; say /= sLen;
        // If self is AT ITS START at the junction, self goes AWAY toward its end.
        // If self is AT ITS END, reverse the direction.
        if (!atStart) { sax = -sax; say = -say; }

        double nbx = (neighbor.getXEnd() - neighbor.getXStart()) * CM_TO_MM;
        double nby = (neighbor.getYEnd() - neighbor.getYStart()) * CM_TO_MM;
        double nLen = Math.hypot(nbx, nby);
        if (nLen < 1e-10) return Math.PI / 2;
        nbx /= nLen; nby /= nLen;
        // Determine which end of neighbor is at the junction and flip if needed.
        boolean neighborAtStart = (neighbor.getWallAtStart() == self);
        if (!neighborAtStart) { nbx = -nbx; nby = -nby; }

        // Cross-product magnitude = |sin| of angle between the two outgoing directions.
        double sinTheta = Math.abs(sax * nby - say * nbx);
        // asin gives the angle; for obtuse angles use pi - asin.
        double dot = sax * nbx + say * nby;
        return dot >= 0 ? Math.asin(Math.min(1.0, sinTheta))
                        : Math.PI - Math.asin(Math.min(1.0, sinTheta));
    }

    /**
     * Third pass: detect pairs of walls whose centerlines geometrically intersect
     * at a point that is NOT a shared formal endpoint.  These are cross (+)
     * junctions where one continuous wall passes through another.
     *
     * <p>At a cross junction both panels need a half-depth slot so they can slide
     * together in 3D assembly.  The primary wall (by identity hash order) gets a
     * slot from the top of the panel down to mid-height; the secondary wall gets
     * a slot from the bottom up to mid-height.
     *
     * <p>The intersection is computed from the raw (unscaled) wall centerlines;
     * it must lie strictly interior to both walls (not within one material
     * thickness of either endpoint) to be counted.
     */
    private void addCrossJunctionNotches(List<Wall> walls, Map<Wall, WallPiece> pieces) {
        if (options.smoothConnections) return;
        double sf = scaleFactor();
        double t  = options.materialThickness;
        int n = walls.size();

        for (int i = 0; i < n - 1; i++) {
            Wall wA = walls.get(i);
            for (int j = i + 1; j < n; j++) {
                Wall wB = walls.get(j);

                // Skip pairs already connected at endpoints.
                if (wA.getWallAtStart() == wB || wA.getWallAtEnd() == wB) continue;
                if (wB.getWallAtStart() == wA || wB.getWallAtEnd() == wA) continue;

                // Centerline vectors in scaled mm.
                double axs = wA.getXStart() * CM_TO_MM * sf;
                double ays = wA.getYStart() * CM_TO_MM * sf;
                double axe = wA.getXEnd()   * CM_TO_MM * sf;
                double aye = wA.getYEnd()   * CM_TO_MM * sf;
                double bxs = wB.getXStart() * CM_TO_MM * sf;
                double bys = wB.getYStart() * CM_TO_MM * sf;
                double bxe = wB.getXEnd()   * CM_TO_MM * sf;
                double bye = wB.getYEnd()   * CM_TO_MM * sf;

                double dax = axe - axs, day = aye - ays;
                double dbx = bxe - bxs, dby = bye - bys;
                double denom = dax * dby - day * dbx;
                if (Math.abs(denom) < SEAM_TOLERANCE) continue; // parallel

                double tA = ((bxs - axs) * dby - (bys - ays) * dbx) / denom;
                double tB = ((bxs - axs) * day - (bys - ays) * dax) / denom;

                double aLen = Math.hypot(dax, day);
                double bLen = Math.hypot(dbx, dby);
                if (aLen < SEAM_TOLERANCE || bLen < SEAM_TOLERANCE) continue;

                // Must be strictly interior (not within t of either endpoint).
                double epA = t / aLen;
                double epB = t / bLen;
                if (tA <= epA || tA >= 1 - epA) continue;
                if (tB <= epB || tB >= 1 - epB) continue;

                WallPiece pieceA = pieces.get(wA);
                WallPiece pieceB = pieces.get(wB);

                // Panel-local x of the crossing on each piece.
                double aStartOff = (pieceA.leftTabs != null) ? t / 2.0 : 0;
                double bStartOff = (pieceB.leftTabs != null) ? t / 2.0 : 0;
                double aX = tA * aLen - aStartOff;
                double bX = tB * bLen - bStartOff;
                double half = t / 2.0;

                double aH = pieceA.height;
                double bH = pieceB.height;

                // Primary gets top-half slot (slides DOWN onto secondary).
                if (isPrimary(wA, wB)) {
                    pieceA.cutouts.add(new double[]{ aX - half, aH / 2.0, aX + half, aH });
                    pieceB.cutouts.add(new double[]{ bX - half, 0,         bX + half, bH / 2.0 });
                } else {
                    pieceA.cutouts.add(new double[]{ aX - half, 0,         aX + half, aH / 2.0 });
                    pieceB.cutouts.add(new double[]{ bX - half, bH / 2.0, bX + half, bH });
                }
            }
        }
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
        double spacing  = options.layoutSpacing;
        double tabDepth = options.materialThickness;
        boolean constrained = bw > 0 && bh > 0;

        // When board packing is active, the usable area per board is the board
        // dimensions minus the spacing margin on each side.  The floor must fit
        // within that usable area (not the raw board dimensions).
        double usableW = constrained ? bw - 2 * spacing : bw;
        double usableH = constrained ? bh - 2 * spacing : bh;
        boolean tooWide = bw > 0 && floorW > usableW + SEAM_TOLERANCE;
        boolean tooTall = bh > 0 && floorH > usableH + SEAM_TOLERANCE;

        // With both board dimensions set, a single unsplit floor tile can still
        // fit if rotated 90°. Splitting is only necessary when neither orientation fits.
        boolean fitsRotatedSingleTile = constrained
            && floorW <= usableH + SEAM_TOLERANCE
            && floorH <= usableW + SEAM_TOLERANCE;

        if ((!tooWide && !tooTall) || fitsRotatedSingleTile) {
            gridSize[0] = 1; gridSize[1] = 1;
            return Collections.singletonList(
                    new FloorPiece(buildRectOutline(minX, minY, maxX, maxY), allSlots));
        }

        if (!options.splitFloor) {
            result.boardWarning = String.format(Locale.US,
                    "Floor (%.0f \u00d7 %.0f mm) exceeds board size (%s \u00d7 %s mm). "
                            + "Enable \"Split floor into interlocking tiles if too large\" to split automatically.",
                    floorW, floorH, fmtBoardDim(bw), fmtBoardDim(bh));
            gridSize[0] = 1; gridSize[1] = 1;
            return Collections.singletonList(
                    new FloorPiece(buildRectOutline(minX, minY, maxX, maxY), allSlots));
        }

        // Choose split orientation (normal board axes vs swapped axes).
        // Preference order:
        // 1) Prefer a single-axis split over a two-axis grid.
        // 2) If both are single-axis, prefer splitting along the longer floor side.
        // 3) Otherwise, prefer the orientation that yields fewer tiles.
        double splitUsableW = usableW;
        double splitUsableH = usableH;
        if (constrained) {
            boolean normalTooWide = floorW > usableW + SEAM_TOLERANCE;
            boolean normalTooTall = floorH > usableH + SEAM_TOLERANCE;
            boolean swappedTooWide = floorW > usableH + SEAM_TOLERANCE;
            boolean swappedTooTall = floorH > usableW + SEAM_TOLERANCE;

            int normalAxes = (normalTooWide ? 1 : 0) + (normalTooTall ? 1 : 0);
            int swappedAxes = (swappedTooWide ? 1 : 0) + (swappedTooTall ? 1 : 0);

            boolean useSwapped = false;
            if (swappedAxes < normalAxes) {
                useSwapped = true;
            } else if (swappedAxes == normalAxes && normalAxes == 1) {
                boolean preferWidthSplit = floorW >= floorH;
                boolean normalSplitsWidth = normalTooWide;
                boolean swappedSplitsWidth = swappedTooWide;
                if (normalSplitsWidth != swappedSplitsWidth) {
                    useSwapped = swappedSplitsWidth == preferWidthSplit;
                }
            } else if (swappedAxes == normalAxes && normalAxes >= 1) {
                double normalIntervalX = normalTooWide ? Math.max(1, usableW - tabDepth) : 0;
                double normalIntervalY = normalTooTall ? Math.max(1, usableH - tabDepth) : 0;
                double swappedIntervalX = swappedTooWide ? Math.max(1, usableH - tabDepth) : 0;
                double swappedIntervalY = swappedTooTall ? Math.max(1, usableW - tabDepth) : 0;

                int normalCols = normalTooWide
                        ? buildSeamCoords(0, floorW, normalIntervalX).size() - 1
                        : 1;
                int normalRows = normalTooTall
                        ? buildSeamCoords(0, floorH, normalIntervalY).size() - 1
                        : 1;
                int swappedCols = swappedTooWide
                        ? buildSeamCoords(0, floorW, swappedIntervalX).size() - 1
                        : 1;
                int swappedRows = swappedTooTall
                        ? buildSeamCoords(0, floorH, swappedIntervalY).size() - 1
                        : 1;

                int normalTiles = normalCols * normalRows;
                int swappedTiles = swappedCols * swappedRows;
                if (swappedTiles < normalTiles) {
                    useSwapped = true;
                }
            }

            if (useSwapped) {
                splitUsableW = usableH;
                splitUsableH = usableW;
            }
        }

        tooWide = bw > 0 && floorW > splitUsableW + SEAM_TOLERANCE;
        tooTall = bh > 0 && floorH > splitUsableH + SEAM_TOLERANCE;

        // Split into a grid of tiles with interlocking puzzle-joint seams.
        // When constrained, each non-last tile has a puzzle-joint tab that protrudes
        // tabDepth beyond the seam line.  To keep the tile's bounding box within the
        // usable area, the seam interval is reduced by tabDepth so that
        // (seam interval) + tabDepth = usableW/H.
        double xInterval = tooWide ? (constrained ? Math.max(1, splitUsableW - tabDepth) : bw) : 0;
        double yInterval = tooTall ? (constrained ? Math.max(1, splitUsableH - tabDepth) : bh) : 0;
        List<Double> xCoords = buildSeamCoords(minX, maxX, xInterval);
        List<Double> yCoords = buildSeamCoords(minY, maxY, yInterval);
        int numCols = xCoords.size() - 1;
        int numRows = yCoords.size() - 1;
        gridSize[0] = numCols;
        gridSize[1] = numRows;
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

    /**
     * Build the footprint rectangle of a wall panel clipped to the given tile
     * bounding box.  Pass {@code ±Double.MAX_VALUE} for the clip bounds to get
     * the unclipped full-length footprint.
     *
     * <p>The wall centre-line segment (after inset for finger-joint connections)
     * is clipped to {@code [clipX0,clipX1]×[clipY0,clipY1]} using Liang-Barsky.
     * A thick rectangle of width {@code materialThickness} is then built around
     * the clipped segment.  Returns an empty list when the segment does not
     * intersect the tile at all.
     */
    private List<double[]> buildWallFootprintOnFloor(Wall wall, WallPiece piece,
                                                      double clipX0, double clipY0,
                                                      double clipX1, double clipY1) {
        double sf   = scaleFactor();
        double t    = options.materialThickness;
        double sx   = wall.getXStart() * CM_TO_MM * sf;
        double sy   = wall.getYStart() * CM_TO_MM * sf;
        double ex   = wall.getXEnd()   * CM_TO_MM * sf;
        double ey   = wall.getYEnd()   * CM_TO_MM * sf;
        double rawLen = Math.hypot(ex - sx, ey - sy);
        if (rawLen < COORD_EPSILON) return Collections.emptyList();
        double dx = (ex - sx) / rawLen;
        double dy = (ey - sy) / rawLen;
        double nx = -dy;
        double ny =  dx;
        // Use full wall centerline extent (no panel inset) so adjacent wall
        // footprints overlap slightly at junctions instead of leaving a gap.
        double half = t / 2.0;
        double psx = sx;
        double psy = sy;
        double pex = ex;
        double pey = ey;

        double[] seg = clipLineToRect(psx, psy, pex, pey, clipX0, clipY0, clipX1, clipY1);
        if (seg == null) return Collections.emptyList();
        if (Math.hypot(seg[2] - seg[0], seg[3] - seg[1]) < COORD_EPSILON) return Collections.emptyList();
        psx = seg[0]; psy = seg[1];
        pex = seg[2]; pey = seg[3];

        List<double[]> pts = new ArrayList<>(4);
        pts.add(new double[]{ psx - nx * half, psy - ny * half });
        pts.add(new double[]{ pex - nx * half, pey - ny * half });
        pts.add(new double[]{ pex + nx * half, pey + ny * half });
        pts.add(new double[]{ psx + nx * half, psy + ny * half });
        return pts;
    }

    /**
     * Liang-Barsky line-clipping algorithm.
     * Clips the segment from (x0,y0) to (x1,y1) to the axis-aligned rectangle
     * [rx0,rx1]×[ry0,ry1].  Returns {cx0,cy0,cx1,cy1} for the clipped segment,
     * or null when the segment is entirely outside the rectangle.
     */
    private static double[] clipLineToRect(double x0, double y0, double x1, double y1,
                                            double rx0, double ry0, double rx1, double ry1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double tMin = 0, tMax = 1;
        double[] p = { -dx,  dx, -dy,  dy };
        double[] q = { x0 - rx0, rx1 - x0, y0 - ry0, ry1 - y0 };
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < COORD_EPSILON) {
                if (q[i] < 0) return null; // parallel and outside
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) { if (t > tMin) tMin = t; }
                else          { if (t < tMax) tMax = t; }
            }
        }
        if (tMin > tMax) return null;
        return new double[] { x0 + tMin * dx, y0 + tMin * dy,
                              x0 + tMax * dx, y0 + tMax * dy };
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
     * <p>Each tab uses a dovetail (locking) profile: the neck (at {@code xCut})
     * is {@link #PUZZLE_NECK_FRACTION} of the span wide and the head (at
     * {@code xCut + tabDepth}) is {@link #PUZZLE_HEAD_FRACTION} wide.  The wider
     * head prevents the assembled tiles from being pulled apart perpendicular to
     * the seam; they must slide together from the end.
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
                double sa       = yA + span.start;
                double sb       = yA + span.end;
                double yC       = (sa + sb) / 2.0;
                double neckHalf = (sb - sa) / 2.0 * PUZZLE_NECK_FRACTION;
                double headHalf = (sb - sa) / 2.0 * PUZZLE_HEAD_FRACTION;
                if (sa > cur)              pts.add(new double[] { xCut, sa });
                if (yC - neckHalf > sa)    pts.add(new double[] { xCut, yC - neckHalf });
                pts.add(new double[] { xCut + tabDepth, yC - headHalf });
                pts.add(new double[] { xCut + tabDepth, yC + headHalf });
                pts.add(new double[] { xCut, yC + neckHalf });
                if (sb > yC + neckHalf)    pts.add(new double[] { xCut, sb });
                cur = sb;
            }
            if (cur < yB) pts.add(new double[] { xCut, yB });
        } else {
            pts.add(new double[] { xCut, yB });
            double cur = yB;
            for (int i = tabs.size() - 1; i >= 0; i--) {
                TabPattern.Span span = tabs.get(i);
                double sa       = yA + span.start;
                double sb       = yA + span.end;
                double yC       = (sa + sb) / 2.0;
                double neckHalf = (sb - sa) / 2.0 * PUZZLE_NECK_FRACTION;
                double headHalf = (sb - sa) / 2.0 * PUZZLE_HEAD_FRACTION;
                if (sb < cur)              pts.add(new double[] { xCut, sb });
                if (yC + neckHalf < sb)    pts.add(new double[] { xCut, yC + neckHalf });
                pts.add(new double[] { xCut + tabDepth, yC + headHalf });
                pts.add(new double[] { xCut + tabDepth, yC - headHalf });
                pts.add(new double[] { xCut, yC - neckHalf });
                if (sa < yC - neckHalf)    pts.add(new double[] { xCut, sa });
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
     * <p>Same dovetail (locking) profile as {@link #buildVSeamPoints}: neck
     * width {@link #PUZZLE_NECK_FRACTION}, head width {@link #PUZZLE_HEAD_FRACTION}.
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
                double sa       = xA + span.start;
                double sb       = xA + span.end;
                double xC       = (sa + sb) / 2.0;
                double neckHalf = (sb - sa) / 2.0 * PUZZLE_NECK_FRACTION;
                double headHalf = (sb - sa) / 2.0 * PUZZLE_HEAD_FRACTION;
                if (sa > cur)              pts.add(new double[] { sa, yCut });
                if (xC - neckHalf > sa)    pts.add(new double[] { xC - neckHalf, yCut });
                pts.add(new double[] { xC - headHalf, yCut + tabDepth });
                pts.add(new double[] { xC + headHalf, yCut + tabDepth });
                pts.add(new double[] { xC + neckHalf, yCut });
                if (sb > xC + neckHalf)    pts.add(new double[] { sb, yCut });
                cur = sb;
            }
            if (cur < xB) pts.add(new double[] { xB, yCut });
        } else {
            pts.add(new double[] { xB, yCut });
            double cur = xB;
            for (int i = tabs.size() - 1; i >= 0; i--) {
                TabPattern.Span span = tabs.get(i);
                double sa       = xA + span.start;
                double sb       = xA + span.end;
                double xC       = (sa + sb) / 2.0;
                double neckHalf = (sb - sa) / 2.0 * PUZZLE_NECK_FRACTION;
                double headHalf = (sb - sa) / 2.0 * PUZZLE_HEAD_FRACTION;
                if (sb < cur)              pts.add(new double[] { sb, yCut });
                if (xC + neckHalf < sb)    pts.add(new double[] { xC + neckHalf, yCut });
                pts.add(new double[] { xC + headHalf, yCut + tabDepth });
                pts.add(new double[] { xC - headHalf, yCut + tabDepth });
                pts.add(new double[] { xC - neckHalf, yCut });
                if (sa < xC - neckHalf)    pts.add(new double[] { sa, yCut });
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
        final List<List<double[]>> innerShapes;
        final List<List<double[]>> referenceShapes;
        final List<LayoutResult.Label> labels;
        final double width;
        final double height;

        BoardItem(List<List<double[]>> shapes, List<List<double[]>> innerShapes,
                  List<List<double[]>> referenceShapes,
                  List<LayoutResult.Label> labels, double width, double height) {
            this.shapes          = shapes;
            this.innerShapes     = innerShapes;
            this.referenceShapes = referenceShapes;
            this.labels          = labels;
            this.width           = width;
            this.height          = height;
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
            List<List<double[]>> tileInner = new ArrayList<>();
            for (double[][] slot : tile.slots) {
                tileInner.add(translated(asPolygon(slot), offX, offY));
            }

            List<List<double[]>> refShapes = new ArrayList<>();
            List<LayoutResult.Label> labels = new ArrayList<>();
            String floorLabel = numTiles > 1
                    ? "FLOOR " + (i + 1) + "/" + numTiles
                    : "FLOOR";
            labels.add(new LayoutResult.Label(floorLabel, 4, 8, 6));

            // Wall midpoint labels (midpoint-owning tile only) and footprints
            // (every tile the wall segment intersects, clipped to tile bounds).
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
                List<double[]> footprint = buildWallFootprintOnFloor(wall, piece,
                        tile.bounds[0], tile.bounds[1], tile.bounds[2], tile.bounds[3]);
                if (!footprint.isEmpty()) {
                    refShapes.add(translated(footprint, offX, offY));
                }
            }

            items.add(new BoardItem(shapes, tileInner, refShapes, labels, w, h));
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
            List<List<double[]>> wallInner = new ArrayList<>();
            for (double[] cut : piece.cutouts) {
                List<double[]> rect = new ArrayList<>(4);
                rect.add(new double[] { cut[0] + offX, cut[1] + offY });
                rect.add(new double[] { cut[2] + offX, cut[1] + offY });
                rect.add(new double[] { cut[2] + offX, cut[3] + offY });
                rect.add(new double[] { cut[0] + offX, cut[3] + offY });
                wallInner.add(rect);
            }

            List<LayoutResult.Label> labels = new ArrayList<>();
            labels.add(new LayoutResult.Label(piece.label, 2, 8, 6));

            items.add(new BoardItem(shapes, wallInner, Collections.emptyList(), labels, w, h));
        }

        return items;
    }

    /**
     * Pack items onto boards using the MaxRects algorithm with the Best Short
     * Side Fit (BSSF) heuristic.
     *
     * <p>Each board starts with one free rectangle covering its entire usable
     * area.  For each item the algorithm picks the free rectangle that leaves
     * the smallest shorter leftover dimension after placement, splits that
     * rectangle into up to four new free rectangles covering the remaining
     * space, then prunes any free rectangle that is fully contained within
     * another.  When no free rectangle on the current board can fit the item a
     * new board is opened.
     *
     * <p>Inter-item spacing is folded into each item’s effective packed size
     * ({@code width + spacing} × {@code height + spacing}) so adjacent pieces
     * are always at least {@code spacing} mm apart without explicit gap tracking.
     *
     * @return one {@link LayoutResult} per board; pieces in board-local coords
     *         (origin = (0,0) in the usable area, before the outer spacing margin).
     */
    private List<LayoutResult> packOntoBoards(List<BoardItem> items) throws IOException {
        double spacing    = options.layoutSpacing;
        double bw         = options.boardWidth;
        double bh         = options.boardHeight;
        boolean constrained = bw > 0 && bh > 0;
        double usableW = constrained ? bw - 2 * spacing : 1e9;
        double usableH = constrained ? bh - 2 * spacing : 1e9;

        if (constrained) {
            if (usableW <= 0 || usableH <= 0) {
                throw new IOException(String.format(Locale.US,
                        "Layout spacing (%.0f mm) leaves no usable area on the board "
                        + "(%.0f × %.0f mm). Reduce spacing or increase board size.",
                        spacing, bw, bh));
            }
            for (BoardItem item : items) {
                if (item.width > usableW + SEAM_TOLERANCE || item.height > usableH + SEAM_TOLERANCE) {
                    String lbl = item.labels.isEmpty() ? "?" : item.labels.get(0).text;
                    throw new IOException(String.format(Locale.US,
                            "Piece ‘%s’ (%.0f × %.0f mm) is larger than the board "
                            + "usable area (%.0f × %.0f mm). Increase board size or reduce scale.",
                            lbl, item.width, item.height, usableW, usableH));
                }
            }
        }

        // Sort largest-area first — good pre-processing for greedy bin-packing.
        List<BoardItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Double.compare(b.width * b.height, a.width * a.height));

        List<LayoutResult> boards  = new ArrayList<>();
        List<double[]>     free    = new ArrayList<>();  // {x, y, w, h}
        // Effective free area: +spacing so the last item’s inter-item gap fits.
        free.add(new double[]{ 0, 0, usableW + spacing, usableH + spacing });
        LayoutResult current = new LayoutResult();
        boards.add(current);
        double maxX = 0, maxY = 0;

        for (BoardItem item : sorted) {
            double iw  = item.width  + spacing;  // effective packed width  (normal)
            double ih  = item.height + spacing;  // effective packed height (normal)
            double iwr = item.height + spacing;  // effective packed width  (rotated 90°)
            double ihr = item.width  + spacing;  // effective packed height (rotated 90°)
            boolean canRotate = Math.abs(iw - iwr) > SEAM_TOLERANCE; // skip if square

            // Best Short Side Fit across both orientations.
            int    bestIdx     = -1;
            double bestScore   = Double.MAX_VALUE;
            double bestX = 0, bestY = 0;
            boolean bestRotated = false;
            for (int i = 0; i < free.size(); i++) {
                double[] r = free.get(i);
                if (iw <= r[2] + SEAM_TOLERANCE && ih <= r[3] + SEAM_TOLERANCE) {
                    double score = Math.min(r[2] - iw, r[3] - ih);
                    if (score < bestScore) {
                        bestScore = score; bestIdx = i;
                        bestX = r[0]; bestY = r[1]; bestRotated = false;
                    }
                }
                if (canRotate && iwr <= r[2] + SEAM_TOLERANCE && ihr <= r[3] + SEAM_TOLERANCE) {
                    double score = Math.min(r[2] - iwr, r[3] - ihr);
                    if (score < bestScore) {
                        bestScore = score; bestIdx = i;
                        bestX = r[0]; bestY = r[1]; bestRotated = true;
                    }
                }
            }

            if (bestIdx < 0) {
                // Item doesn’t fit on this board — open a new one.
                current.width  = maxX;
                current.height = maxY;
                current = new LayoutResult();
                boards.add(current);
                free.clear();
                free.add(new double[]{ 0, 0, usableW + spacing, usableH + spacing });
                maxX = 0; maxY = 0;
                bestX = 0; bestY = 0;
                bestRotated = false;
            }

            double placedW = bestRotated ? item.height : item.width;
            double placedH = bestRotated ? item.width  : item.height;
            if (bestRotated) {
                // 90° CW rotation: (x, y) → (y, item.width − x), then translate.
                double origW = item.width;
                for (List<double[]> shape : item.shapes)
                    current.shapes.add(translated(rotated90(shape, origW), bestX, bestY));
                for (List<double[]> shape : item.innerShapes)
                    current.innerShapes.add(translated(rotated90(shape, origW), bestX, bestY));
                for (List<double[]> shape : item.referenceShapes)
                    current.referenceShapes.add(translated(rotated90(shape, origW), bestX, bestY));
                for (LayoutResult.Label lbl : item.labels)
                    current.labels.add(new LayoutResult.Label(
                            lbl.text, lbl.y + bestX, origW - lbl.x + bestY, lbl.size));
            } else {
                for (List<double[]> shape : item.shapes)
                    current.shapes.add(translated(shape, bestX, bestY));
                for (List<double[]> shape : item.innerShapes)
                    current.innerShapes.add(translated(shape, bestX, bestY));
                for (List<double[]> shape : item.referenceShapes)
                    current.referenceShapes.add(translated(shape, bestX, bestY));
                for (LayoutResult.Label lbl : item.labels)
                    current.labels.add(new LayoutResult.Label(
                            lbl.text, lbl.x + bestX, lbl.y + bestY, lbl.size));
            }
            if (bestX + placedW > maxX) maxX = bestX + placedW;
            if (bestY + placedH > maxY) maxY = bestY + placedH;

            maxRectsPlace(free, bestX, bestY,
                    bestRotated ? iwr : iw,
                    bestRotated ? ihr : ih);
        }

        current.width  = maxX;
        current.height = maxY;
        return boards;
    }

    /**
     * MaxRects split-and-prune step.
     *
     * <p>For every free rectangle that overlaps the just-placed item (at
     * {@code px,py} with effective size {@code pw × ph}), remove the free
     * rectangle and replace it with up to four axis-aligned sub-rectangles
     * covering its non-overlapping parts.  Then prune any free rectangle that
     * is fully contained within another (those can never be the best choice for
     * any future item).
     */
    private static void maxRectsPlace(List<double[]> free,
                                       double px, double py, double pw, double ph) {
        List<double[]> toAdd    = new ArrayList<>();
        List<double[]> toRemove = new ArrayList<>();
        for (double[] r : free) {
            double rx = r[0], ry = r[1], rw = r[2], rh = r[3];
            if (px >= rx + rw || px + pw <= rx || py >= ry + rh || py + ph <= ry) continue;
            toRemove.add(r);
            double left  = px      - rx;
            double right = rx + rw - (px + pw);
            double below = py      - ry;
            double above = ry + rh - (py + ph);
            if (left  > SEAM_TOLERANCE) toAdd.add(new double[]{ rx,       ry, left,  rh    });
            if (right > SEAM_TOLERANCE) toAdd.add(new double[]{ px + pw,  ry, right, rh    });
            if (below > SEAM_TOLERANCE) toAdd.add(new double[]{ rx,       ry, rw,    below });
            if (above > SEAM_TOLERANCE) toAdd.add(new double[]{ rx, py + ph,  rw,    above });
        }
        free.removeAll(toRemove);
        free.addAll(toAdd);
        // Prune rectangles fully contained within another.
        int n = free.size();
        boolean[] drop = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (drop[i]) continue;
            double[] a = free.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j || drop[j]) continue;
                double[] b = free.get(j);
                if (b[0] <= a[0] && b[1] <= a[1]
                        && b[0] + b[2] >= a[0] + a[2]
                        && b[1] + b[3] >= a[1] + a[3]) {
                    drop[i] = true;
                    break;
                }
            }
        }
        List<double[]> pruned = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { if (!drop[i]) pruned.add(free.get(i)); }
        free.clear();
        free.addAll(pruned);
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
            for (List<double[]> shape : board.innerShapes) {
                result.innerShapes.add(translated(shape, itemOffX, itemOffY));
            }
            for (List<double[]> shape : board.referenceShapes) {
                result.referenceShapes.add(translated(shape, itemOffX, itemOffY));
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
                    result.innerShapes.add(translated(asPolygon(slot), tOffX, tOffY));
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

        // ---- wall-identifier labels and footprints on the floor tiles -------
        // Labels go on whichever tile the wall midpoint falls on (one per wall).
        // Footprints appear on every tile the wall segment intersects, clipped
        // to that tile's bounds so nothing extends outside the tile outline.
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
                double tOffX = tilePos[i][0] - tilePos[i][2];
                double tOffY = tilePos[i][1] - tilePos[i][3];

                if (midWX >= tile.bounds[0] && midWX <= tile.bounds[2]
                        && midWY >= tile.bounds[1] && midWY <= tile.bounds[3]) {
                    result.labels.add(new LayoutResult.Label(
                            piece.label, midWX + tOffX, midWY + tOffY, 4));
                }

                List<double[]> footprint = buildWallFootprintOnFloor(wall, piece,
                        tile.bounds[0], tile.bounds[1], tile.bounds[2], tile.bounds[3]);
                if (!footprint.isEmpty()) {
                    result.referenceShapes.add(translated(footprint, tOffX, tOffY));
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
                result.innerShapes.add(rect);
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
        double kerf = options.kerfMm;
        // Inner cuts first (slots, door/window openings) — shrink by kerf/2
        for (List<double[]> shape : layout.innerShapes) {
            svg.addPolygon(kerf > 0 ? offsetPolygon(shape, -kerf / 2.0) : shape, 0, 0);
        }
        // Outer profiles last — expand by kerf/2, apply bridge tabs
        for (List<double[]> shape : layout.shapes) {
            svg.addOuterPolygon(kerf > 0 ? offsetPolygon(shape, kerf / 2.0) : shape,
                    0, 0, options.bridgeWidth, options.bridgesPerEdge);
        }
        for (List<double[]> shape : layout.referenceShapes) {
            svg.addReferencePath(shape, 0, 0);
        }
        for (LayoutResult.Label label : layout.labels) {
            svg.addLabel(label.text, label.x, label.y, label.size);
        }
        svg.write(outputFile);
    }

    /** Write a single per-board SVG. Board outline at (0,0), items inset by spacing. */
    private void writeSvgBoard(File outputFile, LayoutResult boardLayout) throws IOException {
        double bw      = options.boardWidth;
        double bh      = options.boardHeight;
        double spacing = options.layoutSpacing;
        double kerf    = options.kerfMm;

        SVGWriter svg = new SVGWriter(options.svgStrokeWidth, options.cutStrokeColor);
        svg.addBoardOutline(0, 0, bw, bh);
        for (List<double[]> shape : boardLayout.innerShapes) {
            List<double[]> s = kerf > 0 ? offsetPolygon(shape, -kerf / 2.0) : shape;
            svg.addPolygon(translated(s, spacing, spacing), 0, 0);
        }
        for (List<double[]> shape : boardLayout.shapes) {
            List<double[]> s = kerf > 0 ? offsetPolygon(shape, kerf / 2.0) : shape;
            svg.addOuterPolygon(translated(s, spacing, spacing), 0, 0,
                    options.bridgeWidth, options.bridgesPerEdge);
        }
        for (List<double[]> shape : boardLayout.referenceShapes) {
            svg.addReferencePath(translated(shape, spacing, spacing), 0, 0);
        }
        for (LayoutResult.Label label : boardLayout.labels) {
            svg.addLabel(label.text, label.x + spacing, label.y + spacing, label.size);
        }
        svg.write(outputFile);
    }

    private void writeDxf(File outputFile, LayoutResult layout) throws IOException {
        DXFWriter dxf = new DXFWriter();
        for (double[] r : layout.boardRects) {
            dxf.addBoardOutline(r[0], r[1], r[2], r[3]);
        }
        double kerf = options.kerfMm;
        for (List<double[]> shape : layout.innerShapes) {
            dxf.addPolygon(kerf > 0 ? offsetPolygon(shape, -kerf / 2.0) : shape, 0, 0);
        }
        for (List<double[]> shape : layout.shapes) {
            dxf.addOuterPolygon(kerf > 0 ? offsetPolygon(shape, kerf / 2.0) : shape,
                    0, 0, options.bridgeWidth, options.bridgesPerEdge);
        }
        for (List<double[]> shape : layout.referenceShapes) {
            dxf.addReferencePath(shape, 0, 0);
        }
        dxf.write(outputFile);
    }

    private void writeDxfBoard(File outputFile, LayoutResult boardLayout) throws IOException {
        double bw      = options.boardWidth;
        double bh      = options.boardHeight;
        double spacing = options.layoutSpacing;
        double kerf    = options.kerfMm;

        DXFWriter dxf = new DXFWriter();
        dxf.addBoardOutline(0, 0, bw, bh);
        for (List<double[]> shape : boardLayout.innerShapes) {
            List<double[]> s = kerf > 0 ? offsetPolygon(shape, -kerf / 2.0) : shape;
            dxf.addPolygon(translated(s, spacing, spacing), 0, 0);
        }
        for (List<double[]> shape : boardLayout.shapes) {
            List<double[]> s = kerf > 0 ? offsetPolygon(shape, kerf / 2.0) : shape;
            dxf.addOuterPolygon(translated(s, spacing, spacing), 0, 0,
                    options.bridgeWidth, options.bridgesPerEdge);
        }
        for (List<double[]> shape : boardLayout.referenceShapes) {
            dxf.addReferencePath(translated(shape, spacing, spacing), 0, 0);
        }
        dxf.write(outputFile);
    }

    /**
     * Write one file (SVG, DXF, or both) per board.
     *
     * <p>When there is more than one board the output filenames are
     * {@code <base>-board1.svg}, {@code <base>-board2.svg}, etc.
     *
     * @return list of files actually written (in board order)
     */
    private List<File> writeBoardFiles(File baseFile, List<LayoutResult> boards) throws IOException {
        String name = stripExtension(baseFile.getName());
        File dir = baseFile.getParentFile();
        if (dir == null) dir = new File(".");

        ExportOptions.ExportFormat fmt = options.exportFormat;
        List<File> written = new ArrayList<>();
        for (int i = 0; i < boards.size(); i++) {
            String suffix = boards.size() > 1 ? "-board" + (i + 1) : "";
            if (fmt != ExportOptions.ExportFormat.DXF) {
                File f = new File(dir, name + suffix + ".svg");
                writeSvgBoard(f, boards.get(i));
                written.add(f);
            }
            if (fmt != ExportOptions.ExportFormat.SVG) {
                File f = new File(dir, name + suffix + ".dxf");
                writeDxfBoard(f, boards.get(i));
                written.add(f);
            }
        }
        return written;
    }

    private static String stripExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".svg") || lower.endsWith(".dxf")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    /**
     * Offset a polygon outward (d > 0) or inward (d < 0) by {@code d} mm.
     *
     * <p>Each edge is shifted by {@code d} along its outward normal; adjacent
     * offset edges are then intersected to produce the new vertex positions.
     * This correctly handles both convex (tab tips expand outward) and concave
     * (slot roots widen) corners.
     *
     * <p>Our polygons are traced clockwise in screen/SVG space (y-down), so the
     * outward normal of each edge is its 90° CCW (left) rotation.
     */
    private static List<double[]> offsetPolygon(List<double[]> pts, double d) {
        int n = pts.size();
        if (n < 3 || Math.abs(d) < 1e-9) return pts;

        // Compute offset edges: each edge moved d in the outward-normal direction.
        // Left normal of (ex, ey) = (-ey, ex) — outward for our CW polygons.
        double[][] os = new double[n][2]; // offset-edge start
        double[][] oe = new double[n][2]; // offset-edge end
        for (int i = 0; i < n; i++) {
            double[] p0 = pts.get(i);
            double[] p1 = pts.get((i + 1) % n);
            double ex = p1[0] - p0[0], ey = p1[1] - p0[1];
            double len = Math.hypot(ex, ey);
            if (len < 1e-9) {
                os[i][0] = p0[0]; os[i][1] = p0[1];
                oe[i][0] = p1[0]; oe[i][1] = p1[1];
            } else {
                double nx = -ey / len * d, ny = ex / len * d;
                os[i][0] = p0[0] + nx; os[i][1] = p0[1] + ny;
                oe[i][0] = p1[0] + nx; oe[i][1] = p1[1] + ny;
            }
        }

        // New vertex i = intersection of offset edge (i-1) with offset edge i.
        List<double[]> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int prev = (i - 1 + n) % n;
            double[] p = lineIntersect(os[prev], oe[prev], os[i], oe[i]);
            result.add(p != null ? p : os[i].clone());
        }
        return result;
    }

    /** Intersect the infinite lines through (a→b) and (c→d). Returns null if parallel. */
    private static double[] lineIntersect(double[] a, double[] b, double[] c, double[] d) {
        double d1x = b[0] - a[0], d1y = b[1] - a[1];
        double d2x = d[0] - c[0], d2y = d[1] - c[1];
        double denom = d1x * d2y - d1y * d2x;
        if (Math.abs(denom) < 1e-9) return null;
        double dx = c[0] - a[0], dy = c[1] - a[1];
        double t = (dx * d2y - dy * d2x) / denom;
        return new double[]{ a[0] + t * d1x, a[1] + t * d1y };
    }

    private static List<double[]> translated(List<double[]> pts, double dx, double dy) {
        List<double[]> r = new ArrayList<>(pts.size());
        for (double[] p : pts) {
            r.add(new double[] { p[0] + dx, p[1] + dy });
        }
        return r;
    }

    /** 90° CW rotation: (x,y) → (y, origWidth-x), maps [0,W]×[0,H] → [0,H]×[0,W]. */
    private static List<double[]> rotated90(List<double[]> pts, double origWidth) {
        List<double[]> r = new ArrayList<>(pts.size());
        for (double[] p : pts) r.add(new double[] { p[1], origWidth - p[0] });
        return r;
    }

    private static List<double[]> asPolygon(double[][] pts) {
        List<double[]> r = new ArrayList<>(pts.length);
        for (double[] p : pts) r.add(p);
        return r;
    }
}
