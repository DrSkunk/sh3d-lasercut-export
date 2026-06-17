package com.drskunk.sh3dlasercut;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Bridge / holding-tab splitting of outer profiles.
 */
public class BridgeSplitTest {

    private static List<double[]> rect(double w, double h) {
        List<double[]> p = new ArrayList<>();
        p.add(new double[]{0, 0});
        p.add(new double[]{w, 0});
        p.add(new double[]{w, h});
        p.add(new double[]{0, h});
        return p;
    }

    private static double polylineLength(List<double[]> seg) {
        double len = 0;
        for (int i = 1; i < seg.size(); i++) {
            len += Math.hypot(seg.get(i)[0] - seg.get(i - 1)[0],
                              seg.get(i)[1] - seg.get(i - 1)[1]);
        }
        return len;
    }

    @Test
    public void shortEdgesAreNotBridged() {
        // A 10×10 square with bridgeWidth=10: every edge (10) < 3*10, so the
        // whole outline stays a single uncut loop.
        List<List<double[]>> segs = DXFWriter.splitBridges(rect(10, 10), 0, 0, 10, 2);
        assertEquals(1, segs.size());
    }

    @Test
    public void longEdgesAreSplitByBridgeCount() {
        // 100×100 square, bridgeWidth=4, 2 bridges per edge → 8 gaps total.
        List<List<double[]>> segs = DXFWriter.splitBridges(rect(100, 100), 0, 0, 4, 2);
        // Each bridge introduces one break; the polygon start adds one more seam.
        assertTrue("expected multiple cut segments", segs.size() >= 8);
    }

    @Test
    public void totalCutLengthEqualsPerimeterMinusBridges() {
        double w = 100, h = 60, bridgeWidth = 4;
        int bridgesPerEdge = 2;
        List<List<double[]>> segs = DXFWriter.splitBridges(rect(w, h), 0, 0,
                bridgeWidth, bridgesPerEdge);

        double perimeter = 2 * (w + h);
        // All four edges exceed 3*bridgeWidth, so every edge is bridged.
        int numBridges = 4 * bridgesPerEdge;
        double expected = perimeter - numBridges * bridgeWidth;

        double total = 0;
        for (List<double[]> seg : segs) total += polylineLength(seg);
        assertEquals(expected, total, 1e-6);
    }

    @Test
    public void offsetIsAppliedToOutputPoints() {
        List<List<double[]>> segs = DXFWriter.splitBridges(rect(100, 100), 1000, 2000, 4, 2);
        for (List<double[]> seg : segs) {
            for (double[] p : seg) {
                assertTrue(p[0] >= 1000 - 1e-6 && p[0] <= 1100 + 1e-6);
                assertTrue(p[1] >= 2000 - 1e-6 && p[1] <= 2100 + 1e-6);
            }
        }
    }
}
