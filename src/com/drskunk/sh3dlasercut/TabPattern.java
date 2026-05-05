package com.drskunk.sh3dlasercut;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes finger / slot positions for a box-joint edge of given length.
 *
 * The edge is divided into an odd number of equal-width segments. Segments
 * alternate between "tab" (raised material) and "gap" (cutaway). Two mating
 * edges share the same segmentation but with opposite polarity, so the tabs
 * of one fit the gaps of the other.
 */
public final class TabPattern {

    /** A single tab span along the edge, in edge-local coordinates [start, end]. */
    public static final class Span {
        public final double start;
        public final double end;
        public Span(double start, double end) {
            this.start = start;
            this.end = end;
        }
        public double width() { return end - start; }
    }

    private TabPattern() {}

    /**
     * @param length      total edge length
     * @param targetWidth desired width of each finger / gap
     * @param startsWithTab if true, segment 0 is a tab; otherwise segment 0 is a gap
     * @return list of tab spans along the edge
     */
    public static List<Span> compute(double length, double targetWidth, boolean startsWithTab) {
        if (length <= 0) {
            return new ArrayList<>();
        }
        // Use 2N+1 segments — odd count so the pattern is symmetric.
        int n = (int) Math.max(1, Math.round((length / targetWidth - 1) / 2.0));
        int segments = 2 * n + 1;
        double segW = length / segments;

        List<Span> tabs = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            boolean isTab = startsWithTab ? (i % 2 == 0) : (i % 2 == 1);
            if (isTab) {
                tabs.add(new Span(i * segW, (i + 1) * segW));
            }
        }
        return tabs;
    }
}
