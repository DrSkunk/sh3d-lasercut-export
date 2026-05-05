package com.drskunk.sh3dlasercut;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Locale;

/**
 * Modal dialog that collects {@link ExportOptions} from the user.
 *
 * Shows a live "Estimated output: W × H mm" label that recomputes whenever
 * any size-affecting field changes.
 */
public final class ExportOptionsPanel {

    private ExportOptionsPanel() {}

    public static ExportOptions showDialog(Component parent, ExportOptions defaults, ModelMetrics metrics) {
        if (defaults == null) defaults = new ExportOptions();
        if (metrics == null) metrics = new ModelMetrics();

        final JTextField scaleField     = new JTextField(format(defaults.scaleDivisor), 8);
        final JTextField thicknessField = new JTextField(format(defaults.materialThickness), 8);
        final JTextField tabWidthField  = new JTextField(format(defaults.tabWidth), 8);
        final JTextField marginField    = new JTextField(format(defaults.floorMargin), 8);
        final JTextField spacingField   = new JTextField(format(defaults.layoutSpacing), 8);
        final JTextField strokeField    = new JTextField(format(defaults.svgStrokeWidth), 8);
        final JCheckBox  smoothBox      = new JCheckBox(
                "Smooth wall-to-wall connections (only floor connects to walls)",
                defaults.smoothConnections);

        final Color[] colorHolder = { defaults.cutStrokeColor != null ? defaults.cutStrokeColor : Color.RED };
        final JButton colorButton = new JButton();
        colorButton.setPreferredSize(new Dimension(80, 22));
        colorButton.setFocusPainted(false);
        applyColorButton(colorButton, colorHolder[0]);
        colorButton.addActionListener(e -> {
            Color picked = JColorChooser.showDialog(parent, "Cut stroke color", colorHolder[0]);
            if (picked != null) {
                colorHolder[0] = picked;
                applyColorButton(colorButton, picked);
            }
        });

        final JLabel previewLabel = new JLabel(" ");
        previewLabel.setFont(previewLabel.getFont().deriveFont(Font.BOLD));

        final JLabel modelInfoLabel = new JLabel(modelInfoText(metrics));
        modelInfoLabel.setForeground(new java.awt.Color(0x666666));

        final ModelMetrics finalMetrics = metrics;
        final Runnable updatePreview = () -> {
            try {
                ExportOptions tentative = readOptions(
                        scaleField, thicknessField, tabWidthField,
                        marginField, spacingField, strokeField, smoothBox, colorHolder[0]);
                double[] size = finalMetrics.estimateOutputSize(tentative);
                previewLabel.setForeground(java.awt.Color.BLACK);
                previewLabel.setText(String.format(Locale.US,
                        "Estimated output: %.0f × %.0f mm   (1:%.0f scale)",
                        size[0], size[1], tentative.scaleDivisor));
            } catch (RuntimeException ex) {
                previewLabel.setForeground(new java.awt.Color(0xAA0000));
                previewLabel.setText("Estimated output: —");
            }
        };

        DocumentListener live = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { updatePreview.run(); }
            @Override public void removeUpdate(DocumentEvent e)  { updatePreview.run(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreview.run(); }
        };
        scaleField.getDocument().addDocumentListener(live);
        thicknessField.getDocument().addDocumentListener(live);
        marginField.getDocument().addDocumentListener(live);
        spacingField.getDocument().addDocumentListener(live);
        // Tab width and stroke don't affect overall size; skip them for the preview.

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addRow(panel, c, row++, "Scale 1:", scaleField);
        addRow(panel, c, row++, "Material thickness (mm):", thicknessField);
        addRow(panel, c, row++, "Box-joint finger width (mm):", tabWidthField);
        addRow(panel, c, row++, "Floor margin (mm):", marginField);
        addRow(panel, c, row++, "Layout spacing (mm):", spacingField);
        addRow(panel, c, row++, "SVG stroke width (mm):", strokeField);
        addRow(panel, c, row++, "Cut stroke color:", colorButton);

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        panel.add(smoothBox, c);

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        c.insets = new Insets(10, 6, 2, 6);
        panel.add(modelInfoLabel, c);

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        c.insets = new Insets(2, 6, 8, 6);
        panel.add(previewLabel, c);

        updatePreview.run();

        int result = JOptionPane.showConfirmDialog(parent, panel,
                "Lasercut Export Options",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }

        try {
            return readOptions(scaleField, thicknessField, tabWidthField,
                    marginField, spacingField, strokeField, smoothBox, colorHolder[0]);
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(),
                    "Invalid input", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static ExportOptions readOptions(
            JTextField scaleField, JTextField thicknessField, JTextField tabWidthField,
            JTextField marginField, JTextField spacingField, JTextField strokeField,
            JCheckBox smoothBox, Color cutColor) {
        ExportOptions opts = new ExportOptions();
        opts.scaleDivisor      = parsePositive(scaleField.getText(),  "Scale divisor");
        opts.materialThickness = parsePositive(thicknessField.getText(), "Material thickness");
        opts.tabWidth          = parsePositive(tabWidthField.getText(),  "Finger width");
        opts.floorMargin       = parseNonNegative(marginField.getText(), "Floor margin");
        opts.layoutSpacing     = parseNonNegative(spacingField.getText(), "Layout spacing");
        opts.svgStrokeWidth    = parseNonNegative(strokeField.getText(),  "Stroke width");
        opts.smoothConnections = smoothBox.isSelected();
        opts.cutStrokeColor    = cutColor != null ? cutColor : Color.RED;
        return opts;
    }

    private static void applyColorButton(JButton btn, Color c) {
        btn.setBackground(c);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        btn.setText(SVGWriter.toHex(c));
        // Pick a contrasting label so the hex stays readable on any background.
        double luminance = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
        btn.setForeground(luminance < 0.5 ? Color.WHITE : Color.BLACK);
    }

    private static String modelInfoText(ModelMetrics metrics) {
        if (metrics.isEmpty()) {
            return "(no walls in selected level)";
        }
        return String.format(Locale.US,
                "Model: %d walls, footprint %.0f × %.0f mm at full scale",
                metrics.wallLengths_mm.size(),
                metrics.floorWidth_mm, metrics.floorHeight_mm);
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row, String label, Component field) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label, SwingConstants.RIGHT), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    private static double parsePositive(String s, String name) {
        double v = Double.parseDouble(s.trim());
        if (!(v > 0)) throw new NumberFormatException(name + " must be > 0");
        return v;
    }

    private static double parseNonNegative(String s, String name) {
        double v = Double.parseDouble(s.trim());
        if (v < 0) throw new NumberFormatException(name + " must be ≥ 0");
        return v;
    }

    private static String format(double v) {
        if (v == Math.rint(v)) return Integer.toString((int) v);
        return String.valueOf(v);
    }
}
