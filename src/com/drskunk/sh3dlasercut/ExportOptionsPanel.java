package com.drskunk.sh3dlasercut;

import com.eteks.sweethome3d.model.Home;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
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

        final JTextField scaleField       = new JTextField(format(defaults.scaleDivisor), 8);
        final JTextField thicknessField   = new JTextField(format(defaults.materialThickness), 8);
        final JTextField tabWidthField    = new JTextField(format(defaults.tabWidth), 8);
        final JTextField marginField      = new JTextField(format(defaults.floorMargin), 8);
        final JTextField spacingField     = new JTextField(format(defaults.layoutSpacing), 8);
        final JTextField strokeField      = new JTextField(format(defaults.svgStrokeWidth), 8);
        final JTextField kerfField        = new JTextField(format(defaults.kerfMm), 8);
        final JTextField bridgeField      = new JTextField(format(defaults.bridgeWidth), 8);
        final JTextField bridgesPerField  = new JTextField(String.valueOf(defaults.bridgesPerEdge), 8);
        final JCheckBox  smoothBox        = new JCheckBox(
                "Smooth connections — no finger joints, glue only",
                defaults.smoothConnections);
        final JTextField boardWidthField  = new JTextField(format(defaults.boardWidth), 8);
        final JTextField boardHeightField = new JTextField(format(defaults.boardHeight), 8);
        final JCheckBox  splitFloorBox    = new JCheckBox(
                "Split floor into interlocking tiles if too large",
                defaults.splitFloor);
        final JCheckBox  separateFilesBox = new JCheckBox(
                "Write one file per board",
                defaults.separateFilesPerBoard);

        final JComboBox<String> slopingBox = new JComboBox<>(new String[]{
                "Compensate — clip joints to height",
                "Smooth — no joints on sloped ends"
        });
        slopingBox.setSelectedIndex(
                defaults.slopingWallMode == ExportOptions.SlopingWallMode.SMOOTH ? 1 : 0);
        final JLabel slopingLabel = new JLabel("Sloping wall joints:", SwingConstants.RIGHT);

        final JComboBox<String> formatBox = new JComboBox<>(new String[]{"SVG", "DXF", "SVG + DXF"});
        formatBox.setSelectedIndex(
                defaults.exportFormat == ExportOptions.ExportFormat.DXF  ? 1 :
                defaults.exportFormat == ExportOptions.ExportFormat.BOTH ? 2 : 0);

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
                        marginField, spacingField, strokeField,
                        kerfField, bridgeField, bridgesPerField,
                        smoothBox, slopingBox, colorHolder[0],
                        boardWidthField, boardHeightField, splitFloorBox, separateFilesBox,
                        formatBox);
                LayoutResult layout = new LasercutExporter(home, tentative).buildLayout();
                preview.setLayout(layout, tentative.cutStrokeColor);
                if (layout.boardWarning != null) {
                    previewLabel.setForeground(WARNING_COLOR);
                    previewLabel.setText("[!] " + layout.boardWarning);
                } else {
                    double[] size = metrics.estimateOutputSize(tentative);
                    previewLabel.setForeground(Color.BLACK);
                    previewLabel.setText(String.format(Locale.US,
                            "Estimated output: %.0f × %.0f mm   (1:%.0f scale)",
                            size[0], size[1], tentative.scaleDivisor));
                }
            } catch (Exception ex) {
                preview.setEmpty("(invalid options)");
                previewLabel.setForeground(new Color(0xAA0000));
                previewLabel.setText("Estimated output: —");
            }
        };

        final Runnable updateEnabled = () -> {
            boolean smooth = smoothBox.isSelected();
            slopingLabel.setEnabled(!smooth);
            slopingBox.setEnabled(!smooth);

            double bw = 0, bh = 0;
            try { bw = Double.parseDouble(boardWidthField.getText().trim()); } catch (Exception ignored) {}
            try { bh = Double.parseDouble(boardHeightField.getText().trim()); } catch (Exception ignored) {}
            boolean hasBoard = bw > 0 && bh > 0;
            splitFloorBox.setEnabled(hasBoard);
            separateFilesBox.setEnabled(hasBoard);
        };

        final Runnable update = () -> { refresh.run(); updateEnabled.run(); };

        DocumentListener live = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { update.run(); }
            @Override public void removeUpdate(DocumentEvent e)  { update.run(); }
            @Override public void changedUpdate(DocumentEvent e) { update.run(); }
        };
        scaleField.getDocument().addDocumentListener(live);
        thicknessField.getDocument().addDocumentListener(live);
        tabWidthField.getDocument().addDocumentListener(live);
        marginField.getDocument().addDocumentListener(live);
        spacingField.getDocument().addDocumentListener(live);
        strokeField.getDocument().addDocumentListener(live);
        kerfField.getDocument().addDocumentListener(live);
        bridgeField.getDocument().addDocumentListener(live);
        bridgesPerField.getDocument().addDocumentListener(live);
        boardWidthField.getDocument().addDocumentListener(live);
        boardHeightField.getDocument().addDocumentListener(live);
        smoothBox.addActionListener(e -> update.run());
        slopingBox.addActionListener(e -> refresh.run());
        splitFloorBox.addActionListener(e -> refresh.run());
        separateFilesBox.addActionListener(e -> refresh.run());
        formatBox.addActionListener(e -> refresh.run());
        colorButton.addActionListener(e -> {
            Color picked = JColorChooser.showDialog(colorButton, "Cut stroke color", colorHolder[0]);
            if (picked != null) {
                colorHolder[0] = picked;
                applyColorButton(colorButton, picked);
                refresh.run();
            }
        });

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;

        // ---- Section 1: Scale & Material ------------------------------------
        JPanel scalePanel = makeSection("Scale & Material");
        addRow(scalePanel, c, 0, "Scale (1:N):", scaleField);
        addRow(scalePanel, c, 1, "Material thickness (mm):", thicknessField);
        addRow(scalePanel, c, 2, "Finger width (mm):", tabWidthField);

        // ---- Section 2: Connections -----------------------------------------
        JPanel jointsPanel = makeSection("Connections");
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL;
        jointsPanel.add(smoothBox, c);
        c.gridx = 0; c.gridy = 1; c.gridwidth = 1; c.fill = GridBagConstraints.NONE;
        jointsPanel.add(slopingLabel, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        jointsPanel.add(slopingBox, c);

        // ---- Section 3: Layout & Output -------------------------------------
        JPanel layoutPanel = makeSection("Layout & Output");
        addRow(layoutPanel, c, 0, "Floor margin (mm):", marginField);
        addRow(layoutPanel, c, 1, "Layout spacing (mm):", spacingField);
        addRow(layoutPanel, c, 2, "SVG stroke width (mm):", strokeField);
        addRow(layoutPanel, c, 3, "Cut color:", colorButton);
        addRow(layoutPanel, c, 4, "Export format:", formatBox);

        // ---- Section 4: Cut Quality -----------------------------------------
        JPanel qualityPanel = makeSection("Cut Quality");
        addRow(qualityPanel, c, 0, "Kerf compensation (mm):", kerfField);
        addRow(qualityPanel, c, 1, "Bridge width (mm):", bridgeField);
        addRow(qualityPanel, c, 2, "Bridges per edge:", bridgesPerField);

        // ---- Section 5: Board -----------------------------------------------
        JPanel boardPanel = makeSection("Board");
        addRow(boardPanel, c, 0, "Board width (mm):", boardWidthField);
        addRow(boardPanel, c, 1, "Board height (mm):", boardHeightField);

        JLabel boardHint = new JLabel("Enter 0 for no board size limit");
        boardHint.setForeground(new Color(0x888888));
        boardHint.setFont(boardHint.getFont().deriveFont(10f));
        c.gridx = 1; c.gridy = 2; c.gridwidth = 1; c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 6, 4, 6);
        boardPanel.add(boardHint, c);
        c.insets = new Insets(3, 6, 3, 6);

        c.gridx = 0; c.gridy = 3; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL;
        boardPanel.add(splitFloorBox, c);
        c.gridy = 4;
        boardPanel.add(separateFilesBox, c);

        // ---- Status row (model info + preview label) ------------------------
        JPanel statusPanel = new JPanel(new GridBagLayout());
        GridBagConstraints sc = new GridBagConstraints();
        sc.anchor = GridBagConstraints.WEST;
        sc.insets = new Insets(4, 8, 2, 6);
        sc.gridx = 0; sc.gridy = 0;
        statusPanel.add(modelInfoLabel, sc);
        sc.insets = new Insets(2, 8, 6, 6);
        sc.gridy = 1;
        statusPanel.add(previewLabel, sc);

        // ---- Assemble form --------------------------------------------------
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        for (JPanel section : new JPanel[]{scalePanel, jointsPanel, layoutPanel, qualityPanel, boardPanel}) {
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(section);
        }
        statusPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(statusPanel);

        // ---- Preview column -------------------------------------------------
        JPanel previewWrap = new JPanel(new BorderLayout());
        previewWrap.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        JLabel previewTitle = new JLabel("Cut layout preview");
        previewTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        previewWrap.add(previewTitle, BorderLayout.NORTH);
        previewWrap.add(preview, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.WEST);
        root.add(previewWrap, BorderLayout.CENTER);

        update.run();

        int result = JOptionPane.showConfirmDialog(parent, root,
                "Lasercut Export Options",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        try {
            return readOptions(scaleField, thicknessField, tabWidthField,
                    marginField, spacingField, strokeField,
                    kerfField, bridgeField, bridgesPerField,
                    smoothBox, slopingBox, colorHolder[0],
                    boardWidthField, boardHeightField, splitFloorBox, separateFilesBox,
                    formatBox);
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(),
                    "Invalid input", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private static ExportOptions readOptions(
            JTextField scaleField, JTextField thicknessField, JTextField tabWidthField,
            JTextField marginField, JTextField spacingField, JTextField strokeField,
            JTextField kerfField, JTextField bridgeField, JTextField bridgesPerField,
            JCheckBox smoothBox, JComboBox<String> slopingBox, Color cutColor,
            JTextField boardWidthField, JTextField boardHeightField,
            JCheckBox splitFloorBox, JCheckBox separateFilesBox,
            JComboBox<String> formatBox) {
        ExportOptions opts = new ExportOptions();
        opts.scaleDivisor          = parsePositive(scaleField.getText(),      "Scale divisor");
        opts.materialThickness     = parsePositive(thicknessField.getText(),  "Material thickness");
        opts.tabWidth              = parsePositive(tabWidthField.getText(),   "Finger width");
        opts.floorMargin           = parseNonNegative(marginField.getText(),  "Floor margin");
        opts.layoutSpacing         = parseNonNegative(spacingField.getText(), "Layout spacing");
        opts.svgStrokeWidth        = parseNonNegative(strokeField.getText(),  "Stroke width");
        opts.kerfMm                = parseNonNegative(kerfField.getText(),    "Kerf compensation");
        opts.bridgeWidth           = parseNonNegative(bridgeField.getText(),  "Bridge width");
        opts.bridgesPerEdge        = Math.max(1, (int) parsePositive(bridgesPerField.getText(), "Bridges per edge"));
        opts.smoothConnections     = smoothBox.isSelected();
        opts.slopingWallMode       = slopingBox.getSelectedIndex() == 1
                ? ExportOptions.SlopingWallMode.SMOOTH
                : ExportOptions.SlopingWallMode.COMPENSATE;
        opts.cutStrokeColor        = cutColor != null ? cutColor : Color.RED;
        opts.boardWidth            = parseNonNegative(boardWidthField.getText(),  "Board width");
        opts.boardHeight           = parseNonNegative(boardHeightField.getText(), "Board height");
        opts.splitFloor            = splitFloorBox.isSelected();
        opts.separateFilesPerBoard = separateFilesBox.isSelected();
        int fmtIdx = formatBox.getSelectedIndex();
        opts.exportFormat = fmtIdx == 1 ? ExportOptions.ExportFormat.DXF
                          : fmtIdx == 2 ? ExportOptions.ExportFormat.BOTH
                          : ExportOptions.ExportFormat.SVG;
        return opts;
    }

    private static JPanel makeSection(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title));
        return p;
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row, String label, Component field) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label, SwingConstants.RIGHT), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
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
