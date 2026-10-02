package org.eclipse.txray.ui;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.txray.Activator;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class TxRayPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public TxRayPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("TxRay runs the SQL of DBeaver consoles on the java.sql.Connection of a thread "
                + "suspended in the debugger, inside the transaction of the application.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // nothing
    }

    @Override
    protected void createFieldEditors() {
        IntegerFieldEditor rows = new IntegerFieldEditor(Preferences.MAX_ROWS, "Maximum &rows per query:",
                getFieldEditorParent());
        rows.setValidRange(1, 1_000_000);
        addField(rows);
        IntegerFieldEditor bytes = new IntegerFieldEditor(Preferences.MAX_BINARY_BYTES,
                "Maximum &bytes read per binary value:", getFieldEditorParent());
        bytes.setValidRange(0, 10_000_000);
        addField(bytes);
    }
}
