package com.drskunk.sh3dlasercut;

import org.junit.Test;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end file emission for the SVG and DXF writers.
 */
public class WriterOutputTest {

    private static List<double[]> square() {
        List<double[]> p = new ArrayList<>();
        p.add(new double[]{0, 0});
        p.add(new double[]{10, 0});
        p.add(new double[]{10, 10});
        p.add(new double[]{0, 10});
        return p;
    }

    private static String writeAndRead(Object writer) throws IOException {
        File f = File.createTempFile("lasercut-test", ".out");
        f.deleteOnExit();
        if (writer instanceof SVGWriter) ((SVGWriter) writer).write(f);
        else ((DXFWriter) writer).write(f);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void svgContainsHeaderPathAndStrokeColor() throws IOException {
        SVGWriter svg = new SVGWriter(0.1, Color.RED);
        svg.addPolygon(square(), 0, 0);
        String out = writeAndRead(svg);

        assertTrue(out.contains("<svg"));
        assertTrue(out.contains("viewBox"));
        assertTrue(out.contains("<path"));
        assertTrue(out.contains("#FF0000"));
    }

    @Test
    public void svgEmptyWriteThrows() {
        SVGWriter svg = new SVGWriter(0.1, Color.RED);
        try {
            File f = File.createTempFile("lasercut-empty", ".svg");
            f.deleteOnExit();
            svg.write(f);
            fail("expected IOException for empty SVG");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void dxfContainsR2000HeaderAndCutLayer() throws IOException {
        DXFWriter dxf = new DXFWriter();
        dxf.addPolygon(square(), 0, 0);
        String out = writeAndRead(dxf);

        assertTrue(out.contains("LWPOLYLINE"));
        assertTrue(out.contains("$INSUNITS"));
        assertTrue(out.contains("CUT"));
        assertTrue(out.contains("EOF"));
    }

    @Test
    public void dxfEmptyWriteThrows() {
        DXFWriter dxf = new DXFWriter();
        try {
            File f = File.createTempFile("lasercut-empty", ".dxf");
            f.deleteOnExit();
            dxf.write(f);
            fail("expected IOException for empty DXF");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void dxfBoardOutlineGoesOnBoardLayer() throws IOException {
        DXFWriter dxf = new DXFWriter();
        dxf.addBoardOutline(0, 0, 600, 400);
        String out = writeAndRead(dxf);
        assertTrue(out.contains("BOARD"));
    }
}
