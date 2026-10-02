package org.eclipse.txray.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import org.eclipse.txray.jdbc.backend.Backend;
import org.eclipse.txray.jdbc.backend.ColumnMeta;
import org.eclipse.txray.jdbc.backend.ExecuteRequest;
import org.eclipse.txray.jdbc.backend.ExecuteResult;
import org.eclipse.txray.jdbc.backend.Param;
import org.eclipse.txray.jdbc.backend.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The driver against a fake of the Eclipse side. */
class TxRayDriverTest {

    private volatile ExecuteRequest lastRequest;

    @BeforeEach
    void start() {
        TxRayDriver.setBackend(new FakeBackend());
    }

    private Connection connect(String binding) throws SQLException {
        return new TxRayDriver().connect(TxRayDriver.url(binding), new Properties());
    }

    @Test
    void acceptsTxRayUrls() {
        assertTrue(new TxRayDriver().acceptsURL("jdbc:txray:3"));
        assertTrue(new TxRayDriver().acceptsURL(TxRayDriver.url(null)));
        assertFalse(new TxRayDriver().acceptsURL("jdbc:postgresql://x/y"));
    }

    @Test
    void followsTheLastExposedConnectionWithoutId() throws SQLException {
        try (Connection c = connect(null)) {
            assertEquals("PostgreSQL", c.getMetaData().getDatabaseProductName());
        }
    }

    @Test
    void reportsTheRealDatabase() throws SQLException {
        try (Connection c = connect("1")) {
            DatabaseMetaData md = c.getMetaData();
            assertEquals("PostgreSQL", md.getDatabaseProductName());
            assertEquals(16, md.getDatabaseMajorVersion());
            assertEquals("app", md.getUserName());
            assertEquals("public", c.getSchema());
            try (ResultSet tables = md.getTables(null, null, "%", null)) {
                assertFalse(tables.next());
            }
        }
    }

    @Test
    void readsTypedRows() throws SQLException {
        try (Connection c = connect(null); Statement s = c.createStatement()) {
            s.setMaxRows(200);
            try (ResultSet rs = s.executeQuery("SELECT rows")) {
                assertEquals(200, lastRequest.maxRows);
                assertNull(lastRequest.params);
                ResultSetMetaData md = rs.getMetaData();
                assertEquals(6, md.getColumnCount());
                assertEquals("ID", md.getColumnLabel(1));
                assertEquals(Types.BIGINT, md.getColumnType(1));
                assertEquals(Long.class.getName(), md.getColumnClassName(1));

                assertTrue(rs.next());
                assertEquals(42L, rs.getObject(1));
                assertEquals(42, rs.getInt("id"));
                assertEquals("café", rs.getString("NAME"));
                assertEquals(new BigDecimal("12.50"), rs.getBigDecimal(3));
                assertTrue(rs.getBoolean(4));
                assertEquals(Timestamp.valueOf("2026-10-02 17:04:00.5"), rs.getTimestamp(5));
                assertEquals(LocalDate.of(2026, 10, 2), rs.getObject(5, java.time.LocalDateTime.class).toLocalDate());
                assertArrayEquals(new byte[] { 0x0a, (byte) 0xff }, rs.getBytes(6));

                assertTrue(rs.next());
                assertNull(rs.getObject(2));
                assertTrue(rs.wasNull());
                assertEquals(0, rs.getInt(3));
                assertTrue(rs.wasNull());
                assertFalse(rs.getBoolean(4));

                assertFalse(rs.next());
                assertTrue(rs.absolute(1));
                assertEquals(42, rs.getInt(1));
            }
            assertNotNull(s.getWarnings(), "truncated results are signaled");
        }
    }

    @Test
    void sendsPreparedParameters() throws SQLException {
        try (Connection c = connect("1"); PreparedStatement ps = c.prepareStatement("SELECT rows WHERE ?")) {
            ps.setLong(1, 7);
            ps.setString(2, "x");
            ps.setNull(3, Types.INTEGER);
            ps.setObject(4, LocalDate.of(2026, 1, 31));
            ps.setBigDecimal(5, new BigDecimal("1.5"));
            ps.executeQuery().close();
            List<Param> p = lastRequest.params;
            assertEquals(5, p.size());
            assertEquals(Param.LONG, p.get(0).kind);
            assertEquals("7", p.get(0).value);
            assertEquals(Param.STRING, p.get(1).kind);
            assertEquals(Param.NULL, p.get(2).kind);
            assertEquals(Types.INTEGER, p.get(2).sqlType);
            assertEquals(Param.DATE, p.get(3).kind);
            assertEquals("2026-01-31", p.get(3).value);
            assertEquals(Param.DECIMAL, p.get(4).kind);
        }
    }

    @Test
    void missingParameterIsAnError() throws SQLException {
        try (Connection c = connect("1"); PreparedStatement ps = c.prepareStatement("SELECT ?, ?")) {
            ps.setInt(2, 1);
            assertThrows(SQLException.class, ps::executeQuery);
        }
    }

    @Test
    void returnsUpdateCounts() throws SQLException {
        try (Connection c = connect("1"); Statement s = c.createStatement()) {
            assertFalse(s.execute("UPDATE t SET x = 1"));
            assertEquals(3, s.getUpdateCount());
            assertEquals(3, s.executeUpdate("UPDATE t SET x = 2"));
        }
    }

    @Test
    void propagatesDatabaseErrors() throws SQLException {
        try (Connection c = connect("1"); Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.executeQuery("FAIL"));
            assertEquals("ERROR: relation \"nope\" does not exist", e.getMessage());
            assertEquals("42P01", e.getSQLState());
            assertEquals(7, e.getErrorCode());
            // the connection is still usable after an error
            assertFalse(s.execute("UPDATE t SET x = 1"));
        }
    }

    @Test
    void neverDrivesTheTransaction() throws SQLException {
        try (Connection c = connect("1")) {
            assertTrue(c.getAutoCommit());
            c.setAutoCommit(false);
            c.commit();
            c.rollback();
            assertTrue(c.getAutoCommit());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
        }
    }

    @Test
    void reportsAMissingBinding() {
        SQLException e = assertThrows(SQLException.class, () -> connect("missing"));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().contains("no longer exposed"));
    }

    @Test
    void unwrapsAndProbesOptionalMethods() throws SQLException {
        try (Connection c = connect("1")) {
            assertTrue(c.isWrapperFor(Connection.class));
            assertNotNull(c.unwrap(Connection.class));
            assertFalse(c.getMetaData().supportsBatchUpdates());
            assertTrue(c.isValid(1));
        }
    }

    // ---- fake Eclipse side ---------------------------------------------------------------

    private final class FakeBackend implements Backend {

        @Override
        public ServerInfo describe(String bindingId) throws SQLException {
            if ("missing".equals(bindingId)) {
                throw new SQLException("TxRay: connection #missing is no longer exposed", "08001");
            }
            return info();
        }

        @Override
        public ExecuteResult execute(String bindingId, ExecuteRequest r) throws SQLException {
            lastRequest = r;
            if (r.sql.startsWith("FAIL")) {
                throw new SQLException("ERROR: relation \"nope\" does not exist", "42P01", 7);
            }
            if (r.sql.startsWith("UPDATE")) {
                ExecuteResult res = new ExecuteResult();
                res.kind = ExecuteResult.UPDATE_COUNT;
                res.updateCount = 3;
                return res;
            }
            return rows();
        }
    }

    private static ServerInfo info() {
        ServerInfo info = new ServerInfo();
        info.bindingId = "1";
        info.threadName = "main";
        info.productName = "PostgreSQL";
        info.productVersion = "16.4";
        info.url = "jdbc:postgresql://db/app";
        info.userName = "app";
        info.schema = "public";
        info.autoCommit = false;
        info.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED;
        return info;
    }

    private static ExecuteResult rows() {
        ExecuteResult r = new ExecuteResult();
        r.kind = ExecuteResult.ROWS;
        r.columns.add(column("ID", Types.BIGINT, "int8"));
        r.columns.add(column("NAME", Types.VARCHAR, "varchar"));
        r.columns.add(column("AMOUNT", Types.NUMERIC, "numeric"));
        r.columns.add(column("ACTIVE", Types.BIT, "bool"));
        r.columns.add(column("CREATED", Types.TIMESTAMP, "timestamp"));
        r.columns.add(column("DATA", Types.VARBINARY, "bytea"));
        r.rows.add(new String[] { "42", "café", "12.50", "t", "2026-10-02 17:04:00.5", "0aff" });
        r.rows.add(new String[] { "43", null, null, "f", null, null });
        r.truncated = true;
        return r;
    }

    private static ColumnMeta column(String name, int type, String typeName) {
        ColumnMeta c = new ColumnMeta();
        c.name = name;
        c.label = name;
        c.type = type;
        c.typeName = typeName;
        c.nullable = ResultSetMetaData.columnNullable;
        return c;
    }
}
