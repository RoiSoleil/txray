package org.eclipse.txray.ui;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.txray.Activator;

/** Preference keys and defaults. */
public class Preferences extends AbstractPreferenceInitializer {

    public static final String MAX_ROWS = "maxRows";
    public static final String MAX_BINARY_BYTES = "maxBinaryBytes";

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore p = Activator.getDefault().getPreferenceStore();
        p.setDefault(MAX_ROWS, 500);
        p.setDefault(MAX_BINARY_BYTES, 4096);
    }
}
