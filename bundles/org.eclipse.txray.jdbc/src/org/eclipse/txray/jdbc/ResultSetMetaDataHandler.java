package org.eclipse.txray.jdbc;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.eclipse.txray.jdbc.backend.ColumnMeta;

final class ResultSetMetaDataHandler extends JdbcHandler {

    private final List<ColumnMeta> columns;

    ResultSetMetaDataHandler(List<ColumnMeta> columns) {
        this.columns = columns;
    }

    private ColumnMeta col(int c) throws SQLException {
        if (c < 1 || c > columns.size()) {
            throw new SQLException("Invalid column index " + c, "07009");
        }
        return columns.get(c - 1);
    }

    public int getColumnCount() {
        return columns.size();
    }

    public String getColumnName(int c) throws SQLException {
        return col(c).name;
    }

    public String getColumnLabel(int c) throws SQLException {
        ColumnMeta m = col(c);
        return m.label != null ? m.label : m.name;
    }

    public int getColumnType(int c) throws SQLException {
        return col(c).type;
    }

    public String getColumnTypeName(int c) throws SQLException {
        return col(c).typeName;
    }

    public String getColumnClassName(int c) throws SQLException {
        switch (col(c).type) {
        case Types.TINYINT:
        case Types.SMALLINT:
        case Types.INTEGER:
            return Integer.class.getName();
        case Types.BIGINT:
            return Long.class.getName();
        case Types.NUMERIC:
        case Types.DECIMAL:
            return BigDecimal.class.getName();
        case Types.REAL:
            return Float.class.getName();
        case Types.FLOAT:
        case Types.DOUBLE:
            return Double.class.getName();
        case Types.BIT:
        case Types.BOOLEAN:
            return Boolean.class.getName();
        case Types.DATE:
            return java.sql.Date.class.getName();
        case Types.TIME:
            return java.sql.Time.class.getName();
        case Types.TIMESTAMP:
            return Timestamp.class.getName();
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
            return byte[].class.getName();
        default:
            return String.class.getName();
        }
    }

    public int getPrecision(int c) throws SQLException {
        return col(c).precision;
    }

    public int getScale(int c) throws SQLException {
        return col(c).scale;
    }

    public int isNullable(int c) throws SQLException {
        return col(c).nullable;
    }

    public int getColumnDisplaySize(int c) throws SQLException {
        return col(c).displaySize;
    }

    public String getTableName(int c) throws SQLException {
        String t = col(c).tableName;
        return t == null ? "" : t;
    }

    public String getSchemaName(int c) throws SQLException {
        String s = col(c).schemaName;
        return s == null ? "" : s;
    }

    public String getCatalogName(int c) {
        return "";
    }

    public boolean isAutoIncrement(int c) {
        return false;
    }

    public boolean isCaseSensitive(int c) {
        return true;
    }

    public boolean isSearchable(int c) {
        return true;
    }

    public boolean isCurrency(int c) {
        return false;
    }

    public boolean isSigned(int c) throws SQLException {
        switch (col(c).type) {
        case Types.TINYINT:
        case Types.SMALLINT:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.NUMERIC:
        case Types.DECIMAL:
        case Types.REAL:
        case Types.FLOAT:
        case Types.DOUBLE:
            return true;
        default:
            return false;
        }
    }

    public boolean isReadOnly(int c) {
        return true;
    }

    public boolean isWritable(int c) {
        return false;
    }

    public boolean isDefinitelyWritable(int c) {
        return false;
    }
}
