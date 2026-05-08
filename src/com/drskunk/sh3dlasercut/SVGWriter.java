package com.drskunk.sh3dlasercut;

import java.awt.Color;
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
 * Minimal SVG writer for laser-cut layouts.
 *
 * All coordinates are millimeters. Cut lines are emitted as black hairline
 * strokes with no fill — the convention most laser-cutting drivers expect.
 */
public final class SVGWriter {

    private final double strokeWidthMm;
    private final String strokeColorHex;
    private final List<String> bodyElements = new ArrayList<>();
    private double minX = Double.POSITIVE_INFINITY;
    private double minY = Double.POSITIVE_INFINITY;
    private double maxX = Double.NEGATIVE_INFINITY;
    private double maxY = Double.NEGATIVE_INFINITY;

    public SVGWriter(double strokeWidthMm, Color strokeColor) {
        this.strokeWidthMm = strokeWidthMm;
        this.strokeColorHex = toHex(strokeColor != null ? strokeColor : Color.RED);
    }

    public static String toHex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static Color fromHex(String hex) {
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            // #RGB shorthand → #RRGGBB
            StringBuilder sb = new StringBuilder(6);
            for (char ch : s.toCharArray()) { sb.append(ch).append(ch); }
            s = sb.toString();
        }
        if (s.length() != 6) throw new IllegalArgumentException("Bad hex color: " + hex);
        return new Color(Integer.parseInt(s, 16));
    }

    /** Emit a closed polygon path. {@code points} is a list of {x, y} pairs. */
    public void addPolygon(List<double[]> points, double offsetX, double offsetY) {
        if (points.isEmpty()) return;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            double x = p[0] + offsetX;
            double y = p[1] + offsetY;
            d.append(i == 0 ? "M " : " L ");
            d.append(fmt(x)).append(' ').append(fmt(y));
            updateBounds(x, y);
        }
        d.append(" Z");
        bodyElements.add("<path d=\"" + d + "\" />");
    }

    /** Emit a closed polygon from an array of points (each {x, y}). */
    public void addPolygon(double[][] points, double offsetX, double offsetY) {
        List<double[]> list = new ArrayList<>(points.length);
        for (double[] p : points) list.add(p);
        addPolygon(list, offsetX, offsetY);
    }

    /** Emit a board outline rectangle (light gray, dashed). */
    public void addBoardOutline(double x, double y, double w, double h) {
        bodyElements.add(String.format(Locale.US,
                "<rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" "
                        + "stroke=\"#CCCCCC\" stroke-width=\"0.5\" "
                        + "stroke-dasharray=\"2 2\" fill=\"none\" />",
                fmt(x), fmt(y), fmt(w), fmt(h)));
        updateBounds(x, y);
        updateBounds(x + w, y + h);
    }

    /** Light-gray closed path for assembly-guide reference marks (e.g., wall footprints). */
    public void addReferencePath(List<double[]> points, double offsetX, double offsetY) {
        if (points.isEmpty()) return;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            double x = p[0] + offsetX;
            double y = p[1] + offsetY;
            d.append(i == 0 ? "M " : " L ");
            d.append(fmt(x)).append(' ').append(fmt(y));
            updateBounds(x, y);
        }
        d.append(" Z");
        bodyElements.add("<path d=\"" + d + "\" stroke=\"#888888\" stroke-width=\"0.2\" />");
    }

    /** Light-gray label, intended for engraving or cosmetic identification. */
    public void addLabel(String text, double x, double y, double sizeMm) {
        bodyElements.add(String.format(Locale.US,
                "<text x=\"%s\" y=\"%s\" font-size=\"%s\" fill=\"#888888\" stroke=\"none\" font-family=\"sans-serif\">%s</text>",
                fmt(x), fmt(y), fmt(sizeMm), escapeXml(text)));
        updateBounds(x, y);
        updateBounds(x + text.length() * sizeMm, y - sizeMm);
    }

    public void write(File file) throws IOException {
        if (bodyElements.isEmpty()) {
            throw new IOException("Nothing to write");
        }
        double pad = 5.0;
        double w = (maxX - minX) + 2 * pad;
        double h = (maxY - minY) + 2 * pad;
        double tx = -minX + pad;
        double ty = -minY + pad;

        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            out.write(String.format(Locale.US,
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" "
                            + "width=\"%smm\" height=\"%smm\" viewBox=\"0 0 %s %s\">\n",
                    fmt(w), fmt(h), fmt(w), fmt(h)));
            out.write(String.format(Locale.US,
                    "  <g transform=\"translate(%s %s)\" "
                            + "fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" "
                            + "stroke-linecap=\"round\" stroke-linejoin=\"round\">\n",
                    fmt(tx), fmt(ty), strokeColorHex, fmt(strokeWidthMm)));
            for (String element : bodyElements) {
                out.write("    ");
                out.write(element);
                out.write('\n');
            }
            out.write("  </g>\n</svg>\n");
        }
    }

    private void updateBounds(double x, double y) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
    }

    private static String fmt(double v) {
        if (Math.abs(v) < 1e-6) v = 0;
        return String.format(Locale.US, "%.4f", v);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
