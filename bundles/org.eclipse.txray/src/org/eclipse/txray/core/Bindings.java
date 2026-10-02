package org.eclipse.txray.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaThread;

/** The exposed connections. Bindings disappear with their thread or their debug target. */
public final class Bindings implements IDebugEventSetListener {

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final List<Consumer<Binding>> removalListeners = new CopyOnWriteArrayList<>();

    /** Called when a binding is released, replaced, or disappears with its thread or debug target. */
    public void addRemovalListener(Consumer<Binding> listener) {
        removalListeners.add(listener);
    }

    public void start() {
        DebugPlugin.getDefault().addDebugEventListener(this);
    }

    public void stop() {
        DebugPlugin plugin = DebugPlugin.getDefault();
        if (plugin != null) {
            plugin.removeDebugEventListener(this);
        }
        for (Binding b : all()) {
            remove(b);
        }
    }

    /**
     * Exposes a connection. A connection already exposed for the same thread replaces the previous
     * one, so that "the connection of this thread" stays unambiguous.
     */
    public Binding add(IJavaThread thread, IJavaObject connection, String origin) throws DebugException {
        connection.disableCollection();
        Binding b = new Binding(Integer.toString(ids.incrementAndGet()), thread, connection, origin);
        List<Binding> replaced = new ArrayList<>();
        synchronized (bindings) {
            for (Binding old : bindings.values()) {
                if (old.getThread().equals(thread)) {
                    replaced.add(old);
                }
            }
            for (Binding old : replaced) {
                bindings.remove(old.getId());
            }
            bindings.put(b.getId(), b);
        }
        replaced.forEach(this::removed);
        return b;
    }

    public void remove(Binding b) {
        synchronized (bindings) {
            if (bindings.remove(b.getId()) == null) {
                return;
            }
        }
        removed(b);
    }

    private void removed(Binding b) {
        b.dispose();
        for (Consumer<Binding> l : removalListeners) {
            l.accept(b);
        }
    }

    /** The binding with this id, or the most recent one when {@code id} is null or empty. */
    public Binding get(String id) {
        synchronized (bindings) {
            if (id == null || id.isEmpty()) {
                Binding last = null;
                for (Binding b : bindings.values()) {
                    last = b;
                }
                return last;
            }
            return bindings.get(id);
        }
    }

    public List<Binding> all() {
        synchronized (bindings) {
            return new ArrayList<>(bindings.values());
        }
    }

    public List<Binding> forThread(IJavaThread thread) {
        List<Binding> result = new ArrayList<>();
        for (Binding b : all()) {
            if (b.getThread().equals(thread)) {
                result.add(b);
            }
        }
        return result;
    }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        for (DebugEvent event : events) {
            if (event.getKind() != DebugEvent.TERMINATE) {
                continue;
            }
            Object source = event.getSource();
            for (Binding b : all()) {
                IJavaThread t = b.getThread();
                IDebugTarget target = t.getDebugTarget();
                if (source.equals(t) || source.equals(target)) {
                    remove(b);
                }
            }
        }
    }
}
