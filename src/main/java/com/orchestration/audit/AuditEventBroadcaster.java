package com.orchestration.audit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Fan-out hub for live {@link AuditLog.AuditEvent}s. The web layer subscribes (one listener per
 * open SSE connection) to stream the agent-interaction timeline to browsers in real time.
 *
 * <p>Deliberately framework-free (plain {@link Consumer}s, no web types) so the audit package stays
 * decoupled from the UI; the controller bridges listeners to SSE.
 */
public class AuditEventBroadcaster {

    private final List<Consumer<AuditLog.AuditEvent>> listeners = new CopyOnWriteArrayList<>();

    /** Register a listener; returns a handle that removes it. */
    public Runnable subscribe(Consumer<AuditLog.AuditEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Deliver an event to all current listeners; a failing listener never blocks the others. */
    public void publish(AuditLog.AuditEvent event) {
        for (Consumer<AuditLog.AuditEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // a broken SSE connection must not break delivery to other subscribers
            }
        }
    }

    public int listenerCount() {
        return listeners.size();
    }
}
