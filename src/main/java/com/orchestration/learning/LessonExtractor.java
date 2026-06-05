package com.orchestration.learning;

import com.orchestration.audit.AuditLog.AuditEvent;
import com.orchestration.audit.AuditLog.EventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mines a finished run's audit events into evidence-backed lesson proposals. Deliberately conservative
 * (evidence-based): it only proposes from concrete signals the orchestrator already records —
 * build-fix dispatches, reviewer-finding fixes, and user-clarification rounds. Each proposal carries
 * the real excerpt that justifies it, so the user reviews receipts, not speculation. Pure/deterministic
 * — mirrors {@code MetricsCalculator}.
 */
public final class LessonExtractor {

    private static final int EVIDENCE_MAX = 500;

    private LessonExtractor() {
    }

    public static List<Lesson> extract(List<AuditEvent> events, String projectId) {
        List<Lesson> out = new ArrayList<>();
        for (AuditEvent e : events) {
            if (e.type() != EventType.PROMPT) {
                continue;
            }
            String summary = e.summary() == null ? "" : e.summary();
            String role = str(e.details().get("role"));
            String evidence = clip(str(e.details().get("prompt")));
            if (role.isBlank() || evidence.isBlank()) {
                continue;
            }
            if (summary.startsWith("Build-fix #")) {
                out.add(propose(projectId, role, Lesson.BUILD_FIX, buildLesson(evidence), evidence));
            } else if (summary.startsWith("Fixing ") && summary.contains("findings by")) {
                out.add(propose(projectId, role, Lesson.REVIEW_FIX, reviewLesson(evidence), evidence));
            } else if (summary.startsWith("Clarified with the user")) {
                out.add(propose(projectId, role, Lesson.CLARIFICATION, clarifyLesson(evidence), evidence));
            }
        }
        return out;
    }

    private static Lesson propose(String projectId, String role, String category,
                                  String lesson, String evidence) {
        return new Lesson(UUID.randomUUID().toString(), projectId, role, category, lesson, evidence,
                1, Lesson.PENDING, Instant.now().toString());
    }

    private static String buildLesson(String evidence) {
        return "## Build reliability (" + Lesson.BUILD_FIX + ")\n"
                + "A previous build/test run failed and had to be fixed. Before submitting, compile and "
                + "run the tests yourself and resolve any failures so the hand-off is green. Watch for:\n"
                + "```\n" + evidence + "\n```";
    }

    private static String reviewLesson(String evidence) {
        return "## Pre-empt review findings (" + Lesson.REVIEW_FIX + ")\n"
                + "A reviewer previously flagged issues that required a fix. Proactively address problems "
                + "of this kind before submitting:\n```\n" + evidence + "\n```";
    }

    private static String clarifyLesson(String evidence) {
        return "## Reduce clarification round-trips (" + Lesson.CLARIFICATION + ")\n"
                + "The team had to pause and ask the user the following. Where reasonable, confirm this "
                + "up front or state an explicit assumption instead of blocking:\n```\n" + evidence + "\n```";
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= EVIDENCE_MAX ? t : t.substring(0, EVIDENCE_MAX) + "…";
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
