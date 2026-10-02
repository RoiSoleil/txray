package org.eclipse.txray.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jdt.debug.core.IJavaMethodBreakpoint;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.eclipse.finder.widgets.SWTBotEditor;
import org.eclipse.swtbot.eclipse.finder.widgets.SWTBotView;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.junit5.SWTBotJunit5Extension;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotTreeItem;
import org.eclipse.txray.Activator;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetModel;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End to end, as a user: a Java application with an H2 transaction in progress is debugged, the
 * thread is suspended on a breakpoint, "TxRay > Open SQL Console on Thread Connection" is chosen in
 * the Debug view, SQL is typed and run in the DBeaver console, and the result grid shows the
 * uncommitted row. An UPDATE run from the console becomes part of the application's transaction,
 * which the application rolls back afterwards.
 */
@ExtendWith(SWTBotJunit5Extension.class)
class TxRayEndToEndTest {

    private static final String DEBUG_VIEW = "org.eclipse.debug.ui.DebugView";
    private static final String RUN_STATEMENT = "org.jkiss.dbeaver.ui.editors.sql.run.statement";

    private final SWTWorkbenchBot bot = new SWTWorkbenchBot();
    private final List<String> output = new ArrayList<>();
    private Process debuggee;
    private ILaunch launch;
    private IJavaMethodBreakpoint breakpoint;

    @BeforeEach
    void setUp() {
        // no perspective switch prompt when the breakpoint is hit
        DebugUITools.getPreferenceStore().setValue("org.eclipse.debug.ui.switch_perspective_on_suspend",
                MessageDialogWithToggle.NEVER);
        DebugUITools.getPreferenceStore().setValue("org.eclipse.debug.ui.switch_to_perspective",
                MessageDialogWithToggle.NEVER);
        try {
            bot.viewByTitle("Welcome").close();
        } catch (RuntimeException e) {
            // no welcome page
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (launch != null && launch.canTerminate()) {
            launch.terminate();
        }
        if (debuggee != null) {
            debuggee.destroyForcibly();
        }
        if (breakpoint != null) {
            breakpoint.delete();
        }
    }

    @Test
    void queriesTheTransactionOfASuspendedThreadFromADBeaverConsole() throws Exception {
        IJavaThread thread = startAndSuspendTheApplication();

        // the user: Debug view > thread > TxRay > Open SQL Console on Thread Connection
        UIThreadRunnable.syncExec(() -> {
            try {
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(DEBUG_VIEW);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        SWTBotView debugView = bot.viewById(DEBUG_VIEW);
        debugView.show();
        SWTBotTreeItem threadItem = waitFor("the suspended thread in the Debug view",
                () -> findItem(debugView.bot().tree().getAllItems(), "Thread [main]"));
        threadItem.select();
        threadItem.contextMenu("TxRay").menu("Open SQL Console on Thread Connection").click();

        // a DBeaver SQL console opens on the connection of the thread
        SWTBotEditor console = waitFor("the TxRay SQL console", () -> {
            for (SWTBotEditor e : bot.editors()) {
                if (e.getTitle().contains("TxRay #")) {
                    return e;
                }
            }
            return null;
        });
        console.show();
        SQLEditor editor = (SQLEditor) console.getReference().getEditor(false);
        assertTrue(text(editor).contains("-- TxRay: connection of thread 'main'"), text(editor));
        assertTrue(text(editor).contains("H2"), text(editor));

        // the uncommitted row of the application is visible
        run(editor, "SELECT ID, LABEL, AMOUNT FROM ORDERS");
        List<List<String>> rows = waitForRows(editor, r -> !r.isEmpty() && r.get(0).contains("uncommitted"));
        assertEquals(List.of(List.of("1", "uncommitted", "12.50")), rows);

        // an UPDATE from the console becomes part of the application's transaction
        run(editor, "UPDATE ORDERS SET LABEL = 'from txray' WHERE ID = 1");
        run(editor, "SELECT ID, LABEL, AMOUNT FROM ORDERS");
        waitForRows(editor, r -> !r.isEmpty() && r.get(0).contains("from txray"));

        // the application goes on: it sees the update in its transaction, then rolls it back
        thread.resume();
        assertTrue(debuggee.waitFor(60, TimeUnit.SECONDS), "the application did not finish: " + output());
        assertTrue(output().contains("CHECKPOINT true"), output());
        assertTrue(output().contains("LABEL=from txray"), output());
        assertTrue(output().contains("AFTER_ROLLBACK=0"), output());

        // the end of the debug session removes the DBeaver connection
        waitFor("the removal of the TxRay connection", () -> txrayDataSources().isEmpty() ? Boolean.TRUE : null);
        assertTrue(Activator.getDefault().getBindings().all().isEmpty());
    }

    // ---- the debugged application --------------------------------------------------------

    private IJavaThread startAndSuspendTheApplication() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("txray.e2e.classpath");
        assertNotNull(classpath, "txray.e2e.classpath is not set");
        debuggee = new ProcessBuilder(java,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + port,
                "-cp", classpath, Debuggee.class.getName()).redirectErrorStream(true).start();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(debuggee.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (output) {
                        output.add(line);
                    }
                }
            } catch (IOException e) {
                // process gone
            }
        }, "debuggee output");
        reader.setDaemon(true);
        reader.start();
        waitFor("the application to be ready", () -> output().contains("READY") ? Boolean.TRUE : null);

        breakpoint = JDIDebugModel.createMethodBreakpoint(ResourcesPlugin.getWorkspace().getRoot(),
                Debuggee.class.getName(), "checkpoint", "(Ljava/sql/Connection;)V", true, false, false, -1, -1, -1,
                0, true, null);

        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = manager
                .getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_REMOTE_JAVA_APPLICATION);
        ILaunchConfigurationWorkingCopy config = type.newInstance(null, "txray-e2e");
        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_CONNECTOR,
                IJavaLaunchConfigurationConstants.ID_SOCKET_ATTACH_VM_CONNECTOR);
        Map<String, String> connect = new HashMap<>();
        connect.put("hostname", "127.0.0.1");
        connect.put("port", Integer.toString(port));
        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_CONNECT_MAP, connect);
        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_ALLOW_TERMINATE, true);
        launch = config.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
        waitFor("the debugger to attach", () -> launch.getDebugTarget() != null ? Boolean.TRUE : null);

        // let the application reach the breakpoint
        Writer in = new OutputStreamWriter(debuggee.getOutputStream(), StandardCharsets.UTF_8);
        in.write("go\n");
        in.flush();

        return waitFor("the thread to stop on the breakpoint", () -> {
            try {
                for (IThread t : launch.getDebugTarget().getThreads()) {
                    if (t.isSuspended() && t.getBreakpoints().length > 0 && "main".equals(t.getName())) {
                        return (IJavaThread) t;
                    }
                }
            } catch (Exception e) {
                // not yet
            }
            return null;
        });
    }

    private String output() {
        synchronized (output) {
            return String.join("\n", output);
        }
    }

    // ---- the DBeaver console -------------------------------------------------------------

    private static String text(SQLEditor editor) {
        return UIThreadRunnable.syncExec(() -> document(editor).get());
    }

    private static IDocument document(ITextEditor editor) {
        return editor.getDocumentProvider().getDocument(editor.getEditorInput());
    }

    /** Types the statement in the console and runs it like Ctrl+Enter. */
    private void run(SQLEditor editor, String sql) {
        // asynchronous, as a key press: a dialog opened by the execution must not block the test
        UIThreadRunnable.asyncExec(() -> {
            editor.getSite().getPage().activate(editor);
            IDocument doc = document(editor);
            doc.set(sql);
            editor.selectAndReveal(0, 0);
            IHandlerService handlers = editor.getSite().getService(IHandlerService.class);
            try {
                handlers.executeCommand(RUN_STATEMENT, null);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot run " + sql, e);
            }
        });
    }

    private interface RowsCondition {
        boolean accept(List<List<String>> rows);
    }

    /** The rows of the result grid of the console, as text. */
    private List<List<String>> waitForRows(SQLEditor editor, RowsCondition condition) {
        return waitFor("the result grid", () -> {
            List<List<String>> rows = UIThreadRunnable.syncExec(() -> {
                List<List<String>> result = new ArrayList<>();
                if (editor.getResultSetController() == null) {
                    return result;
                }
                ResultSetModel model = editor.getResultSetController().getModel();
                for (ResultSetRow row : model.getAllRows()) {
                    List<String> cells = new ArrayList<>();
                    for (Object v : row.getValues()) {
                        cells.add(String.valueOf(v));
                    }
                    result.add(cells);
                }
                return result;
            });
            return condition.accept(rows) ? rows : null;
        });
    }

    private static List<DBPDataSourceContainer> txrayDataSources() {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        for (DBPProject p : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            for (DBPDataSourceContainer ds : p.getDataSourceRegistry().getDataSources()) {
                if (ds.getName().startsWith("TxRay #")) {
                    result.add(ds);
                }
            }
        }
        return result;
    }

    // ---- plumbing ------------------------------------------------------------------------

    private static SWTBotTreeItem findItem(SWTBotTreeItem[] items, String prefix) {
        for (SWTBotTreeItem item : items) {
            if (item.getText().startsWith(prefix)) {
                return item;
            }
            if (!item.isExpanded()) {
                item.expand();
            }
            SWTBotTreeItem found = findItem(item.getItems(), prefix);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The open windows and their texts, to understand a failure. */
    private static String windows() {
        return UIThreadRunnable.syncExec(() -> {
            StringBuilder sb = new StringBuilder();
            for (org.eclipse.swt.widgets.Shell shell : org.eclipse.swt.widgets.Display.getDefault().getShells()) {
                if (!shell.isVisible()) {
                    continue;
                }
                sb.append("- ").append(shell.getText()).append('\n');
                if (shell.getParent() != null) {
                    texts(shell, sb);
                }
            }
            return sb.toString();
        });
    }

    private static void texts(org.eclipse.swt.widgets.Control control, StringBuilder sb) {
        String text = null;
        if (control instanceof org.eclipse.swt.widgets.Label l) {
            text = l.getText();
        } else if (control instanceof org.eclipse.swt.widgets.Text t) {
            text = t.getText();
        } else if (control instanceof org.eclipse.swt.widgets.Button b) {
            text = "[" + b.getText() + "]";
        } else if (control instanceof org.eclipse.swt.widgets.Table t) {
            for (org.eclipse.swt.widgets.TableItem item : t.getItems()) {
                sb.append("    * ").append(item.getText()).append('\n');
            }
        }
        if (text != null && !text.isBlank()) {
            sb.append("    ").append(text).append('\n');
        }
        if (control instanceof org.eclipse.swt.widgets.Composite c) {
            for (org.eclipse.swt.widgets.Control child : c.getChildren()) {
                texts(child, sb);
            }
        }
    }

    private <T> T waitFor(String what, Supplier<T> probe) {
        Object[] result = new Object[1];
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                try {
                    result[0] = probe.get();
                } catch (RuntimeException e) {
                    result[0] = null;
                }
                return result[0] != null;
            }

            @Override
            public String getFailureMessage() {
                return "Timed out waiting for " + what + "\nApplication output:\n" + output() + "\nWindows:\n"
                        + windows();
            }
        }, 60_000);
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }
}
