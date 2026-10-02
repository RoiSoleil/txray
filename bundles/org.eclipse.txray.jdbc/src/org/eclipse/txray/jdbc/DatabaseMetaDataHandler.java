package org.eclipse.txray.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;

import org.eclipse.txray.jdbc.backend.ServerInfo;

/**
 * Database metadata. Product information comes from the real connection; catalog queries
 * (tables, columns, ...) return empty result sets: TxRay is meant for the SQL editor, not for the
 * navigator tree.
 */
final class DatabaseMetaDataHandler extends JdbcHandler {

    private final TxRayConnection connection;

    DatabaseMetaDataHandler(TxRayConnection connection) {
        this.connection = connection;
    }

    private ServerInfo info() {
        return connection.info();
    }

    public Connection getConnection() {
        return (Connection) connection.self();
    }

    public String getDatabaseProductName() {
        String p = info().productName;
        return p == null ? "TxRay" : p;
    }

    public String getDatabaseProductVersion() {
        String v = info().productVersion;
        return v == null ? "" : v;
    }

    public int getDatabaseMajorVersion() {
        return leadingNumber(getDatabaseProductVersion(), 0);
    }

    public int getDatabaseMinorVersion() {
        return leadingNumber(getDatabaseProductVersion(), 1);
    }

    private static int leadingNumber(String version, int index) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:\\.(\\d+))?").matcher(version);
        if (m.find() && m.group(index + 1) != null) {
            try {
                return Integer.parseInt(m.group(index + 1));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }

    public String getDriverName() {
        return "TxRay";
    }

    public String getDriverVersion() {
        return TxRayDriver.MAJOR + "." + TxRayDriver.MINOR;
    }

    public int getDriverMajorVersion() {
        return TxRayDriver.MAJOR;
    }

    public int getDriverMinorVersion() {
        return TxRayDriver.MINOR;
    }

    public int getJDBCMajorVersion() {
        return 4;
    }

    public int getJDBCMinorVersion() {
        return 2;
    }

    public String getURL() {
        return connection.url();
    }

    public String getUserName() {
        return info().userName;
    }

    public boolean isReadOnly() {
        return false;
    }

    public String getIdentifierQuoteString() {
        return "\"";
    }

    public String getSQLKeywords() {
        return "";
    }

    public String getNumericFunctions() {
        return "";
    }

    public String getStringFunctions() {
        return "";
    }

    public String getSystemFunctions() {
        return "";
    }

    public String getTimeDateFunctions() {
        return "";
    }

    public String getSearchStringEscape() {
        return "\\";
    }

    public String getExtraNameCharacters() {
        return "";
    }

    public String getSchemaTerm() {
        return "schema";
    }

    public String getProcedureTerm() {
        return "procedure";
    }

    public String getCatalogTerm() {
        return "catalog";
    }

    public String getCatalogSeparator() {
        return ".";
    }

    public boolean isCatalogAtStart() {
        return true;
    }

    public boolean supportsTransactions() {
        return false;
    }

    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    public boolean supportsResultSetType(int type) {
        return type != ResultSet.TYPE_SCROLL_SENSITIVE;
    }

    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return supportsResultSetType(type) && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    public int getResultSetHoldability() {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    public boolean supportsMixedCaseIdentifiers() {
        return false;
    }

    public boolean storesUpperCaseIdentifiers() {
        String p = getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
        return p.contains("oracle") || p.contains("db2") || p.contains("h2");
    }

    public boolean storesLowerCaseIdentifiers() {
        return getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres");
    }

    public boolean supportsMultipleResultSets() {
        return false;
    }

    public boolean supportsBatchUpdates() {
        return false;
    }

    public boolean supportsSavepoints() {
        return false;
    }

    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    public boolean supportsStoredProcedures() {
        return false;
    }

    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    public int getSQLStateType() {
        return java.sql.DatabaseMetaData.sqlStateSQL;
    }
}
