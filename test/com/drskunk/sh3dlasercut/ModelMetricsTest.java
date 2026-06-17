package com.drskunk.sh3dlasercut;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Output-size estimation shown live in the options dialog.
 */
public class ModelMetricsTest {

    @Test
    public void emptyMetricsReportEmpty() {
        ModelMetrics m = new ModelMetrics();
        assertTrue(m.isEmpty());
    }

    @Test
    public void nonEmptyMetricsReportNonEmpty() {
        ModelMetrics m = new ModelMetrics();
        m.wallLengths_mm.add(1000.0);
        m.wallHeights_mm.add(500.0);
        assertFalse(m.isEmpty());
    }

    @Test
    public void estimateMatchesHandComputedLayout() {
        ModelMetrics m = new ModelMetrics();
        m.floorWidth_mm = 1000;
        m.floorHeight_mm = 200;
        m.wallLengths_mm.add(1000.0);
        m.wallHeights_mm.add(500.0);

        ExportOptions o = new ExportOptions();
        o.scaleDivisor = 10;      // sf = 0.1
        o.floorMargin = 20;
        o.materialThickness = 3;
        o.layoutSpacing = 10;

        // floorW = 1000*0.1 + 40 = 140 ; floorH = 200*0.1 + 40 = 60
        // wall:  wl = 1000*0.1 + 6 = 106 (< 140) ; wh = 500*0.1 + 3 = 53
        // totalH = 60 + 10 (spacing) + 53 = 123
        // + 2*pad(5) on each axis
        double[] size = m.estimateOutputSize(o);
        assertEquals(150.0, size[0], 1e-6);
        assertEquals(133.0, size[1], 1e-6);
    }

    @Test
    public void widerWallExpandsTotalWidth() {
        ModelMetrics m = new ModelMetrics();
        m.floorWidth_mm = 100;
        m.floorHeight_mm = 100;
        m.wallLengths_mm.add(5000.0); // far wider than the floor
        m.wallHeights_mm.add(300.0);

        ExportOptions o = new ExportOptions();
        o.scaleDivisor = 10;
        o.floorMargin = 0;
        o.materialThickness = 3;
        o.layoutSpacing = 0;

        // floorW = 10 ; wl = 500 + 6 = 506 → width driven by the wall.
        double[] size = m.estimateOutputSize(o);
        assertEquals(506.0 + 10.0, size[0], 1e-6);
    }
}
