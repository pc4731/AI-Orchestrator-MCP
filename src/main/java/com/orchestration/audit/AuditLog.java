package com.orchestration.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full traceability of every agent decision, prompt and response.
 *
 * <p>Distinct from the {@code ArtifactRepository} (which versions produced files) and the
 * {@code MemoryStore} (which keeps reusable, summarised knowledge): the audit log is the
 * append-only record of <i>what happened</i>, for debugging and accountability.
 */
public interface AuditLog {

    void record(AuditEvent event);

    List<AuditEvent> forProject(String projectId);

    List<AuditEvent> forTask(String taskId);

    record AuditEvent(
            String id,
            String projectId,
            String taskId,        // may be null for project-level events
            String actor,         // AgentId value, "engine", or "human"
            EventType type,
            String summary,
            Map<String, Object> details,
            Instant at
    ) {
        public AuditEvent {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    enum EventType {
        PROMPT,
        RESPONSE,
        DECISION,
        STATE_CHANGE,
        ESCALATION,
        GATE,
        BUDGET,
        TOOL_EXECUTION,
        ERROR
    }
}
