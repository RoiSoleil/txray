package org.eclipse.txray.core;

import java.sql.SQLException;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaInterfaceType;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaPrimitiveValue;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.internal.debug.core.model.JDIDebugTarget;
import org.eclipse.jdt.internal.debug.core.model.JDIValue;

/**
 * Small helpers over the JDT debug model: method invocations in a suspended thread (exactly what
 * the Debug Shell does) and field reads (no code executed in the debugged VM).
 */
@SuppressWarnings("restriction")
public final class Jdi {

    private static final IJavaValue[] NO_ARGS = new IJavaValue[0];

    private final IJavaThread thread;
    private final IJavaDebugTarget target;

    public Jdi(IJavaThread thread) {
        this.thread = thread;
        this.target = (IJavaDebugTarget) thread.getDebugTarget();
    }

    public IJavaThread thread() {
        return thread;
    }

    public IJavaDebugTarget target() {
        return target;
    }

    // ---- invocations ---------------------------------------------------------------------

    public IJavaValue call(IJavaObject receiver, String name, String signature, IJavaValue... args)
            throws DebugException {
        return receiver.sendMessage(name, signature, args.length == 0 ? NO_ARGS : args, thread, false);
    }

    public IJavaObject callObject(IJavaObject receiver, String name, String signature, IJavaValue... args)
            throws DebugException {
        IJavaValue v = call(receiver, name, signature, args);
        return isNull(v) ? null : (IJavaObject) v;
    }

    public String callString(IJavaObject receiver, String name, String signature, IJavaValue... args)
            throws DebugException {
        return string(call(receiver, name, signature, args));
    }

    public int callInt(IJavaObject receiver, String name, String signature, IJavaValue... args)
            throws DebugException {
        return ((IJavaPrimitiveValue) call(receiver, name, signature, args)).getIntValue();
    }

    public boolean callBoolean(IJavaObject receiver, String name, String signature, IJavaValue... args)
            throws DebugException {
        return ((IJavaPrimitiveValue) call(receiver, name, signature, args)).getBooleanValue();
    }

    /** Static call, or null when the class is not loaded in the debugged VM. */
    public IJavaValue callStatic(String className, String name, String signature, IJavaValue... args)
            throws DebugException {
        IJavaClassType type = classType(className);
        if (type == null) {
            return null;
        }
        return type.sendMessage(name, signature, args, thread);
    }

    public IJavaObject newInstance(String className, String signature, IJavaValue... args) throws DebugException {
        IJavaClassType type = classType(className);
        if (type == null) {
            return null;
        }
        return type.newInstance(signature, args, thread);
    }

    private IJavaClassType classType(String className) throws DebugException {
        IJavaType[] types = target.getJavaTypes(className);
        if (types != null) {
            for (IJavaType t : types) {
                if (t instanceof IJavaClassType) {
                    return (IJavaClassType) t;
                }
            }
        }
        return null;
    }

    // ---- values --------------------------------------------------------------------------

    public IJavaValue value(String s) {
        return s == null ? target.nullValue() : target.newValue(s);
    }

    public IJavaValue value(int i) {
        return target.newValue(i);
    }

    public IJavaValue value(long l) {
        return target.newValue(l);
    }

    public IJavaValue value(double d) {
        return target.newValue(d);
    }

    public IJavaValue value(boolean b) {
        return target.newValue(b);
    }

    public static boolean isNull(IJavaValue v) {
        return v == null || v.isNull();
    }

    public static String string(IJavaValue v) throws DebugException {
        return isNull(v) ? null : v.getValueString();
    }

    /** Reads a field without running code in the debugged VM. */
    public static IJavaValue field(IJavaObject o, String name) throws DebugException {
        if (o == null || o.isNull()) {
            return null;
        }
        IJavaFieldVariable f = o.getField(name, false);
        return f == null ? null : (IJavaValue) f.getValue();
    }

    public static IJavaObject fieldObject(IJavaObject o, String name) throws DebugException {
        IJavaValue v = field(o, name);
        return v instanceof IJavaObject && !v.isNull() ? (IJavaObject) v : null;
    }

    public static boolean hasField(IJavaObject o, String name) throws DebugException {
        return o.getField(name, false) != null;
    }

    /** Bytes of a {@code byte[]}, at most {@code max} of them. */
    public static byte[] bytes(IJavaArray array, int max) throws DebugException {
        int len = Math.min(array.getLength(), max);
        byte[] b = new byte[len];
        if (len == 0) {
            return b;
        }
        IVariable[] vars = array.getVariables(0, len);
        for (int i = 0; i < len; i++) {
            b[i] = ((IJavaPrimitiveValue) vars[i].getValue()).getByteValue();
        }
        return b;
    }

    /** Elements of an {@code Object[]}, at most {@code max} of them. */
    public static IJavaValue[] elements(IJavaArray array, int max) throws DebugException {
        int len = Math.min(array.getLength(), max);
        IJavaValue[] values = new IJavaValue[len];
        if (len == 0) {
            return values;
        }
        IVariable[] vars = array.getVariables(0, len);
        for (int i = 0; i < len; i++) {
            values[i] = (IJavaValue) vars[i].getValue();
        }
        return values;
    }

    // ---- types ---------------------------------------------------------------------------

    /** {@code instanceof} on the runtime type, without running code in the debugged VM. */
    public static boolean isInstanceOf(IJavaObject o, String typeName) throws DebugException {
        if (o == null || o.isNull()) {
            return false;
        }
        IJavaType t = o.getJavaType();
        if (!(t instanceof IJavaClassType)) {
            return false;
        }
        IJavaClassType c = (IJavaClassType) t;
        for (IJavaClassType k = c; k != null; k = k.getSuperclass()) {
            if (typeName.equals(k.getName())) {
                return true;
            }
        }
        for (IJavaInterfaceType i : c.getAllInterfaces()) {
            if (typeName.equals(i.getName())) {
                return true;
            }
        }
        return false;
    }

    public static String typeName(IJavaObject o) {
        try {
            IJavaType t = o.getJavaType();
            return t == null ? "null" : t.getName();
        } catch (DebugException e) {
            return "?";
        }
    }

    public static String simpleTypeName(IJavaObject o) {
        String n = typeName(o);
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : n;
    }

    // ---- errors --------------------------------------------------------------------------

    /**
     * Turns a failed invocation into a readable SQL error: the exception thrown in the debugged VM
     * (SQLException message, SQLState, vendor code) or the reason the thread cannot run code.
     */
    public SQLException failure(DebugException e) {
        switch (e.getStatus().getCode()) {
        case IJavaThread.ERR_THREAD_NOT_SUSPENDED:
            return new SQLException("TxRay: thread '" + threadName()
                    + "' is running. Suspend it on a breakpoint (or a step) to query its connection.", "08003", 0);
        case IJavaThread.ERR_INCOMPATIBLE_THREAD_STATE:
            return new SQLException("TxRay: thread '" + threadName()
                    + "' was suspended with the Suspend button. Stop it on a breakpoint or with a step: "
                    + "the JVM only allows method invocations in a thread suspended by an event.", "08003", 0);
        case IJavaThread.ERR_NESTED_METHOD_INVOCATION:
            return new SQLException("TxRay: another evaluation is running in thread '" + threadName() + "'.",
                    "08003", 0);
        default:
            break;
        }
        IJavaObject ex = thrown(e);
        if (ex != null) {
            try {
                String message = callString(ex, "toString", "()Ljava/lang/String;");
                String state = null;
                int code = 0;
                if (isInstanceOf(ex, "java.sql.SQLException")) {
                    state = callString(ex, "getSQLState", "()Ljava/lang/String;");
                    code = callInt(ex, "getErrorCode", "()I");
                }
                return new SQLException(message, state, code);
            } catch (DebugException | RuntimeException inner) {
                // fall back to the debugger's message
            }
        }
        return new SQLException("TxRay: " + e.getMessage(), "HY000");
    }

    /**
     * The exception thrown in the debugged VM by an invocation ({@code com.sun.jdi.InvocationException}).
     * The JDI types come from the JDK, outside of the OSGi class space: they are reached by reflection.
     */
    private IJavaObject thrown(DebugException e) {
        Throwable cause = e.getStatus().getException();
        if (cause == null || !"com.sun.jdi.InvocationException".equals(cause.getClass().getName())) {
            return null;
        }
        try {
            Object reference = cause.getClass().getMethod("exception").invoke(cause);
            Class<?> value = Class.forName("com.sun.jdi.Value", false, cause.getClass().getClassLoader());
            Object v = JDIValue.class.getMethod("createValue", JDIDebugTarget.class, value).invoke(null, target,
                    reference);
            return v instanceof IJavaObject ? (IJavaObject) v : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private String threadName() {
        try {
            return thread.getName();
        } catch (DebugException e) {
            return "?";
        }
    }
}
