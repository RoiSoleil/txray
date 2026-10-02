package org.eclipse.txray.core;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.eclipse.txray.jdbc.backend.Backend;
import org.eclipse.txray.jdbc.backend.ExecuteRequest;
import org.eclipse.txray.jdbc.backend.ExecuteResult;
import org.eclipse.txray.jdbc.backend.ServerInfo;

/** What the TxRay JDBC driver calls: SQL executed through the debugger on an exposed connection. */
public final class DebuggerBackend implements Backend {

    private final Bindings bindings;
    private final Supplier<SqlExecutor> executor;

    public DebuggerBackend(Bindings bindings, Supplier<SqlExecutor> executor) {
        this.bindings = bindings;
        this.executor = executor;
    }

    private Binding binding(String id) throws SQLException {
        Binding b = bindings.get(id);
        if (b != null) {
            return b;
        }
        if (id == null) {
            throw new SQLException("TxRay: no connection is exposed. In the Debug view, right-click a thread "
                    + "suspended on a breakpoint > TxRay > Open SQL Console on Thread Connection.", "08001");
        }
        throw new SQLException("TxRay: connection #" + id + " is no longer exposed (thread ended, debug session "
                + "terminated or connection released). Expose it again from the Debug view.", "08003");
    }

    @Override
    public ServerInfo describe(String bindingId) throws SQLException {
        Binding b = binding(bindingId);
        ServerInfo info = b.getInfo();
        return info != null ? info : SqlExecutor.describe(b);
    }

    @Override
    public ExecuteResult execute(String bindingId, ExecuteRequest request) throws SQLException {
        return executor.get().execute(binding(bindingId), request);
    }
}
