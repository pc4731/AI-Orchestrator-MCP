package com.orchestration.audit;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link AuditLog}: an append-only, thread-safe record of agent decisions, prompts and
 * responses. Sufficient for the v1 demo and tests; a persistent (SQLite-backed) audit log can be
 * substituted behind the same interface when durability across restarts is required.
 */
public class InMemoryAuditLog implements AuditLog {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    @Override
    public List<AuditEvent> forProject(String projectId) {
        return events.stream().filter(e -> Objects.equals(projectId, e.projectId())).toList();
    }

    @Override
    public List<AuditEvent> forTask(String taskId) {
        return events.stream().filter(e -> Objects.equals(taskId, e.taskId())).toList();
    }

    /** All recorded events, in insertion order. */
    public List<AuditEvent> all() {
        return List.copyOf(events);
    }
}
