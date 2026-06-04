package com.orchestration.metrics;

import com.orchestration.audit.AuditLog.AuditEvent;
import com.orchestration.audit.AuditLog.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsCalculatorTest {

    private static AuditEvent ev(EventType type, String summary, Map<String, Object> details, Instant at) {
        return new AuditEvent("id", "p1", "t1", "actor", type, summary, details, at);
    }

    @Test
    void countsReworkBuildFixAndBuildOutcomes() {
        Instant t0 = Instant.parse("2026-06-04T10:00:00Z");
        List<AuditEvent> events = List.of(
                ev(EventType.PROMPT, "Dispatching to BACKEND_DEVELOPER",
                        Map.of("role", "BACKEND_DEVELOPER"), t0),
                ev(EventType.RESPONSE, "outcome=NEEDS_REVIEW",
                        Map.of("role", "BACKEND_DEVELOPER", "outcome", "NEEDS_REVIEW"), t0.plusSeconds(5)),
                ev(EventType.PROMPT, "Rework #1 for BACKEND_DEVELOPER",
                        Map.of("role", "BACKEND_DEVELOPER"), t0.plusSeconds(6)),
                ev(EventType.RESPONSE, "outcome=COMPLETED",
                        Map.of("role", "BACKEND_DEVELOPER", "outcome", "COMPLETED"), t0.plusSeconds(10)),
                ev(EventType.PROMPT, "Build-fix #1 by BACKEND_DEVELOPER",
                        Map.of("role", "QA_ENGINEER"), t0.plusSeconds(11)),
                ev(EventType.RESPONSE, "Build FAILED (exit 1)",
                        Map.of("role", "QA_ENGINEER", "outcome", "NEEDS_REVIEW"), t0.plusSeconds(12)),
                ev(EventType.RESPONSE, "Build PASSED",
                        Map.of("role", "QA_ENGINEER", "outcome", "COMPLETED"), t0.plusSeconds(20)),
                ev(EventType.ERROR, "something broke", Map.of(), t0.plusSeconds(21)));

        RunMetrics m = MetricsCalculator.summarize(events, "p1", "DONE");

        assertEquals("DONE", m.state());
        assertEquals(1, m.reworkDispatches());
        assertEquals(1, m.buildFixDispatches());
        assertEquals(1, m.buildsPassed());
        assertEquals(1, m.buildsFailed());
        assertEquals(1, m.errors());
        assertEquals(21_000L, m.durationMillis());

        RoleMetrics dev = m.byRole().get("BACKEND_DEVELOPER");
        assertEquals(2, dev.responses());
        assertEquals(1, dev.completed());
        assertEquals(1, dev.needsReview());
        assertEquals(1, dev.rework());

        RoleMetrics qa = m.byRole().get("QA_ENGINEER");
        assertEquals(2, qa.responses());
        assertEquals(1, qa.completed());
        assertEquals(1, qa.needsReview());
        assertEquals(0, qa.rework());
    }

    @Test
    void emptyHistoryIsAllZeros() {
        RunMetrics m = MetricsCalculator.summarize(List.of(), "p1", "PLANNING");
        assertEquals(0, m.prompts());
        assertEquals(0, m.responses());
        assertEquals(0, m.durationMillis());
        assertEquals(0, m.byRole().size());
    }
}
