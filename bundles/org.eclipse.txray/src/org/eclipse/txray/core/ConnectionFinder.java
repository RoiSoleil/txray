package org.eclipse.txray.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaValue;

/**
 * Finds the JDBC connections a suspended thread is working with:
 * <ol>
 * <li>the thread locals of the thread, where transaction managers keep the transactional resources
 * (Spring {@code TransactionSynchronizationManager}, Hibernate {@code ThreadLocalSessionContext},
 * ...);</li>
 * <li>the local variables of the stack frames, {@code this} and its fields.</li>
 * </ol>
 * Recognized holders: {@code java.sql.Connection}, Spring {@code ConnectionHolder},
 * {@code EntityManagerHolder} and {@code SessionHolder}, Hibernate sessions, JPA entity managers and
 * maps of them. Everything is read through fields where possible so that no connection is acquired
 * as a side effect.
 */
public final class ConnectionFinder {

    private static final int MAX_MAP_VALUES = 64;

    private final Jdi jdi;
    private final Map<Long, Candidate> found = new LinkedHashMap<>();
    private final Set<Long> visited = new HashSet<>();

    public ConnectionFinder(IJavaThread thread) {
        this.jdi = new Jdi(thread);
    }

    public List<Candidate> find() throws DebugException {
        scanThreadLocals();
        scanFrames();
        return new ArrayList<>(found.values());
    }

    /** Extracts the connection behind any supported value (variable, expression result...). */
    public List<Candidate> fromValue(IJavaValue value, String origin) throws DebugException {
        if (value instanceof IJavaObject) {
            examine((IJavaObject) value, origin, 2);
        }
        return new ArrayList<>(found.values());
    }

    // ---- thread locals -------------------------------------------------------------------

    private void scanThreadLocals() throws DebugException {
        IJavaObject threadObject = jdi.thread().getThreadObject();
        for (String field : new String[] { "threadLocals", "inheritableThreadLocals" }) {
            IJavaObject map = Jdi.fieldObject(threadObject, field);
            IJavaValue table = map == null ? null : Jdi.field(map, "table");
            if (!(table instanceof IJavaArray) || table.isNull()) {
                continue;
            }
            IJavaArray entries = (IJavaArray) table;
            for (IJavaValue entry : Jdi.elements(entries, entries.getLength())) {
                if (entry instanceof IJavaObject && !entry.isNull()) {
                    IJavaObject value = Jdi.fieldObject((IJavaObject) entry, "value");
                    if (value != null) {
                        examine(value, "thread local (" + Jdi.simpleTypeName(value) + ")", 2);
                    }
                }
            }
        }
    }

    // ---- stack frames --------------------------------------------------------------------

    private void scanFrames() throws DebugException {
        for (IStackFrame f : jdi.thread().getStackFrames()) {
            if (!(f instanceof IJavaStackFrame)) {
                continue;
            }
            IJavaStackFrame frame = (IJavaStackFrame) f;
            String where = frame.getDeclaringTypeName() + "." + frame.getMethodName() + "()";
            try {
                for (IVariable v : frame.getLocalVariables()) {
                    IJavaValue value = (IJavaValue) v.getValue();
                    if (value instanceof IJavaObject) {
                        examine((IJavaObject) value, "local '" + v.getName() + "' in " + where, 0);
                    }
                }
            } catch (DebugException e) {
                // no local variable information (class compiled without -g)
            }
            IJavaObject self = frame.getThis();
            if (self != null && !self.isNull()) {
                examine(self, "'this' in " + where, 0);
                for (IVariable v : self.getVariables()) {
                    IJavaValue value = (IJavaValue) v.getValue();
                    if (value instanceof IJavaObject) {
                        examine((IJavaObject) value, "field '" + v.getName() + "' of " + where, 0);
                    }
                }
            }
        }
    }

    // ---- recognition ---------------------------------------------------------------------

    private void examine(IJavaObject o, String origin, int depth) throws DebugException {
        if (o == null || o.isNull() || o instanceof IJavaArray || !visited.add(o.getUniqueId())) {
            return;
        }
        try {
            if (Jdi.isInstanceOf(o, "java.sql.Connection")) {
                add(o, origin);
            } else if (Jdi.isInstanceOf(o, "org.springframework.jdbc.datasource.ConnectionHolder")) {
                springConnectionHolder(o, origin);
            } else if (Jdi.isInstanceOf(o, "org.springframework.orm.jpa.EntityManagerHolder")) {
                examine(Jdi.fieldObject(o, "entityManager"), origin + " > EntityManager", depth);
            } else if (Jdi.hasField(o, "session")
                    && Jdi.typeName(o).startsWith("org.springframework.orm.hibernate")) {
                examine(Jdi.fieldObject(o, "session"), origin + " > Session", depth); // SessionHolder
            } else if (Jdi.isInstanceOf(o, "org.hibernate.engine.spi.SharedSessionContractImplementor")
                    || Jdi.isInstanceOf(o, "org.hibernate.engine.spi.SessionImplementor")) {
                hibernateSession(o, origin);
            } else if ((Jdi.isInstanceOf(o, "jakarta.persistence.EntityManager")
                    || Jdi.isInstanceOf(o, "javax.persistence.EntityManager"))
                    && !Jdi.isInstanceOf(o, "java.lang.reflect.Proxy")) {
                // a non Hibernate entity manager (EclipseLink, Hibernate < 5.2): ask for its delegate
                IJavaObject delegate = jdi.callObject(o, "getDelegate", "()Ljava/lang/Object;");
                examine(delegate, origin + " > delegate", depth);
                eclipseLink(o, origin);
            } else if (depth > 0 && Jdi.isInstanceOf(o, "java.util.Map")) {
                IJavaObject values = jdi.callObject(o, "values", "()Ljava/util/Collection;");
                IJavaObject array = values == null ? null
                        : jdi.callObject(values, "toArray", "()[Ljava/lang/Object;");
                if (array instanceof IJavaArray) {
                    for (IJavaValue v : Jdi.elements((IJavaArray) array, MAX_MAP_VALUES)) {
                        if (v instanceof IJavaObject) {
                            examine((IJavaObject) v, origin, depth - 1);
                        }
                    }
                }
            }
        } catch (DebugException e) {
            // an object we cannot inspect is not a candidate
        }
    }

    private void springConnectionHolder(IJavaObject holder, String origin) throws DebugException {
        IJavaObject current = Jdi.fieldObject(holder, "currentConnection");
        if (current != null) {
            add(current, origin + " > ConnectionHolder");
            return;
        }
        IJavaObject handle = Jdi.fieldObject(holder, "connectionHandle");
        if (handle != null) {
            // the transaction holds a connection handle: getConnection() returns the connection it uses
            IJavaObject c = jdi.callObject(holder, "getConnection", "()Ljava/sql/Connection;");
            if (c != null) {
                add(c, origin + " > ConnectionHolder");
            }
        }
    }

    /** Session > JdbcCoordinator > LogicalConnection > physical connection (Hibernate 5.x/6.x/7.x). */
    private void hibernateSession(IJavaObject session, String origin) throws DebugException {
        IJavaObject coordinator;
        try {
            coordinator = jdi.callObject(session, "getJdbcCoordinator",
                    "()Lorg/hibernate/engine/jdbc/spi/JdbcCoordinator;");
        } catch (DebugException e) {
            // Hibernate 3/4
            add(jdi.callObject(session, "connection", "()Ljava/sql/Connection;"), origin + " > Session.connection()");
            return;
        }
        IJavaObject logical = coordinator == null ? null : Jdi.fieldObject(coordinator, "logicalConnection");
        if (logical == null) {
            return;
        }
        for (String field : new String[] { "physicalConnection", "providedConnection" }) {
            if (Jdi.hasField(logical, field)) {
                // null: the session holds no connection right now, hence no transaction to look into
                add(Jdi.fieldObject(logical, field), origin + " > Hibernate Session");
                return;
            }
        }
    }

    /** EclipseLink: UnitOfWork > accessor > datasourceConnection, when a transaction is active. */
    private void eclipseLink(IJavaObject em, String origin) throws DebugException {
        if (!Jdi.typeName(em).startsWith("org.eclipse.persistence")) {
            return;
        }
        IJavaObject uow = Jdi.fieldObject(em, "extendedPersistenceContext");
        IJavaObject accessor = uow == null ? null : Jdi.fieldObject(uow, "accessor");
        if (accessor == null && uow != null) {
            IJavaValue accessors = Jdi.field(uow, "accessors");
            if (accessors instanceof IJavaObject && !accessors.isNull()) {
                IJavaObject first = jdi.callObject((IJavaObject) accessors, "iterator", "()Ljava/util/Iterator;");
                if (first != null && jdi.callBoolean(first, "hasNext", "()Z")) {
                    accessor = jdi.callObject(first, "next", "()Ljava/lang/Object;");
                }
            }
        }
        if (accessor != null) {
            add(Jdi.fieldObject(accessor, "datasourceConnection"), origin + " > EclipseLink accessor");
        }
    }

    private void add(IJavaObject connection, String origin) throws DebugException {
        if (connection == null || connection.isNull()) {
            return;
        }
        found.putIfAbsent(connection.getUniqueId(), new Candidate(connection, origin));
    }

    /** A connection and where it was found. */
    public static final class Candidate {
        private final IJavaObject connection;
        private final String origin;

        Candidate(IJavaObject connection, String origin) {
            this.connection = connection;
            this.origin = origin;
        }

        public IJavaObject getConnection() {
            return connection;
        }

        public String getOrigin() {
            return origin;
        }

        public String getLabel() {
            return Jdi.simpleTypeName(connection) + "  –  " + origin;
        }
    }
}
