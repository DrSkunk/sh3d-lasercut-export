package com.drskunk.sh3dlasercut;

import org.junit.Test;

import java.awt.Color;

import static org.junit.Assert.assertEquals;

/**
 * Hex ↔ {@link Color} conversion used for the cut-stroke color setting.
 */
public class SVGWriterColorTest {

    @Test
    public void redRoundTrips() {
        assertEquals("#FF0000", SVGWriter.toHex(Color.RED));
        assertEquals(Color.RED, SVGWriter.fromHex("#FF0000"));
    }

    @Test
    public void arbitraryColorRoundTrips() {
        Color c = new Color(0x12, 0x34, 0x56);
        assertEquals(c, SVGWriter.fromHex(SVGWriter.toHex(c)));
    }

    @Test
    public void acceptsShorthandHex() {
        assertEquals(new Color(0xFF, 0x00, 0x00), SVGWriter.fromHex("#f00"));
    }

    @Test
    public void acceptsHexWithoutHash() {
        assertEquals(new Color(0x00, 0xFF, 0x00), SVGWriter.fromHex("00FF00"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedHex() {
        SVGWriter.fromHex("#1234");
    }
}
