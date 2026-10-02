package org.eclipse.txray.jdbc.backend;

/**
 * A prepared statement parameter. Values travel as strings and are rebuilt in the debugged VM
 * with the matching {@code PreparedStatement.setXxx} call.
 */
public final class Param {

    public static final byte NULL = 0;
    public static final byte STRING = 1;
    public static final byte LONG = 2;
    public static final byte DOUBLE = 3;
    public static final byte BOOLEAN = 4;
    public static final byte DECIMAL = 5;
    public static final byte DATE = 6;
    public static final byte TIME = 7;
    public static final byte TIMESTAMP = 8;

    public final byte kind;
    /** java.sql.Types value, used for NULL. */
    public final int sqlType;
    public final String value;

    public Param(byte kind, int sqlType, String value) {
        this.kind = kind;
        this.sqlType = sqlType;
        this.value = value;
    }

}
