package org.eclipse.txray.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import org.eclipse.txray.jdbc.backend.Backend;

/**
 * JDBC driver used by the DBeaver of the same Eclipse.
 * <p>
 * URL: {@code jdbc:txray:<bindingId>}; {@code jdbc:txray:} alone targets the most recently exposed
 * connection. The SQL is executed by the Eclipse debugger, through JDI, on the
 * {@code java.sql.Connection} of a suspended thread of the debugged application, hence inside its
 * current transaction.
 */
public final class TxRayDriver implements Driver {

    public static final String URL_PREFIX = "jdbc:txray:";
    public static final int MAJOR = 1;
    public static final int MINOR = 0;

    private static volatile Backend backend;

    /** Installed by the TxRay plugin when it starts, removed when it stops. */
    public static void setBackend(Backend b) {
        backend = b;
    }

    public static String url(String bindingId) {
        return URL_PREFIX + (bindingId == null ? "" : bindingId);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        Backend b = backend;
        if (b == null) {
            throw new SQLException("TxRay is not running in this Eclipse", "08001");
        }
        String id = url.substring(URL_PREFIX.length()).trim();
        return TxRayConnection.open(b, url, id.isEmpty() ? null : id);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return MINOR;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
