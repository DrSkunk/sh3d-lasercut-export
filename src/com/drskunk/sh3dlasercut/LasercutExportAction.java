package com.drskunk.sh3dlasercut;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.plugin.PluginAction;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;

public final class LasercutExportAction extends PluginAction {

    private static final String MENU = "Tools";
    private static final String NAME = "Export to lasercut SVG…";

    private final LasercutExportPlugin plugin;

    /** Cached so option values persist between invocations within a session. */
    private ExportOptions lastOptions = new ExportOptions();
    private File lastDirectory;

    public LasercutExportAction(LasercutExportPlugin plugin) {
        this.plugin = plugin;
        putPropertyValue(Property.NAME, NAME);
        putPropertyValue(Property.MENU, MENU);
        setEnabled(true);
    }

    @Override
    public void execute() {
        Component parent = findParentComponent();

        Home home = plugin.getHome();
        ModelMetrics metrics = LasercutExporter.computeMetrics(home);
        ExportOptions options = ExportOptionsPanel.showDialog(parent, lastOptions, metrics);
        if (options == null) return;
        lastOptions = options;

        File outputFile = chooseOutputFile(parent);
        if (outputFile == null) return;
        lastDirectory = outputFile.getParentFile();

        try {
            new LasercutExporter(home, options).export(outputFile);
            JOptionPane.showMessageDialog(parent,
                    "Exported to " + outputFile.getAbsolutePath(),
                    "Lasercut Export",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(parent,
                    "Export failed: " + e.getMessage(),
                    "Lasercut Export",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private File chooseOutputFile(Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save lasercut SVG");
        chooser.setFileFilter(new FileNameExtensionFilter("SVG files (*.svg)", "svg"));
        if (lastDirectory != null) {
            chooser.setCurrentDirectory(lastDirectory);
        }
        Home home = plugin.getHome();
        String name = home.getName();
        if (name == null || name.isEmpty()) {
            name = "lasercut";
        } else {
            // Strip any path / extension from the home name.
            String base = new File(name).getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            name = base + "-lasercut";
        }
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), name + ".svg"));

        int result = chooser.showSaveDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION) return null;
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".svg")) {
            file = new File(file.getParentFile(), file.getName() + ".svg");
        }
        if (file.exists()) {
            int overwrite = JOptionPane.showConfirmDialog(parent,
                    file.getName() + " already exists. Overwrite?",
                    "Confirm overwrite",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) return null;
        }
        return file;
    }

    /**
     * SH3D's plugin API doesn't expose the home view directly to actions, so
     * we fall back to the currently focused window. Good enough for a modal
     * dialog parent.
     */
    private Component findParentComponent() {
        Component focused = javax.swing.FocusManager.getCurrentManager().getActiveWindow();
        if (focused != null) return focused;
        return SwingUtilities.getWindowAncestor(null);
    }
}
