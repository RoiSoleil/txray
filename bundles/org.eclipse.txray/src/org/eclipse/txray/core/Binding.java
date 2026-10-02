package org.eclipse.txray.core;

import org.eclipse.debug.core.DebugException;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.txray.jdbc.backend.ServerInfo;

/**
 * A {@code java.sql.Connection} of the debugged VM exposed to JDBC clients, together with the
 * thread in which TxRay runs the SQL. The connection object is protected from garbage collection
 * while it is exposed; the binding survives resumes and is usable each time the thread is
 * suspended again on a breakpoint or a step.
 */
public final class Binding {

    private final String id;
    private final IJavaThread thread;
    private final IJavaObject connection;
    private final String origin;
    private volatile ServerInfo info;

    Binding(String id, IJavaThread thread, IJavaObject connection, String origin) {
        this.id = id;
        this.thread = thread;
        this.connection = connection;
        this.origin = origin;
    }

    public String getId() {
        return id;
    }

    public IJavaThread getThread() {
        return thread;
    }

    public IJavaObject getConnection() {
        return connection;
    }

    /** Where the connection was found (thread local, local variable, expression...). */
    public String getOrigin() {
        return origin;
    }

    public ServerInfo getInfo() {
        return info;
    }

    void setInfo(ServerInfo info) {
        info.bindingId = id;
        this.info = info;
    }

    public String getThreadName() {
        try {
            return thread.getName();
        } catch (DebugException e) {
            return "?";
        }
    }

    void dispose() {
        try {
            connection.enableCollection();
        } catch (DebugException e) {
            // the VM is gone
        }
    }
}
