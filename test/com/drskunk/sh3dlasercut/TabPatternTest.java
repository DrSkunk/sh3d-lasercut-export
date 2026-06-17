package com.drskunk.sh3dlasercut;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Box-joint segmentation: odd segment counts, finger-at-each-end polarity, and
 * complementary mating edges.
 */
public class TabPatternTest {

    private static final double EPS = 1e-9;

    @Test
    public void nonPositiveLengthYieldsNoTabs() {
        assertTrue(TabPattern.compute(0, 15, true).isEmpty());
        assertTrue(TabPattern.compute(-10, 15, true).isEmpty());
    }

    @Test
    public void startsWithTabPutsFingersAtBothEnds() {
        double length = 100;
        List<TabPattern.Span> tabs = TabPattern.compute(length, 15, true);
        // 7 segments (n=3) → tabs at indices 0,2,4,6 → 4 tabs.
        assertEquals(4, tabs.size());
        assertEquals(0.0, tabs.get(0).start, EPS);
        assertEquals(length, tabs.get(tabs.size() - 1).end, EPS);
    }

    @Test
    public void startsWithGapHasNoFingerAtStart() {
        List<TabPattern.Span> tabs = TabPattern.compute(100, 15, false);
        // tabs at indices 1,3,5 → 3 tabs, first one does not start at 0.
        assertEquals(3, tabs.size());
        assertTrue(tabs.get(0).start > 0);
    }

    @Test
    public void segmentCountIsAlwaysOdd() {
        for (double len = 5; len <= 500; len += 7) {
            int tabsA = TabPattern.compute(len, 15, true).size();
            int tabsB = TabPattern.compute(len, 15, false).size();
            // tab-start has n+1 tabs, gap-start has n tabs; total = 2n+1 (odd).
            assertTrue("segments must be odd at len=" + len, (tabsA + tabsB) % 2 == 1);
        }
    }

    @Test
    public void allTabSpansHaveEqualWidth() {
        double length = 137;
        List<TabPattern.Span> tabs = TabPattern.compute(length, 15, true);
        double w0 = tabs.get(0).width();
        for (TabPattern.Span s : tabs) {
            assertEquals(w0, s.width(), 1e-6);
        }
    }

    @Test
    public void matingEdgesAreComplementary() {
        double length = 120, target = 15;
        List<TabPattern.Span> tabs = TabPattern.compute(length, target, true);
        List<TabPattern.Span> gaps = TabPattern.compute(length, target, false);

        // A tab on one edge must coincide with a gap (a non-tab segment) on the
        // mating edge: the two tab sets are disjoint and together tile [0,length].
        double covered = 0;
        for (TabPattern.Span s : tabs) covered += s.width();
        for (TabPattern.Span s : gaps) covered += s.width();
        assertEquals(length, covered, 1e-6);

        for (TabPattern.Span a : tabs) {
            for (TabPattern.Span b : gaps) {
                double overlap = Math.min(a.end, b.end) - Math.max(a.start, b.start);
                assertFalse("tab and mating tab overlap", overlap > 1e-6);
            }
        }
    }

    @Test
    public void veryShortEdgeStillProducesAtLeastOneTab() {
        // length < targetWidth must not divide by zero or yield an empty edge.
        List<TabPattern.Span> tabs = TabPattern.compute(5, 15, true);
        assertEquals(2, tabs.size()); // 3 segments → tabs at 0 and 2
        assertEquals(0.0, tabs.get(0).start, EPS);
        assertEquals(5.0, tabs.get(1).end, EPS);
    }
}
