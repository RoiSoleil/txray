package org.eclipse.txray.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Wrapper;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base of the dynamic JDBC proxies. A call on the JDBC interface is routed to the public method
 * of the handler having the same name and parameter types; methods the handler does not declare
 * fall back to a neutral value (false, 0, null, empty result set). This keeps the driver small
 * while staying tolerant to the hundreds of optional JDBC methods DBeaver may probe.
 */
abstract class JdbcHandler implements InvocationHandler {

    private static final Map<Class<?>, Map<String, Method>> CACHE = new ConcurrentHashMap<>();
    private static final Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Object proxy;

    @SuppressWarnings("unchecked")
    final <T> T proxy(Class<T> iface) {
        if (proxy == null) {
            proxy = Proxy.newProxyInstance(JdbcHandler.class.getClassLoader(), new Class<?>[] { iface }, this);
        }
        return (T) proxy;
    }

    final Object self() {
        return proxy;
    }

    @Override
    public Object invoke(Object p, Method m, Object[] args) throws Throwable {
        if (m.getDeclaringClass() == Object.class) {
            switch (m.getName()) {
            case "equals":
                return p == args[0];
            case "hashCode":
                return System.identityHashCode(p);
            default:
                return getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(p));
            }
        }
        if (m.getDeclaringClass() == Wrapper.class) {
            Class<?> iface = (Class<?>) args[0];
            if ("isWrapperFor".equals(m.getName())) {
                return iface.isInstance(p);
            }
            if (iface.isInstance(p)) {
                return p;
            }
            throw new SQLException("Not a wrapper for " + iface.getName());
        }
        Method impl = lookup(m);
        if (impl == NONE) {
            return fallback(m, args);
        }
        try {
            return impl.invoke(this, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Method lookup(Method m) {
        Map<String, Method> methods = CACHE.computeIfAbsent(getClass(), c -> new ConcurrentHashMap<>());
        String key = m.getName() + java.util.Arrays.toString(m.getParameterTypes());
        return methods.computeIfAbsent(key, k -> {
            try {
                Method impl = getClass().getMethod(m.getName(), m.getParameterTypes());
                if (impl.getDeclaringClass() == Object.class) {
                    return NONE;
                }
                impl.setAccessible(true);
                return impl;
            } catch (NoSuchMethodException e) {
                return NONE;
            }
        });
    }

    /** Neutral answer for methods a handler does not implement. */
    protected Object fallback(Method m, Object[] args) throws SQLException {
        Class<?> r = m.getReturnType();
        if (r == void.class) {
            return null;
        }
        if (r == boolean.class) {
            return Boolean.FALSE;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == short.class) {
            return (short) 0;
        }
        if (r == byte.class) {
            return (byte) 0;
        }
        if (r == double.class) {
            return 0d;
        }
        if (r == float.class) {
            return 0f;
        }
        if (r == ResultSet.class) {
            return ResultSetHandler.empty();
        }
        if (r == Map.class) {
            return Collections.emptyMap();
        }
        return null;
    }
}
