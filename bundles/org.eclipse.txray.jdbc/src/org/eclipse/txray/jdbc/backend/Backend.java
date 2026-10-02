package org.eclipse.txray.jdbc.backend;

import java.sql.SQLException;

/**
 * The side that runs the SQL: the TxRay Eclipse plugin, which executes it through the debugger in
 * a suspended thread of the debugged application. It lives in the same JVM as DBeaver: the driver
 * calls it directly.
 */
public interface Backend {

    /**
     * Description of an exposed connection.
     *
     * @param bindingId id of the exposed connection, or null for the most recently exposed one
     */
    ServerInfo describe(String bindingId) throws SQLException;

    /** Runs the request on the exposed connection, in the transaction of the application. */
    ExecuteResult execute(String bindingId, ExecuteRequest request) throws SQLException;
}
