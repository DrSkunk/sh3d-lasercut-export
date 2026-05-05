package com.drskunk.sh3dlasercut;

import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;

public class LasercutExportPlugin extends Plugin {
    @Override
    public PluginAction[] getActions() {
        return new PluginAction[] { new LasercutExportAction(this) };
    }
}
