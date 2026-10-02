package org.eclipse.txray.core;

import java.sql.SQLException;
import java.sql.Types;
import java.util.HexFormat;

import org.eclipse.debug.core.DebugException;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.txray.jdbc.backend.ColumnMeta;
import org.eclipse.txray.jdbc.backend.ExecuteRequest;
import org.eclipse.txray.jdbc.backend.ExecuteResult;
import org.eclipse.txray.jdbc.backend.Param;
import org.eclipse.txray.jdbc.backend.ServerInfo;

/**
 * Runs SQL on the exposed connection, inside the debugged VM, by invoking JDBC methods in the
 * suspended thread: {@code createStatement / prepareStatement}, {@code execute}, then reads the
 * result set cell by cell. Being the application's own connection, the statement sees the
 * application's uncommitted changes and takes part in its transaction.
 */
public final class SqlExecutor {

    private static final String STRING = "Ljava/lang/String;";

    private final int maxRowsLimit;
    private final int maxBinaryBytes;

    public SqlExecutor(int maxRowsLimit, int maxBinaryBytes) {
        this.maxRowsLimit = maxRowsLimit;
        this.maxBinaryBytes = maxBinaryBytes;
    }

    /** Reads the description of the connection (product, URL, user...). */
    public static ServerInfo describe(Binding binding) throws SQLException {
        Jdi jdi = new Jdi(binding.getThread());
        IJavaObject conn = binding.getConnection();
        ServerInfo info = new ServerInfo();
        info.threadName = binding.getThreadName();
        synchronized (binding) {
            try {
                IJavaObject md = jdi.callObject(conn, "getMetaData", "()Ljava/sql/DatabaseMetaData;");
                if (md != null) {
                    info.productName = optionalString(jdi, md, "getDatabaseProductName");
                    info.productVersion = optionalString(jdi, md, "getDatabaseProductVersion");
                    info.url = optionalString(jdi, md, "getURL");
                    info.userName = optionalString(jdi, md, "getUserName");
                }
                info.catalog = optionalString(jdi, conn, "getCatalog");
                info.schema = optionalString(jdi, conn, "getSchema");
                info.autoCommit = jdi.callBoolean(conn, "getAutoCommit", "()Z");
                info.transactionIsolation = jdi.callInt(conn, "getTransactionIsolation", "()I");
            } catch (DebugException e) {
                throw jdi.failure(e);
            }
        }
        binding.setInfo(info);
        return info;
    }

    private static String optionalString(Jdi jdi, IJavaObject o, String method) {
        try {
            return jdi.callString(o, method, "()" + STRING);
        } catch (DebugException e) {
            return null; // optional (old driver, unsupported operation...)
        }
    }

    public ExecuteResult execute(Binding binding, ExecuteRequest request) throws SQLException {
        Jdi jdi = new Jdi(binding.getThread());
        if (!binding.getThread().isSuspended()) {
            throw new SQLException("TxRay: thread '" + binding.getThreadName()
                    + "' is running. Suspend it on a breakpoint (or a step) to query its connection.", "08003", 0);
        }
        int limit = request.maxRows > 0 ? Math.min(request.maxRows, maxRowsLimit) : maxRowsLimit;
        long start = System.nanoTime();
        synchronized (binding) {
            IJavaObject stmt = null;
            IJavaObject rs = null;
            try {
                IJavaObject conn = binding.getConnection();
                boolean hasResultSet;
                if (request.params == null) {
                    stmt = jdi.callObject(conn, "createStatement", "()Ljava/sql/Statement;");
                    setMaxRows(jdi, stmt, limit);
                    hasResultSet = jdi.callBoolean(stmt, "execute", "(" + STRING + ")Z", jdi.value(request.sql));
                } else {
                    stmt = jdi.callObject(conn, "prepareStatement", "(" + STRING + ")Ljava/sql/PreparedStatement;",
                            jdi.value(request.sql));
                    setMaxRows(jdi, stmt, limit);
                    for (int i = 0; i < request.params.size(); i++) {
                        bind(jdi, stmt, i + 1, request.params.get(i));
                    }
                    hasResultSet = jdi.callBoolean(stmt, "execute", "()Z");
                }
                ExecuteResult result = new ExecuteResult();
                if (hasResultSet) {
                    rs = jdi.callObject(stmt, "getResultSet", "()Ljava/sql/ResultSet;");
                    result.kind = ExecuteResult.ROWS;
                    readResultSet(jdi, rs, limit, result);
                } else {
                    result.kind = ExecuteResult.UPDATE_COUNT;
                    result.updateCount = jdi.callInt(stmt, "getUpdateCount", "()I");
                }
                result.elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                return result;
            } catch (DebugException e) {
                throw jdi.failure(e);
            } finally {
                close(jdi, rs);
                close(jdi, stmt);
            }
        }
    }

    /** One row more than the limit, to know whether the result was truncated. */
    private static void setMaxRows(Jdi jdi, IJavaObject stmt, int limit) throws DebugException {
        int max = limit == Integer.MAX_VALUE ? 0 : limit + 1;
        jdi.call(stmt, "setMaxRows", "(I)V", jdi.value(max));
    }

    private static void close(Jdi jdi, IJavaObject o) {
        if (o == null) {
            return;
        }
        try {
            jdi.call(o, "close", "()V");
        } catch (DebugException e) {
            // best effort
        }
    }

    private static void bind(Jdi jdi, IJavaObject stmt, int index, Param p) throws DebugException, SQLException {
        IJavaValue i = jdi.value(index);
        switch (p.kind) {
        case Param.NULL:
            jdi.call(stmt, "setNull", "(II)V", i, jdi.value(p.sqlType == Types.NULL ? Types.VARCHAR : p.sqlType));
            return;
        case Param.STRING:
            jdi.call(stmt, "setString", "(I" + STRING + ")V", i, jdi.value(p.value));
            return;
        case Param.LONG:
            jdi.call(stmt, "setLong", "(IJ)V", i, jdi.value(Long.parseLong(p.value)));
            return;
        case Param.DOUBLE:
            jdi.call(stmt, "setDouble", "(ID)V", i, jdi.value(Double.parseDouble(p.value)));
            return;
        case Param.BOOLEAN:
            jdi.call(stmt, "setBoolean", "(IZ)V", i, jdi.value(Boolean.parseBoolean(p.value)));
            return;
        case Param.DECIMAL: {
            IJavaObject d = jdi.newInstance("java.math.BigDecimal", "(" + STRING + ")V", jdi.value(p.value));
            jdi.call(stmt, "setBigDecimal", "(ILjava/math/BigDecimal;)V", i, d);
            return;
        }
        case Param.DATE:
            bindTemporal(jdi, stmt, i, p.value, "java.sql.Date", "setDate");
            return;
        case Param.TIME:
            bindTemporal(jdi, stmt, i, p.value, "java.sql.Time", "setTime");
            return;
        case Param.TIMESTAMP:
            bindTemporal(jdi, stmt, i, p.value, "java.sql.Timestamp", "setTimestamp");
            return;
        default:
            throw new SQLException("TxRay: unknown parameter kind " + p.kind, "HY105");
        }
    }

    private static void bindTemporal(Jdi jdi, IJavaObject stmt, IJavaValue index, String text, String className,
            String setter) throws DebugException {
        String sig = "L" + className.replace('.', '/') + ";";
        IJavaValue v = jdi.callStatic(className, "valueOf", "(" + STRING + ")" + sig, jdi.value(text));
        if (v == null) {
            // class not loaded in the debugged VM: let the database convert the text
            jdi.call(stmt, "setString", "(I" + STRING + ")V", index, jdi.value(text));
        } else {
            jdi.call(stmt, setter, "(I" + sig + ")V", index, v);
        }
    }

    private void readResultSet(Jdi jdi, IJavaObject rs, int limit, ExecuteResult result) throws DebugException {
        IJavaObject md = jdi.callObject(rs, "getMetaData", "()Ljava/sql/ResultSetMetaData;");
        int count = jdi.callInt(md, "getColumnCount", "()I");
        int[] fetchTypes = new int[count];
        for (int c = 1; c <= count; c++) {
            IJavaValue ci = jdi.value(c);
            ColumnMeta m = new ColumnMeta();
            m.name = jdi.callString(md, "getColumnName", "(I)" + STRING, ci);
            m.label = optional(() -> jdi.callString(md, "getColumnLabel", "(I)" + STRING, ci), m.name);
            int jdbcType = jdi.callInt(md, "getColumnType", "(I)I", ci);
            m.type = ColumnMeta.transportType(jdbcType);
            m.typeName = optional(() -> jdi.callString(md, "getColumnTypeName", "(I)" + STRING, ci), null);
            m.precision = optional(() -> jdi.callInt(md, "getPrecision", "(I)I", ci), 0);
            m.scale = optional(() -> jdi.callInt(md, "getScale", "(I)I", ci), 0);
            m.nullable = optional(() -> jdi.callInt(md, "isNullable", "(I)I", ci), 2);
            m.displaySize = optional(() -> jdi.callInt(md, "getColumnDisplaySize", "(I)I", ci), 0);
            m.tableName = optional(() -> jdi.callString(md, "getTableName", "(I)" + STRING, ci), null);
            m.schemaName = optional(() -> jdi.callString(md, "getSchemaName", "(I)" + STRING, ci), null);
            fetchTypes[c - 1] = m.type;
            result.columns.add(m);
        }
        while (jdi.callBoolean(rs, "next", "()Z")) {
            if (result.rows.size() >= limit) {
                result.truncated = true;
                break;
            }
            String[] row = new String[count];
            for (int c = 1; c <= count; c++) {
                row[c - 1] = cell(jdi, rs, c, fetchTypes[c - 1]);
            }
            result.rows.add(row);
        }
    }

    private String cell(Jdi jdi, IJavaObject rs, int column, int type) throws DebugException {
        IJavaValue c = jdi.value(column);
        switch (type) {
        case Types.DATE:
            return temporal(jdi, jdi.call(rs, "getDate", "(I)Ljava/sql/Date;", c));
        case Types.TIME:
            return temporal(jdi, jdi.call(rs, "getTime", "(I)Ljava/sql/Time;", c));
        case Types.TIMESTAMP:
            return temporal(jdi, jdi.call(rs, "getTimestamp", "(I)Ljava/sql/Timestamp;", c));
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY: {
            IJavaValue v = jdi.call(rs, "getBytes", "(I)[B", c);
            return Jdi.isNull(v) ? null : HexFormat.of().formatHex(Jdi.bytes((IJavaArray) v, maxBinaryBytes));
        }
        default:
            return Jdi.string(jdi.call(rs, "getString", "(I)" + STRING, c));
        }
    }

    /** {@code java.sql.Date/Time/Timestamp.toString()}: the format the driver parses back. */
    private static String temporal(Jdi jdi, IJavaValue v) throws DebugException {
        if (Jdi.isNull(v)) {
            return null;
        }
        return jdi.callString((IJavaObject) v, "toString", "()" + STRING);
    }

    private interface Call<T> {
        T get() throws DebugException;
    }

    private static <T> T optional(Call<T> call, T fallback) {
        try {
            return call.get();
        } catch (DebugException e) {
            return fallback;
        }
    }
}
