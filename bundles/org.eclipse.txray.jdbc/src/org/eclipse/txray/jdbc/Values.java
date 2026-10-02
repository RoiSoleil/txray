package org.eclipse.txray.jdbc;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/** Text to Java conversions. Temporal values arrive in their {@code java.sql.*#toString()} form. */
final class Values {

    private Values() {
    }

    static boolean parseBoolean(String v) {
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("true") || s.equals("t") || s.equals("1") || s.equals("y") || s.equals("yes")
                || s.equals("on");
    }

    static Timestamp parseTimestamp(String v) throws SQLException {
        String s = v.trim();
        try {
            if (s.length() == 10) {
                return Timestamp.valueOf(s + " 00:00:00");
            }
            return Timestamp.valueOf(s.replace('T', ' '));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Not a timestamp: " + v, "22007");
        }
    }

    static Date parseDate(String v) throws SQLException {
        String s = v.trim();
        try {
            return Date.valueOf(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Not a date: " + v, "22007");
        }
    }

    static Time parseTime(String v) throws SQLException {
        String s = v.trim();
        int space = s.indexOf(' ');
        if (space > 0) {
            s = s.substring(space + 1);
        }
        int dot = s.indexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        try {
            return Time.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Not a time: " + v, "22007");
        }
    }

    static byte[] fromHex(String hex) {
        int len = hex.length() / 2;
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }
}
