package com.drskunk.sh3dlasercut;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal DXF R2000 writer for laser-cut layouts.
 *
 * Cut paths are emitted as LWPOLYLINE entities on layer "CUT" (ACI color 1 =
 * red).  Reference/guide paths go on layer "REF" (ACI color 8 = gray).  Board
 * outlines go on layer "BOARD" (ACI color 8).
 *
 * Coordinates are millimeters.  The file sets $INSUNITS = 4 (mm) in the header
 * so that importing applications use the correct units automatically.
 */
public final class DXFWriter {

    private static final String CUT_LAYER   = "CUT";
    private static final String REF_LAYER   = "REF";
    private static final String BOARD_LAYER = "BOARD";

    private final List<String> entities = new ArrayList<>();

    // -------------------------------------------------------------------------

    /** Emit a closed cut polygon on the CUT layer. */
    public void addPolygon(List<double[]> points, double offsetX, double offsetY) {
        if (points.isEmpty()) return;
        entities.add(lwpolyline(points, offsetX, offsetY, CUT_LAYER, true));
    }

    /**
     * Emit an outer profile with optional bridge gaps.
     * Each bridge is a small uncut section on longer edges.
     */
    public void addOuterPolygon(List<double[]> points, double offsetX, double offsetY,
                                 double bridgeWidth, int bridgesPerEdge) {
        if (points.isEmpty()) return;
        if (bridgeWidth <= 0 || bridgesPerEdge <= 0) {
            addPolygon(points, offsetX, offsetY);
            return;
        }
        List<List<double[]>> segs = splitBridges(points, offsetX, offsetY, bridgeWidth, bridgesPerEdge);
        for (List<double[]> seg : segs) {
            if (seg.size() < 2) continue;
            entities.add(lwpolyline(seg, 0, 0, CUT_LAYER, false));
        }
    }

    /** Emit a reference/guide path (wall footprint, etc.) on the REF layer. */
    public void addReferencePath(List<double[]> points, double offsetX, double offsetY) {
        if (points.isEmpty()) return;
        entities.add(lwpolyline(points, offsetX, offsetY, REF_LAYER, true));
    }

    /** Emit a board outline rectangle on the BOARD layer. */
    public void addBoardOutline(double x, double y, double w, double h) {
        List<double[]> rect = new ArrayList<>(4);
        rect.add(new double[]{x,     y    });
        rect.add(new double[]{x + w, y    });
        rect.add(new double[]{x + w, y + h});
        rect.add(new double[]{x,     y + h});
        entities.add(lwpolyline(rect, 0, 0, BOARD_LAYER, true));
    }

    /** Write all entities to {@code file}. */
    public void write(File file) throws IOException {
        if (entities.isEmpty()) throw new IOException("Nothing to write");
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.US_ASCII))) {
            // HEADER
            out.write("0\nSECTION\n2\nHEADER\n");
            out.write("9\n$ACADVER\n1\nAC1015\n");   // R2000
            out.write("9\n$INSUNITS\n70\n4\n");       // 4 = mm
            out.write("0\nENDSEC\n");
            // TABLES (layer definitions)
            out.write("0\nSECTION\n2\nTABLES\n");
            out.write("0\nTABLE\n2\nLAYER\n70\n3\n");
            layer(out, CUT_LAYER,   1);   // red
            layer(out, REF_LAYER,   8);   // gray
            layer(out, BOARD_LAYER, 8);
            out.write("0\nENDTAB\n0\nENDSEC\n");
            // ENTITIES
            out.write("0\nSECTION\n2\nENTITIES\n");
            for (String e : entities) out.write(e);
            out.write("0\nENDSEC\n0\nEOF\n");
        }
    }

    // -------------------------------------------------------------------------

    private static void layer(BufferedWriter out, String name, int color) throws IOException {
        out.write("0\nLAYER\n2\n" + name + "\n70\n0\n62\n" + color + "\n6\nContinuous\n");
    }

    private static String lwpolyline(List<double[]> pts, double ox, double oy,
                                      String layer, boolean closed) {
        StringBuilder sb = new StringBuilder();
        sb.append("0\nLWPOLYLINE\n");
        sb.append("8\n").append(layer).append("\n");
        sb.append("90\n").append(pts.size()).append("\n");
        sb.append("70\n").append(closed ? 1 : 0).append("\n");
        for (double[] p : pts) {
            sb.append(String.format(Locale.US, "10\n%.4f\n20\n%.4f\n",
                    p[0] + ox, p[1] + oy));
        }
        return sb.toString();
    }

    /** Split a polygon into open segments by inserting bridge gaps on long edges. */
    static List<List<double[]>> splitBridges(List<double[]> pts,
                                              double ox, double oy,
                                              double bridgeWidth, int bridgesPerEdge) {
        List<List<double[]>> segments = new ArrayList<>();
        List<double[]> cur = new ArrayList<>();
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            double[] p0 = pts.get(i);
            double[] p1 = pts.get((i + 1) % n);
            double x0 = p0[0] + ox, y0 = p0[1] + oy;
            double x1 = p1[0] + ox, y1 = p1[1] + oy;
            double edgeLen = Math.hypot(x1 - x0, y1 - y0);
            if (cur.isEmpty()) cur.add(new double[]{x0, y0});
            if (edgeLen < 3 * bridgeWidth) {
                cur.add(new double[]{x1, y1});
            } else {
                double ux = (x1 - x0) / edgeLen, uy = (y1 - y0) / edgeLen;
                double segLen = edgeLen / bridgesPerEdge;
                for (int b = 0; b < bridgesPerEdge; b++) {
                    double bStart = segLen * b + (segLen - bridgeWidth) / 2;
                    double bEnd   = bStart + bridgeWidth;
                    cur.add(new double[]{x0 + ux * bStart, y0 + uy * bStart});
                    segments.add(cur);
                    cur = new ArrayList<>();
                    cur.add(new double[]{x0 + ux * bEnd, y0 + uy * bEnd});
                }
                cur.add(new double[]{x1, y1});
            }
        }
        if (cur.size() >= 2) segments.add(cur);
        return segments;
    }
}
