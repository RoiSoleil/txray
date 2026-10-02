package org.eclipse.txray.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.txray.jdbc.backend.ColumnMeta;

/** Fully materialized, scrollable, read-only result set. */
final class ResultSetHandler extends JdbcHandler {

    private final Object statement;
    private final List<ColumnMeta> columns;
    private final List<String[]> rows;
    private int cursor = -1; // 0-based, -1 = before first, rows.size() = after last
    private boolean wasNull;
    private boolean closed;
    private int fetchSize;

    ResultSetHandler(Object statement, List<ColumnMeta> columns, List<String[]> rows) {
        this.statement = statement;
        this.columns = columns;
        this.rows = rows;
    }

    static ResultSet empty() {
        return new ResultSetHandler(null, Collections.emptyList(), Collections.emptyList()).proxy(ResultSet.class);
    }

    /** Routes the {@code getXxx(String label)} overloads to their {@code getXxx(int index)} twin. */
    @Override
    public Object invoke(Object p, Method m, Object[] args) throws Throwable {
        Class<?>[] types = m.getParameterTypes();
        if (types.length > 0 && types[0] == String.class && m.getName().startsWith("get")
                && m.getDeclaringClass() == ResultSet.class) {
            Class<?>[] indexed = types.clone();
            indexed[0] = int.class;
            Method twin;
            try {
                twin = ResultSet.class.getMethod(m.getName(), indexed);
            } catch (NoSuchMethodException e) {
                twin = null;
            }
            if (twin != null) {
                Object[] indexedArgs = args.clone();
                indexedArgs[0] = findColumn((String) args[0]);
                return super.invoke(p, twin, indexedArgs);
            }
        }
        return super.invoke(p, m, args);
    }

    private String raw(int column) throws SQLException {
        if (closed) {
            throw new SQLException("ResultSet is closed", "HY010");
        }
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("No current row", "24000");
        }
        if (column < 1 || column > columns.size()) {
            throw new SQLException("Invalid column index " + column, "07009");
        }
        String v = rows.get(cursor)[column - 1];
        wasNull = v == null;
        return v;
    }

    private int type(int column) {
        return columns.get(column - 1).type;
    }

    // ---- navigation ----------------------------------------------------------------------

    public boolean next() {
        if (cursor < rows.size()) {
            cursor++;
        }
        return cursor < rows.size();
    }

    public boolean previous() {
        if (cursor >= 0) {
            cursor--;
        }
        return cursor >= 0;
    }

    public boolean first() {
        cursor = 0;
        return !rows.isEmpty();
    }

    public boolean last() {
        cursor = rows.size() - 1;
        return !rows.isEmpty();
    }

    public void beforeFirst() {
        cursor = -1;
    }

    public void afterLast() {
        cursor = rows.size();
    }

    public boolean absolute(int row) {
        int target = row > 0 ? row - 1 : rows.size() + row;
        cursor = Math.max(-1, Math.min(rows.size(), target));
        return cursor >= 0 && cursor < rows.size();
    }

    public boolean relative(int delta) {
        return absolute(cursor + 1 + delta);
    }

    public int getRow() {
        return cursor >= 0 && cursor < rows.size() ? cursor + 1 : 0;
    }

    public boolean isBeforeFirst() {
        return cursor < 0 && !rows.isEmpty();
    }

    public boolean isAfterLast() {
        return cursor >= rows.size() && !rows.isEmpty();
    }

    public boolean isFirst() {
        return cursor == 0 && !rows.isEmpty();
    }

    public boolean isLast() {
        return cursor == rows.size() - 1 && !rows.isEmpty();
    }

    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean wasNull() {
        return wasNull;
    }

    public int findColumn(String label) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            if (label.equalsIgnoreCase(columns.get(i).label)) {
                return i + 1;
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            if (label.equalsIgnoreCase(columns.get(i).name)) {
                return i + 1;
            }
        }
        throw new SQLException("Unknown column " + label, "42S22");
    }

    public ResultSetMetaData getMetaData() {
        return new ResultSetMetaDataHandler(columns).proxy(ResultSetMetaData.class);
    }

    public Statement getStatement() {
        return (Statement) statement;
    }

    public int getType() {
        return ResultSet.TYPE_SCROLL_INSENSITIVE;
    }

    public int getConcurrency() {
        return ResultSet.CONCUR_READ_ONLY;
    }

    public int getHoldability() {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    public int getFetchDirection() {
        return ResultSet.FETCH_FORWARD;
    }

    public void setFetchDirection(int direction) {
        // always scrollable
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int rows) {
        fetchSize = rows;
    }

    public SQLWarning getWarnings() {
        return null;
    }

    public void clearWarnings() {
        // no warnings
    }

    // ---- getters -------------------------------------------------------------------------

    public String getString(int c) throws SQLException {
        return raw(c); // binary columns are returned as hex text
    }

    public String getNString(int c) throws SQLException {
        return getString(c);
    }

    public Object getObject(int c) throws SQLException {
        String v = raw(c);
        if (v == null) {
            return null;
        }
        try {
            switch (type(c)) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
                return new BigDecimal(v).intValueExact();
            case Types.BIGINT:
                return new BigDecimal(v).longValueExact();
            case Types.NUMERIC:
            case Types.DECIMAL:
                return new BigDecimal(v);
            case Types.REAL:
                return Float.valueOf(v);
            case Types.FLOAT:
            case Types.DOUBLE:
                return Double.valueOf(v);
            case Types.BIT:
            case Types.BOOLEAN:
                return Values.parseBoolean(v);
            case Types.DATE:
                return Values.parseDate(v);
            case Types.TIME:
                return Values.parseTime(v);
            case Types.TIMESTAMP:
                return Values.parseTimestamp(v);
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return Values.fromHex(v);
            default:
                return v;
            }
        } catch (RuntimeException e) {
            return v; // unexpected text from the driver: show it as is
        }
    }

    public Object getObject(int c, Map<String, Class<?>> map) throws SQLException {
        return getObject(c);
    }

    public <T> T getObject(int c, Class<T> type) throws SQLException {
        Object v;
        if (type == String.class) {
            v = getString(c);
        } else if (type == Integer.class) {
            v = wrap(getInt(c));
        } else if (type == Long.class) {
            v = wrap(getLong(c));
        } else if (type == Short.class) {
            v = wrap(getShort(c));
        } else if (type == Byte.class) {
            v = wrap(getByte(c));
        } else if (type == Double.class) {
            v = wrap(getDouble(c));
        } else if (type == Float.class) {
            v = wrap(getFloat(c));
        } else if (type == Boolean.class) {
            v = wrap(getBoolean(c));
        } else if (type == BigDecimal.class) {
            v = getBigDecimal(c);
        } else if (type == Timestamp.class) {
            v = getTimestamp(c);
        } else if (type == Date.class) {
            v = getDate(c);
        } else if (type == Time.class) {
            v = getTime(c);
        } else if (type == LocalDateTime.class) {
            Timestamp t = getTimestamp(c);
            v = t == null ? null : t.toLocalDateTime();
        } else if (type == LocalDate.class) {
            Date d = getDate(c);
            v = d == null ? null : d.toLocalDate();
        } else if (type == LocalTime.class) {
            Time t = getTime(c);
            v = t == null ? null : t.toLocalTime();
        } else if (type == byte[].class) {
            v = getBytes(c);
        } else {
            v = getObject(c);
        }
        if (v != null && !type.isInstance(v)) {
            throw new SQLException("Cannot convert column " + c + " to " + type.getName(), "22018");
        }
        return type.cast(v);
    }

    private Object wrap(Object primitive) {
        return wasNull ? null : primitive;
    }

    private BigDecimal number(int c) throws SQLException {
        String v = raw(c);
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (type(c) == Types.BIT || type(c) == Types.BOOLEAN) {
            return Values.parseBoolean(s) ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new SQLException("Not a number: " + v, "22018");
        }
    }

    public BigDecimal getBigDecimal(int c) throws SQLException {
        return number(c);
    }

    public BigDecimal getBigDecimal(int c, int scale) throws SQLException {
        BigDecimal n = number(c);
        return n == null ? null : n.setScale(scale, RoundingMode.HALF_UP);
    }

    public int getInt(int c) throws SQLException {
        BigDecimal n = number(c);
        return n == null ? 0 : n.intValue();
    }

    public long getLong(int c) throws SQLException {
        BigDecimal n = number(c);
        return n == null ? 0 : n.longValue();
    }

    public short getShort(int c) throws SQLException {
        BigDecimal n = number(c);
        return n == null ? 0 : n.shortValue();
    }

    public byte getByte(int c) throws SQLException {
        BigDecimal n = number(c);
        return n == null ? 0 : n.byteValue();
    }

    public double getDouble(int c) throws SQLException {
        BigDecimal n = number(c);
        return n == null ? 0 : n.doubleValue();
    }

    public float getFloat(int c) throws SQLException {
        BigDecimal n = number(c);
        return n == null ? 0 : n.floatValue();
    }

    public boolean getBoolean(int c) throws SQLException {
        String v = raw(c);
        return v != null && Values.parseBoolean(v);
    }

    public Timestamp getTimestamp(int c) throws SQLException {
        String v = raw(c);
        return v == null ? null : Values.parseTimestamp(v);
    }

    public Timestamp getTimestamp(int c, Calendar cal) throws SQLException {
        return getTimestamp(c);
    }

    public Date getDate(int c) throws SQLException {
        String v = raw(c);
        return v == null ? null : Values.parseDate(v);
    }

    public Date getDate(int c, Calendar cal) throws SQLException {
        return getDate(c);
    }

    public Time getTime(int c) throws SQLException {
        String v = raw(c);
        return v == null ? null : Values.parseTime(v);
    }

    public Time getTime(int c, Calendar cal) throws SQLException {
        return getTime(c);
    }

    public byte[] getBytes(int c) throws SQLException {
        String v = raw(c);
        if (v == null) {
            return null;
        }
        return ColumnMeta.isBinary(type(c)) ? Values.fromHex(v) : v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public InputStream getBinaryStream(int c) throws SQLException {
        byte[] b = getBytes(c);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    public Reader getCharacterStream(int c) throws SQLException {
        String v = getString(c);
        return v == null ? null : new StringReader(v);
    }

    public Reader getNCharacterStream(int c) throws SQLException {
        return getCharacterStream(c);
    }
}
