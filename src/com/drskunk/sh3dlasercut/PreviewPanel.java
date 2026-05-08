package com.drskunk.sh3dlasercut;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight Swing component that renders a {@link LayoutResult} fitted to
 * the panel size. Used by the options dialog to preview the cut layout live.
 */
public final class PreviewPanel extends JComponent {

    private static final int PADDING = 8;
    private static final Color BACKGROUND = Color.WHITE;
    private static final Color SHEET_OUTLINE = new Color(0xCCCCCC);
    private static final Color INFO_TEXT = new Color(0x444444);
    private static final Color EMPTY_TEXT = new Color(0x999999);

    private LayoutResult layout;
    private Color strokeColor = Color.RED;
    private String emptyMessage = "(no walls)";

    public PreviewPanel() {
        setPreferredSize(new Dimension(400, 460));
        setBackground(BACKGROUND);
        setOpaque(true);
        setBorder(BorderFactory.createLineBorder(new Color(0xAAAAAA)));
    }

    public void setLayout(LayoutResult layout, Color strokeColor) {
        this.layout = layout;
        this.strokeColor = strokeColor != null ? strokeColor : Color.RED;
        repaint();
    }

    public void setEmpty(String message) {
        this.layout = null;
        this.emptyMessage = message;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (layout == null || layout.shapes.isEmpty()) {
                drawCentered(g2, emptyMessage, EMPTY_TEXT);
                return;
            }

            // Determine layout bounds from shapes AND board rects.
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (List<double[]> shape : layout.shapes) {
                for (double[] p : shape) {
                    if (p[0] < minX) minX = p[0];
                    if (p[1] < minY) minY = p[1];
                    if (p[0] > maxX) maxX = p[0];
                    if (p[1] > maxY) maxY = p[1];
                }
            }
            for (double[] r : layout.boardRects) {
                if (r[0]        < minX) minX = r[0];
                if (r[1]        < minY) minY = r[1];
                if (r[0] + r[2] > maxX) maxX = r[0] + r[2];
                if (r[1] + r[3] > maxY) maxY = r[1] + r[3];
            }
            double bw = Math.max(1e-6, maxX - minX);
            double bh = Math.max(1e-6, maxY - minY);

            int infoBarHeight = 16;
            int availW = Math.max(1, getWidth()  - 2 * PADDING);
            int availH = Math.max(1, getHeight() - 2 * PADDING - infoBarHeight);
            double scale = Math.min(availW / bw, availH / bh);
            double offX = PADDING + (availW - bw * scale) / 2.0 - minX * scale;
            double offY = PADDING + (availH - bh * scale) / 2.0 - minY * scale;

            // Board outlines (dashed light gray) when board dimensions are set,
            // falling back to a single sheet outline for visual context.
            g2.setColor(SHEET_OUTLINE);
            if (!layout.boardRects.isEmpty()) {
                float[] dash = { 4f, 4f };
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10f, dash, 0f));
                for (double[] r : layout.boardRects) {
                    int rx = (int) Math.round(r[0] * scale + offX);
                    int ry = (int) Math.round(r[1] * scale + offY);
                    int rw = (int) Math.round(r[2] * scale);
                    int rh = (int) Math.round(r[3] * scale);
                    g2.drawRect(rx, ry, rw, rh);
                }
            } else {
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawRect(
                        (int) Math.round(minX * scale + offX),
                        (int) Math.round(minY * scale + offY),
                        (int) Math.round(bw * scale),
                        (int) Math.round(bh * scale));
            }

            // Cut paths in the chosen stroke color.
            g2.setColor(strokeColor);
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (List<double[]> shape : layout.shapes) {
                Path2D.Double path = new Path2D.Double();
                boolean first = true;
                for (double[] p : shape) {
                    double x = p[0] * scale + offX;
                    double y = p[1] * scale + offY;
                    if (first) {
                        path.moveTo(x, y);
                        first = false;
                    } else {
                        path.lineTo(x, y);
                    }
                }
                path.closePath();
                g2.draw(path);
            }

            // Wall placement guides in the same gray as labels.
            g2.setColor(new Color(0x888888));
            g2.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (List<double[]> shape : layout.referenceShapes) {
                Path2D.Double path = new Path2D.Double();
                boolean first = true;
                for (double[] p : shape) {
                    double x = p[0] * scale + offX;
                    double y = p[1] * scale + offY;
                    if (first) {
                        path.moveTo(x, y);
                        first = false;
                    } else {
                        path.lineTo(x, y);
                    }
                }
                path.closePath();
                g2.draw(path);
            }

            // Dimension readout along the bottom.
            g2.setColor(INFO_TEXT);
            g2.setFont(g2.getFont().deriveFont(11f));
            String text;
            if (!layout.boardRects.isEmpty()) {
                int n = layout.boardRects.size();
                double boardW = layout.boardRects.get(0)[2];
                double boardH = layout.boardRects.get(0)[3];
                text = String.format(Locale.US,
                        "%d board%s \u00b7 %.0f \u00d7 %.0f mm each   (%d shapes)",
                        n, n == 1 ? "" : "s", boardW, boardH, layout.shapes.size());
            } else {
                text = String.format(Locale.US,
                        "%.0f \u00d7 %.0f mm   (%d shapes)", bw, bh, layout.shapes.size());
            }
            g2.drawString(text, PADDING, getHeight() - PADDING / 2);
        } finally {
            g2.dispose();
        }
    }

    private void drawCentered(Graphics2D g2, String text, Color color) {
        if (text == null) return;
        g2.setColor(color);
        java.awt.FontMetrics fm = g2.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(text)) / 2;
        int y = getHeight() / 2;
        g2.drawString(text, x, y);
    }
}
