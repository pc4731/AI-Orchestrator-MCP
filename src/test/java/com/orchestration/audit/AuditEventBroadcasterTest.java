package com.orchestration.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditEventBroadcasterTest {

    private static AuditLog.AuditEvent event() {
        return new AuditLog.AuditEvent("1", "p", "t", "engine", AuditLog.EventType.RESPONSE,
                "summary", Map.of(), Instant.now());
    }

    @Test
    void deliversToSubscribers() {
        AuditEventBroadcaster broadcaster = new AuditEventBroadcaster();
        AtomicInteger count = new AtomicInteger();
        broadcaster.subscribe(e -> count.incrementAndGet());

        broadcaster.publish(event());

        assertEquals(1, count.get());
    }

    @Test
    void unsubscribeStopsDelivery() {
        AuditEventBroadcaster broadcaster = new AuditEventBroadcaster();
        AtomicInteger count = new AtomicInteger();
        Runnable unsubscribe = broadcaster.subscribe(e -> count.incrementAndGet());

        broadcaster.publish(event());
        unsubscribe.run();
        broadcaster.publish(event());

        assertEquals(1, count.get());
    }

    @Test
    void failingListenerIsIsolated() {
        AuditEventBroadcaster broadcaster = new AuditEventBroadcaster();
        AtomicInteger delivered = new AtomicInteger();
        broadcaster.subscribe(e -> {
            throw new RuntimeException("boom");
        });
        broadcaster.subscribe(e -> delivered.incrementAndGet());

        broadcaster.publish(event());

        assertEquals(1, delivered.get());
    }
}
