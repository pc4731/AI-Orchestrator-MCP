package com.orchestration.audit;

import java.util.List;
import java.util.Objects;

/**
 * An {@link AuditLog} decorator that persists to a delegate <i>and</i> fans each event out through
 * an {@link AuditEventBroadcaster} for live streaming to the UI. Decorating keeps the storage and
 * the streaming concerns independent (Decorator pattern).
 */
public class BroadcastingAuditLog implements AuditLog {

    private final AuditLog delegate;
    private final AuditEventBroadcaster broadcaster;

    public BroadcastingAuditLog(AuditLog delegate, AuditEventBroadcaster broadcaster) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
    }

    @Override
    public void record(AuditEvent event) {
        delegate.record(event);
        broadcaster.publish(event);
    }

    @Override
    public List<AuditEvent> forProject(String projectId) {
        return delegate.forProject(projectId);
    }

    @Override
    public List<AuditEvent> forTask(String taskId) {
        return delegate.forTask(taskId);
    }
}
