package com.orchestration.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BroadcastingAuditLogTest {

    private static AuditLog.AuditEvent event(String projectId) {
        return new AuditLog.AuditEvent("1", projectId, "t1", "engine", AuditLog.EventType.PROMPT,
                "dispatch", Map.of(), Instant.now());
    }

    @Test
    void recordsToDelegateAndBroadcasts() {
        AuditEventBroadcaster broadcaster = new AuditEventBroadcaster();
        AtomicInteger broadcast = new AtomicInteger();
        broadcaster.subscribe(e -> broadcast.incrementAndGet());

        InMemoryAuditLog delegate = new InMemoryAuditLog();
        BroadcastingAuditLog log = new BroadcastingAuditLog(delegate, broadcaster);

        log.record(event("p1"));

        assertEquals(1, broadcast.get());
        assertEquals(1, delegate.forProject("p1").size());
        assertEquals(1, log.forProject("p1").size());
    }
}
