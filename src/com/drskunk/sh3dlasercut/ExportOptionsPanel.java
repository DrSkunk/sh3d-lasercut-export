package com.drskunk.sh3dlasercut;

import com.eteks.sweethome3d.model.Home;

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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Locale;

/**
 * Modal dialog that collects {@link ExportOptions} from the user and shows a
 * live preview of the resulting cut layout.
 */
public final class ExportOptionsPanel {

    private static final Color WARNING_COLOR = new Color(0xCC6600);

    private ExportOptionsPanel() {}

    public static ExportOptions showDialog(Component parent, Home home, ExportOptions defaults) {
        if (defaults == null) defaults = new ExportOptions();
        final ModelMetrics metrics = LasercutExporter.computeMetrics(home);

        final JTextField scaleField     = new JTextField(format(defaults.scaleDivisor), 8);
        final JTextField thicknessField = new JTextField(format(defaults.materialThickness), 8);
        final JTextField tabWidthField  = new JTextField(format(defaults.tabWidth), 8);
        final JTextField marginField    = new JTextField(format(defaults.floorMargin), 8);
        final JTextField spacingField   = new JTextField(format(defaults.layoutSpacing), 8);
        final JTextField strokeField    = new JTextField(format(defaults.svgStrokeWidth), 8);
        final JCheckBox  smoothBox      = new JCheckBox(
                "Smooth wall-to-wall connections (only floor connects to walls)",
                defaults.smoothConnections);
        final JTextField boardWidthField  = new JTextField(format(defaults.boardWidth), 8);
        final JTextField boardHeightField = new JTextField(format(defaults.boardHeight), 8);
        final JCheckBox  splitFloorBox    = new JCheckBox(
                "Split floor with puzzle joints if too large",
                defaults.splitFloor);
        final JCheckBox  separateFilesBox = new JCheckBox(
                "Write one SVG file per board (requires both board dimensions)",
                defaults.separateFilesPerBoard);

        final Color[] colorHolder = { defaults.cutStrokeColor != null ? defaults.cutStrokeColor : Color.RED };
        final JButton colorButton = new JButton();
        colorButton.setPreferredSize(new Dimension(80, 22));
        colorButton.setFocusPainted(false);
        applyColorButton(colorButton, colorHolder[0]);

        final PreviewPanel preview = new PreviewPanel();
        final JLabel previewLabel = new JLabel(" ");
        previewLabel.setFont(previewLabel.getFont().deriveFont(Font.BOLD));
        final JLabel modelInfoLabel = new JLabel(modelInfoText(metrics));
        modelInfoLabel.setForeground(new Color(0x666666));

        final Runnable refresh = () -> {
            try {
                ExportOptions tentative = readOptions(
                        scaleField, thicknessField, tabWidthField,
                        marginField, spacingField, strokeField, smoothBox, colorHolder[0],
                        boardWidthField, boardHeightField, splitFloorBox, separateFilesBox);
                LayoutResult layout = new LasercutExporter(home, tentative).buildLayout();
                preview.setLayout(layout, tentative.cutStrokeColor);
                if (layout.boardWarning != null) {
                    previewLabel.setForeground(WARNING_COLOR);
                    previewLabel.setText("[!] " + layout.boardWarning);
                } else {
                    double[] size = metrics.estimateOutputSize(tentative);
                    previewLabel.setForeground(Color.BLACK);
                    previewLabel.setText(String.format(Locale.US,
                            "Estimated output: %.0f \u00d7 %.0f mm   (1:%.0f scale)",
                            size[0], size[1], tentative.scaleDivisor));
                }
            } catch (RuntimeException ex) {
                preview.setEmpty("(invalid options)");
                previewLabel.setForeground(new Color(0xAA0000));
                previewLabel.setText("Estimated output: —");
            }
        };

        DocumentListener live = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { refresh.run(); }
            @Override public void removeUpdate(DocumentEvent e)  { refresh.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh.run(); }
        };
        scaleField.getDocument().addDocumentListener(live);
        thicknessField.getDocument().addDocumentListener(live);
        tabWidthField.getDocument().addDocumentListener(live);
        marginField.getDocument().addDocumentListener(live);
        spacingField.getDocument().addDocumentListener(live);
        strokeField.getDocument().addDocumentListener(live);
        smoothBox.addActionListener(e -> refresh.run());
        boardWidthField.getDocument().addDocumentListener(live);
        boardHeightField.getDocument().addDocumentListener(live);
        splitFloorBox.addActionListener(e -> refresh.run());
        separateFilesBox.addActionListener(e -> refresh.run());
        colorButton.addActionListener(e -> {
            Color picked = JColorChooser.showDialog(colorButton, "Cut stroke color", colorHolder[0]);
            if (picked != null) {
                colorHolder[0] = picked;
                applyColorButton(colorButton, picked);
                refresh.run();
            }
        });

        // ---- form column -----------------------------------------------------
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addRow(form, c, row++, "Scale 1:", scaleField);
        addRow(form, c, row++, "Material thickness (mm):", thicknessField);
        addRow(form, c, row++, "Box-joint finger width (mm):", tabWidthField);
        addRow(form, c, row++, "Floor margin (mm):", marginField);
        addRow(form, c, row++, "Layout spacing (mm):", spacingField);
        addRow(form, c, row++, "SVG stroke width (mm):", strokeField);
        addRow(form, c, row++, "Cut stroke color:", colorButton);

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        form.add(smoothBox, c);

        addRow(form, c, row++, "Board width (mm, 0=no limit):", boardWidthField);
        addRow(form, c, row++, "Board height (mm, 0=no limit):", boardHeightField);

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        form.add(splitFloorBox, c);

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        form.add(separateFilesBox, c);

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        c.insets = new Insets(10, 6, 2, 6);
        form.add(modelInfoLabel, c);

        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        c.insets = new Insets(2, 6, 8, 6);
        form.add(previewLabel, c);

        // ---- preview column --------------------------------------------------
        JPanel previewWrap = new JPanel(new BorderLayout());
        previewWrap.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        JLabel previewTitle = new JLabel("Cut layout preview");
        previewTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        previewWrap.add(previewTitle, BorderLayout.NORTH);
        previewWrap.add(preview, BorderLayout.CENTER);

        // ---- combined --------------------------------------------------------
        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.WEST);
        root.add(previewWrap, BorderLayout.CENTER);

        refresh.run();

        int result = JOptionPane.showConfirmDialog(parent, root,
                "Lasercut Export Options",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }

        try {
            return readOptions(scaleField, thicknessField, tabWidthField,
                    marginField, spacingField, strokeField, smoothBox, colorHolder[0],
                    boardWidthField, boardHeightField, splitFloorBox, separateFilesBox);
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(),
                    "Invalid input", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static ExportOptions readOptions(
            JTextField scaleField, JTextField thicknessField, JTextField tabWidthField,
            JTextField marginField, JTextField spacingField, JTextField strokeField,
            JCheckBox smoothBox, Color cutColor,
            JTextField boardWidthField, JTextField boardHeightField,
            JCheckBox splitFloorBox, JCheckBox separateFilesBox) {
        ExportOptions opts = new ExportOptions();
        opts.scaleDivisor         = parsePositive(scaleField.getText(),      "Scale divisor");
        opts.materialThickness    = parsePositive(thicknessField.getText(),  "Material thickness");
        opts.tabWidth             = parsePositive(tabWidthField.getText(),   "Finger width");
        opts.floorMargin          = parseNonNegative(marginField.getText(),  "Floor margin");
        opts.layoutSpacing        = parseNonNegative(spacingField.getText(), "Layout spacing");
        opts.svgStrokeWidth       = parseNonNegative(strokeField.getText(),  "Stroke width");
        opts.smoothConnections    = smoothBox.isSelected();
        opts.cutStrokeColor       = cutColor != null ? cutColor : Color.RED;
        opts.boardWidth           = parseNonNegative(boardWidthField.getText(),  "Board width");
        opts.boardHeight          = parseNonNegative(boardHeightField.getText(), "Board height");
        opts.splitFloor           = splitFloorBox.isSelected();
        opts.separateFilesPerBoard = separateFilesBox.isSelected();
        return opts;
    }

    private static void applyColorButton(JButton btn, Color c) {
        btn.setBackground(c);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        btn.setText(SVGWriter.toHex(c));
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
