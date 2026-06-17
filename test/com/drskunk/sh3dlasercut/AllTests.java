package com.drskunk.sh3dlasercut;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Aggregates every unit test so the Ant {@code test} target can run the whole
 * suite with a single {@code JUnitCore} invocation.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        TabPatternTest.class,
        BridgeSplitTest.class,
        SVGWriterColorTest.class,
        ModelMetricsTest.class,
        WriterOutputTest.class,
})
public final class AllTests {
}
