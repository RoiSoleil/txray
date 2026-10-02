package org.eclipse.txray.tests;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The debugged application of the end-to-end test: a plain Java program, outside of Eclipse, with
 * an H2 database. It opens a transaction, inserts a row without committing it, keeps the connection
 * in a thread local (as transaction managers do) and calls {@link #checkpoint(Connection)}, where
 * the test suspends it. After the test, it prints what its transaction sees and rolls it back.
 */
public final class Debuggee {

    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

    private Debuggee() {
    }

    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:txray;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE ORDERS (ID INT PRIMARY KEY, LABEL VARCHAR(50), AMOUNT DECIMAL(10, 2), "
                        + "CREATED TIMESTAMP)");
            }
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                s.executeUpdate("INSERT INTO ORDERS VALUES (1, 'uncommitted', 12.50, TIMESTAMP '2026-10-02 18:24:00')");
            }
            CURRENT.set(connection);

            System.out.println("READY");
            System.out.flush();
            // the test sets its breakpoint, then lets the application go on
            new BufferedReader(new InputStreamReader(System.in)).readLine();

            checkpoint(connection);

            System.out.println("LABEL=" + query(connection, "SELECT LABEL FROM ORDERS WHERE ID = 1"));
            connection.rollback();
            System.out.println("AFTER_ROLLBACK=" + query(connection, "SELECT COUNT(*) FROM ORDERS"));
            CURRENT.remove();
        }
    }

    /** The breakpoint of the test. */
    static void checkpoint(Connection connection) {
        System.out.println("CHECKPOINT " + (connection == CURRENT.get()));
    }

    private static String query(Connection connection, String sql) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "<none>";
        }
    }
}
