package org.eclipse.txray.ui;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.txray.Activator;
import org.eclipse.txray.core.Binding;
import org.eclipse.txray.core.Bindings;
import org.eclipse.ui.handlers.HandlerUtil;

/** Stops exposing the connection of the selected thread (or every connection without selection). */
public class ReleaseHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        Object element = HandlerUtil.getCurrentStructuredSelection(event).getFirstElement();
        Bindings bindings = Activator.getDefault().getBindings();
        IJavaThread thread = null;
        if (element instanceof IJavaThread) {
            thread = (IJavaThread) element;
        } else if (element instanceof IJavaStackFrame) {
            thread = (IJavaThread) ((IJavaStackFrame) element).getThread();
        }
        List<Binding> released = thread == null ? bindings.all() : bindings.forThread(thread);
        released.forEach(bindings::remove);
        String who = thread == null ? "" : " of thread '" + name(thread) + "'";
        MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "TxRay",
                released.isEmpty() ? "No connection" + who + " is exposed."
                        : released.size() + " connection(s)" + who + " released.");
        return null;
    }

    private static String name(IJavaThread thread) {
        try {
            return thread.getName();
        } catch (DebugException e) {
            return "?";
        }
    }
}
