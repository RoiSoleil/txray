package org.eclipse.txray.ui;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.debug.eval.EvaluationManager;
import org.eclipse.jdt.debug.eval.IAstEvaluationEngine;
import org.eclipse.jdt.debug.eval.IEvaluationResult;
import org.eclipse.jdt.internal.debug.core.JavaDebugUtils;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.txray.Activator;
import org.eclipse.txray.core.Binding;
import org.eclipse.txray.core.ConnectionFinder;
import org.eclipse.txray.core.ConnectionFinder.Candidate;
import org.eclipse.txray.core.SqlExecutor;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * "Open SQL Console on Thread Connection": on a thread or a stack frame of the Debug view, finds the
 * connections of the thread; on a variable or an expression, uses its value (a Connection, a
 * Hibernate Session, an EntityManager, a Spring holder...). A DBeaver SQL console is then opened
 * on the chosen connection: its SQL runs in the thread, inside the application's transaction.
 */
@SuppressWarnings("restriction")
public class ExposeHandler extends AbstractHandler {

    private static final String EXPRESSION = "Evaluate an expression in the selected frame…";
    private static final String TITLE = "TxRay";

    @Override
    public Object execute(ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);
        Object element = selection.getFirstElement();
        try {
            if (element instanceof IJavaThread) {
                searchThread(shell, (IJavaThread) element, null);
            } else if (element instanceof IJavaStackFrame) {
                IJavaStackFrame frame = (IJavaStackFrame) element;
                searchThread(shell, (IJavaThread) frame.getThread(), frame);
            } else if (element instanceof IVariable) {
                IVariable v = (IVariable) element;
                fromValue(shell, v.getValue(), "variable '" + v.getName() + "'");
            } else if (element instanceof IExpression) {
                IExpression e = (IExpression) element;
                fromValue(shell, e.getValue(), "expression '" + e.getExpressionText() + "'");
            }
        } catch (DebugException e) {
            MessageDialog.openError(shell, TITLE, e.getMessage());
        }
        return null;
    }

    // ---- sources of connections ----------------------------------------------------------

    private void searchThread(Shell shell, IJavaThread thread, IJavaStackFrame frame) {
        if (!thread.isSuspended()) {
            MessageDialog.openInformation(shell, TITLE,
                    "Suspend the thread on a breakpoint (or a step) first: TxRay runs the SQL in this thread.");
            return;
        }
        background("TxRay: looking for the JDBC connections of the thread", () -> {
            List<Candidate> candidates = new ConnectionFinder(thread).find();
            ui(() -> choose(shell, thread, frame, candidates));
        });
    }

    private void fromValue(Shell shell, IValue value, String origin) {
        IJavaStackFrame frame = currentFrame();
        if (!(value instanceof IJavaValue) || frame == null) {
            MessageDialog.openInformation(shell, TITLE, "Select a variable of a thread suspended in a Java debug session.");
            return;
        }
        IJavaThread thread = (IJavaThread) frame.getThread();
        if (thread.getDebugTarget() != value.getDebugTarget()) {
            MessageDialog.openInformation(shell, TITLE, "The variable does not belong to the selected thread.");
            return;
        }
        background("TxRay: reading " + origin, () -> {
            List<Candidate> candidates = new ConnectionFinder(thread).fromValue((IJavaValue) value, origin);
            ui(() -> {
                if (candidates.isEmpty()) {
                    MessageDialog.openInformation(shell, TITLE, "No JDBC connection behind " + origin
                            + ". Supported: java.sql.Connection, Hibernate Session, EntityManager, "
                            + "Spring ConnectionHolder / EntityManagerHolder / SessionHolder.");
                } else {
                    choose(shell, thread, frame, candidates);
                }
            });
        });
    }

    private static IJavaStackFrame currentFrame() {
        IAdaptable context = DebugUITools.getDebugContext();
        if (context == null) {
            return null;
        }
        IJavaStackFrame frame = context.getAdapter(IJavaStackFrame.class);
        if (frame != null) {
            return frame;
        }
        IJavaThread thread = context.getAdapter(IJavaThread.class);
        try {
            return thread == null ? null : (IJavaStackFrame) thread.getTopStackFrame();
        } catch (DebugException e) {
            return null;
        }
    }

    // ---- choice --------------------------------------------------------------------------

    private void choose(Shell shell, IJavaThread thread, IJavaStackFrame frame, List<Candidate> candidates) {
        if (candidates.size() == 1) {
            expose(shell, thread, candidates.get(0));
            return;
        }
        if (candidates.isEmpty()) {
            askExpression(shell, thread, frame, "No JDBC connection found in the thread locals and the stack of '"
                    + name(thread) + "'.\n\nExpression returning the connection (as typed in the Debug Shell), "
                    + "a Hibernate Session or an EntityManager:");
            return;
        }
        List<Object> elements = new ArrayList<>(candidates);
        elements.add(EXPRESSION);
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof Candidate ? ((Candidate) element).getLabel() : String.valueOf(element);
            }
        });
        dialog.setTitle(TITLE);
        dialog.setMessage("JDBC connections of thread '" + name(thread) + "':");
        dialog.setElements(elements.toArray());
        dialog.setMultipleSelection(false);
        dialog.setSize(110, 14);
        if (dialog.open() != Window.OK) {
            return;
        }
        Object chosen = dialog.getFirstResult();
        if (chosen instanceof Candidate) {
            expose(shell, thread, (Candidate) chosen);
        } else {
            askExpression(shell, thread, frame,
                    "Expression returning the connection (as typed in the Debug Shell), a Hibernate Session "
                            + "or an EntityManager:");
        }
    }

    private void askExpression(Shell shell, IJavaThread thread, IJavaStackFrame selectedFrame, String message) {
        InputDialog input = new InputDialog(shell, TITLE, message, "", s -> s.isBlank() ? "" : null);
        if (input.open() != Window.OK) {
            return;
        }
        String expression = input.getValue().trim();
        background("TxRay: evaluating " + expression, () -> {
            IJavaStackFrame frame = selectedFrame != null ? selectedFrame
                    : (IJavaStackFrame) thread.getTopStackFrame();
            IJavaValue value = evaluate(frame, expression);
            List<Candidate> candidates = new ConnectionFinder(thread).fromValue(value,
                    "expression '" + expression + "'");
            ui(() -> {
                if (candidates.isEmpty()) {
                    MessageDialog.openInformation(shell, TITLE, "'" + expression
                            + "' does not lead to a JDBC connection (value: " + describe(value) + ").");
                } else {
                    choose(shell, thread, frame, candidates);
                }
            });
        });
    }

    private static IJavaValue evaluate(IJavaStackFrame frame, String expression) throws Exception {
        IJavaProject project = JavaDebugUtils.resolveJavaProject(frame);
        if (project == null) {
            throw new IllegalStateException("No Java project found for frame " + frame.getDeclaringTypeName()
                    + ": select a frame of a class of your workspace.");
        }
        IAstEvaluationEngine engine = EvaluationManager.newAstEvaluationEngine(project,
                (IJavaDebugTarget) frame.getDebugTarget());
        try {
            AtomicReference<IEvaluationResult> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            engine.evaluate(expression, frame, r -> {
                result.set(r);
                done.countDown();
            }, DebugEvent.EVALUATION_IMPLICIT, false);
            if (!done.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Evaluation timed out");
            }
            IEvaluationResult r = result.get();
            if (r.hasErrors()) {
                if (r.getException() != null) {
                    throw r.getException();
                }
                throw new IllegalStateException(String.join("\n", r.getErrorMessages()));
            }
            return r.getValue();
        } finally {
            engine.dispose();
        }
    }

    private static String describe(IJavaValue value) {
        try {
            return value == null ? "null" : value.getReferenceTypeName();
        } catch (DebugException e) {
            return "?";
        }
    }

    // ---- exposure ------------------------------------------------------------------------

    private void expose(Shell shell, IJavaThread thread, Candidate candidate) {
        background("TxRay: opening a SQL console on the connection", monitor -> {
            Activator plugin = Activator.getDefault();
            Binding binding = plugin.getBindings().add(thread, candidate.getConnection(), candidate.getOrigin());
            try {
                SqlExecutor.describe(binding);
            } catch (SQLException e) {
                plugin.getBindings().remove(binding);
                throw e;
            }
            plugin.getConsoles().open(binding, monitor);
        });
    }

    // ---- plumbing ------------------------------------------------------------------------

    private interface Work {
        void run() throws Exception;
    }

    private interface MonitoredWork {
        void run(IProgressMonitor monitor) throws Exception;
    }

    private static void background(String name, Work work) {
        background(name, monitor -> work.run());
    }

    private static void background(String name, MonitoredWork work) {
        Job job = Job.create(name, monitor -> {
            try {
                work.run(monitor);
                return Status.OK_STATUS;
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                Activator.getDefault().getLog().log(new Status(IStatus.WARNING, Activator.PLUGIN_ID, message, e));
                ui(() -> MessageDialog.openError(Display.getDefault().getActiveShell(), TITLE, message));
                return Status.OK_STATUS; // already reported
            }
        });
        job.setUser(true);
        job.schedule();
    }

    private static void ui(Runnable r) {
        Display.getDefault().asyncExec(r);
    }

    private static String name(IJavaThread thread) {
        try {
            return thread.getName();
        } catch (DebugException e) {
            return "?";
        }
    }

}
