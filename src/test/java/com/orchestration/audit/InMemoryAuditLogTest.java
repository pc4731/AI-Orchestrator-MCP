package com.orchestration.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryAuditLogTest {

    private static AuditLog.AuditEvent event(String id, String projectId, String taskId,
                                             AuditLog.EventType type) {
        return new AuditLog.AuditEvent(id, projectId, taskId, "engine", type, "summary", Map.of(), Instant.now());
    }

    @Test
    void recordsAndFiltersByProjectAndTask() {
        InMemoryAuditLog log = new InMemoryAuditLog();
        log.record(event("1", "p1", "t1", AuditLog.EventType.DECISION));
        log.record(event("2", "p1", null, AuditLog.EventType.STATE_CHANGE));
        log.record(event("3", "p2", "t9", AuditLog.EventType.ERROR));

        assertEquals(2, log.forProject("p1").size());
        assertEquals(1, log.forTask("t1").size());
        assertEquals(3, log.all().size());
    }
}
