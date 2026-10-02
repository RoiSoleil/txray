package org.eclipse.txray.ui;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.txray.Activator;
import org.eclipse.txray.core.Binding;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens another SQL console on the connection already exposed for the selected thread. */
public class NewConsoleHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        Object element = HandlerUtil.getCurrentStructuredSelection(event).getFirstElement();
        IJavaThread thread = element instanceof IJavaStackFrame
                ? (IJavaThread) ((IJavaStackFrame) element).getThread()
                : element instanceof IJavaThread ? (IJavaThread) element : null;
        List<Binding> bindings = thread == null ? List.of() : Activator.getDefault().getBindings().forThread(thread);
        if (bindings.isEmpty()) {
            MessageDialog.openInformation(shell, "TxRay",
                    "No connection of this thread is exposed yet: use 'Open SQL Console on Thread Connection'.");
            return null;
        }
        Binding binding = bindings.get(bindings.size() - 1);
        Job job = Job.create("TxRay: opening a SQL console", monitor -> {
            try {
                Activator.getDefault().getConsoles().open(binding, monitor);
                return Status.OK_STATUS;
            } catch (Exception e) {
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID, e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
        return null;
    }
}
