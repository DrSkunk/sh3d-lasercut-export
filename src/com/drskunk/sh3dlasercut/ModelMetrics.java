package com.drskunk.sh3dlasercut;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of a Sweet Home 3D level's relevant dimensions, in real-world
 * millimeters (i.e. NOT yet scaled). Used by the options dialog to estimate
 * the final SVG output size live as the user adjusts settings.
 */
public final class ModelMetrics {
    /** Real-world width / height of the bounding box of all wall footprints. */
    public double floorWidth_mm;
    public double floorHeight_mm;

    /** Per-wall lengths, in real-world mm. */
    public final List<Double> wallLengths_mm = new ArrayList<>();
    /** Per-wall heights, in real-world mm. Same length as {@link #wallLengths_mm}. */
    public final List<Double> wallHeights_mm = new ArrayList<>();

    public boolean isEmpty() {
        return wallLengths_mm.isEmpty();
    }

    /**
     * Estimate the final SVG output size (width × height in mm) assuming the
     * given options. Mirrors the layout {@link LasercutExporter} performs:
     * floor on top, walls stacked vertically below, with spacing between.
     */
    public double[] estimateOutputSize(ExportOptions opts) {
        double sf = 1.0 / opts.scaleDivisor;
        double floorW = floorWidth_mm * sf + 2 * opts.floorMargin;
        double floorH = floorHeight_mm * sf + 2 * opts.floorMargin;

        double totalW = floorW;
        double totalH = floorH;
        boolean hasFloor = !isEmpty();

        for (int i = 0; i < wallLengths_mm.size(); i++) {
            // Wall piece including side-tab protrusions (2 × thickness wide max)
            // and bottom-tab protrusion (1 × thickness tall).
            double wl = wallLengths_mm.get(i) * sf + 2 * opts.materialThickness;
            double wh = wallHeights_mm.get(i) * sf + opts.materialThickness;
            if (wl > totalW) totalW = wl;
            if (hasFloor || i > 0) totalH += opts.layoutSpacing;
            totalH += wh;
        }

        // SVG padding (matches SVGWriter)
        double pad = 5.0;
        return new double[] { totalW + 2 * pad, totalH + 2 * pad };
    }
}
