package org.eclipse.txray.jdbc.backend;

import java.sql.Types;

/** Column description. {@link #type} is the transport type (see {@link #transportType(int)}). */
public final class ColumnMeta {

    public String name;
    public String label;
    public int type;
    public String typeName;
    public int precision;
    public int scale;
    public int nullable;
    public int displaySize;
    public String tableName;
    public String schemaName;

    /**
     * Maps a driver-reported JDBC type to the type TxRay knows how to carry faithfully.
     * Anything exotic travels as text (VARCHAR) while keeping its original type name.
     */
    public static int transportType(int jdbcType) {
        switch (jdbcType) {
        case Types.TINYINT:
        case Types.SMALLINT:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.NUMERIC:
        case Types.DECIMAL:
        case Types.REAL:
        case Types.FLOAT:
        case Types.DOUBLE:
        case Types.BIT:
        case Types.BOOLEAN:
        case Types.DATE:
        case Types.TIME:
        case Types.TIMESTAMP:
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.CHAR:
        case Types.NCHAR:
        case Types.NVARCHAR:
        case Types.LONGVARCHAR:
        case Types.LONGNVARCHAR:
            return jdbcType;
        // LOBs are materialized: present them as long values so that clients use getString/getBytes
        case Types.BLOB:
            return Types.LONGVARBINARY;
        case Types.CLOB:
            return Types.LONGVARCHAR;
        case Types.NCLOB:
            return Types.LONGNVARCHAR;
        default:
            return Types.VARCHAR;
        }
    }

    public static boolean isBinary(int type) {
        return type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY
                || type == Types.BLOB;
    }

}
