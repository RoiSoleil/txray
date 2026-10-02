package org.eclipse.txray;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.txray.core.Bindings;
import org.eclipse.txray.core.DebuggerBackend;
import org.eclipse.txray.core.SqlExecutor;
import org.eclipse.txray.dbeaver.SqlConsoles;
import org.eclipse.txray.jdbc.TxRayDriver;
import org.eclipse.txray.ui.Preferences;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "org.eclipse.txray";

    private static Activator plugin;

    private Bindings bindings;
    private SqlConsoles consoles;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        bindings = new Bindings();
        consoles = new SqlConsoles();
        bindings.addRemovalListener(consoles::closed);
        bindings.start();
        TxRayDriver.setBackend(new DebuggerBackend(bindings, this::newExecutor));
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        TxRayDriver.setBackend(null);
        bindings.stop();
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public Bindings getBindings() {
        return bindings;
    }

    public SqlConsoles getConsoles() {
        return consoles;
    }

    private SqlExecutor newExecutor() {
        IPreferenceStore p = getPreferenceStore();
        return new SqlExecutor(Math.max(1, p.getInt(Preferences.MAX_ROWS)),
                Math.max(0, p.getInt(Preferences.MAX_BINARY_BYTES)));
    }
}
