package org.eclipse.txray.dbeaver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.txray.Activator;
import org.eclipse.txray.core.Binding;
import org.eclipse.txray.jdbc.TxRayDriver;
import org.eclipse.txray.jdbc.backend.ServerInfo;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPPlatform;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDataSourceProviderDescriptor;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverConfigurationType;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLEditorHandlerOpenEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLNavigatorContext;

/**
 * DBeaver connections on the exposed connections: one temporary DBeaver connection per exposed
 * connection, removed with it, and SQL consoles opened on them.
 */
public final class SqlConsoles {

    private static final String PROVIDER_ID = "txray";
    private static final String DRIVER_ID = "txray";

    private final Map<String, DBPDataSourceContainer> containers = new ConcurrentHashMap<>();

    /** Connects DBeaver to the exposed connection (if needed) and opens a SQL console on it. */
    public void open(Binding binding, IProgressMonitor monitor) throws DBException {
        DBPDataSourceContainer container = containers.get(binding.getId());
        if (container == null) {
            container = create(binding);
            containers.put(binding.getId(), container);
        }
        if (!container.isConnected()) {
            container.connect(new DefaultProgressMonitor(monitor), true, true);
        }
        DBPDataSourceContainer ds = container;
        String header = header(binding);
        Display.getDefault().asyncExec(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null) {
                SQLEditorHandlerOpenEditor.openSQLConsole(window, new SQLNavigatorContext(ds), ds.getName(), header);
            }
        });
    }

    private static DBPDataSourceContainer create(Binding binding) throws DBException {
        DBPPlatform platform = DBWorkbench.getPlatform();
        DBPDataSourceProviderDescriptor provider = platform.getDataSourceProviderRegistry()
                .getDataSourceProvider(PROVIDER_ID);
        DBPDriver driver = provider == null ? null : provider.getDriver(DRIVER_ID);
        if (driver == null) {
            throw new DBException("The TxRay driver is not registered in DBeaver");
        }
        DBPProject project = platform.getWorkspace().getActiveProject();
        if (project == null) {
            throw new DBException("No active DBeaver project");
        }
        DBPDataSourceRegistry registry = project.getDataSourceRegistry();
        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration();
        configuration.setConfigurationType(DBPDriverConfigurationType.URL);
        configuration.setUrl(TxRayDriver.url(binding.getId()));
        DBPDataSourceContainer container = registry.createDataSource(driver, configuration);
        container.setName("TxRay #" + binding.getId() + " – " + binding.getThreadName());
        container.setTemporary(true);
        container.setSavePassword(true);
        registry.addDataSource(container);
        return container;
    }

    private static String header(Binding binding) {
        ServerInfo info = binding.getInfo();
        StringBuilder sb = new StringBuilder();
        sb.append("-- TxRay: connection of thread '").append(binding.getThreadName()).append("'\n");
        if (info != null) {
            sb.append("-- ").append(info.productName).append(' ').append(info.productVersion);
            if (info.userName != null) {
                sb.append(", user ").append(info.userName);
            }
            sb.append('\n');
            if (info.url != null) {
                sb.append("-- ").append(info.url).append('\n');
            }
        }
        sb.append("-- Found in: ").append(binding.getOrigin()).append('\n');
        sb.append("-- The SQL runs in the application's transaction, each time the thread is suspended\n");
        sb.append("-- on a breakpoint or a step. Commit and rollback stay in the hands of the application.\n\n");
        return sb.toString();
    }

    /** The exposed connection is gone: remove its DBeaver connection. */
    public void closed(Binding binding) {
        DBPDataSourceContainer container = containers.remove(binding.getId());
        if (container == null) {
            return;
        }
        Job job = Job.create("TxRay: closing " + container.getName(), monitor -> {
            try {
                if (container.isConnected()) {
                    container.disconnect(new DefaultProgressMonitor(monitor));
                }
            } catch (DBException e) {
                Platform.getLog(SqlConsoles.class).log(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                        "TxRay: cannot disconnect " + container.getName(), e));
            }
            container.getRegistry().removeDataSource(container);
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }
}
