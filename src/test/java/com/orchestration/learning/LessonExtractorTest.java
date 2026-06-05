package com.orchestration.learning;

import com.orchestration.audit.AuditLog.AuditEvent;
import com.orchestration.audit.AuditLog.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LessonExtractorTest {

    private static AuditEvent prompt(String summary, Map<String, Object> details) {
        return new AuditEvent("id", "p1", "t1", "actor", EventType.PROMPT, summary, details, Instant.now());
    }

    @Test
    void extractsBuildReviewAndClarificationLessonsWithEvidence() {
        List<AuditEvent> events = List.of(
                prompt("Build-fix #1 by BACKEND_DEVELOPER",
                        Map.of("role", "BACKEND_DEVELOPER", "prompt", "compile error: missing semicolon")),
                prompt("Fixing CODE_REVIEWER findings by BACKEND_DEVELOPER",
                        Map.of("role", "BACKEND_DEVELOPER", "prompt", "SQL injection risk in query")),
                prompt("Clarified with the user; re-running BACKEND_ARCHITECT",
                        Map.of("role", "BACKEND_ARCHITECT", "prompt", "Which database should I use?")),
                // noise that must NOT produce a lesson:
                prompt("Dispatching to BACKEND_DEVELOPER", Map.of("role", "BACKEND_DEVELOPER", "prompt", "build it")));

        List<Lesson> lessons = LessonExtractor.extract(events, "p1");

        assertEquals(3, lessons.size());
        assertTrue(lessons.stream().anyMatch(l -> l.category().equals(Lesson.BUILD_FIX)
                && l.role().equals("BACKEND_DEVELOPER") && l.evidence().contains("semicolon")));
        assertTrue(lessons.stream().anyMatch(l -> l.category().equals(Lesson.REVIEW_FIX)
                && l.evidence().contains("SQL injection")));
        assertTrue(lessons.stream().anyMatch(l -> l.category().equals(Lesson.CLARIFICATION)
                && l.role().equals("BACKEND_ARCHITECT") && l.evidence().contains("database")));
        assertTrue(lessons.stream().allMatch(l -> Lesson.PENDING.equals(l.status())));
    }

    @Test
    void skipsEntriesMissingRoleOrEvidence() {
        List<AuditEvent> events = List.of(
                prompt("Build-fix #1 by X", Map.of("role", "", "prompt", "x")),
                prompt("Build-fix #1 by X", Map.of("role", "BACKEND_DEVELOPER", "prompt", "")));
        assertTrue(LessonExtractor.extract(events, "p1").isEmpty());
    }
}
