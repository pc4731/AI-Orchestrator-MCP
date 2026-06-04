package com.orchestration.metrics;

import com.orchestration.audit.AuditLog.AuditEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds a project's audit events into a {@link RunMetrics} tally. It reads the structured details
 * ({@code role}, {@code outcome}) where possible, and the code-controlled audit summaries (e.g.
 * "Rework #", "Build-fix #", "Build PASSED/FAILED") for the counters those encode.
 */
public final class MetricsCalculator {

    // Indices into the per-role counter array.
    private static final int RESPONSES = 0, COMPLETED = 1, NEEDS_REVIEW = 2,
            ESCALATED = 3, FAILED = 4, REWORK = 5;

    private MetricsCalculator() {
    }

    public static RunMetrics summarize(List<AuditEvent> events, String projectId, String state) {
        int prompts = 0, responses = 0, errors = 0;
        int rework = 0, buildFix = 0, buildsPassed = 0, buildsFailed = 0, clarifications = 0;
        Instant first = null, last = null;
        Map<String, int[]> byRole = new LinkedHashMap<>();

        for (AuditEvent e : events) {
            if (first == null || e.at().isBefore(first)) {
                first = e.at();
            }
            if (last == null || e.at().isAfter(last)) {
                last = e.at();
            }
            String summary = e.summary() == null ? "" : e.summary();
            String role = str(e.details().get("role"));
            switch (e.type()) {
                case PROMPT -> {
                    prompts++;
                    if (summary.startsWith("Rework #")) {
                        rework++;
                        bump(byRole, role, REWORK);
                    } else if (summary.startsWith("Build-fix #")) {
                        buildFix++;
                    }
                    if (summary.startsWith("Clarified with the user")) {
                        clarifications++;
                    }
                }
                case RESPONSE -> {
                    responses++;
                    if (summary.startsWith("Build PASSED")) {
                        buildsPassed++;
                    } else if (summary.startsWith("Build FAILED")) {
                        buildsFailed++;
                    }
                    if (!role.isBlank()) {
                        bump(byRole, role, RESPONSES);
                        switch (str(e.details().get("outcome"))) {
                            case "COMPLETED" -> bump(byRole, role, COMPLETED);
                            case "NEEDS_REVIEW" -> bump(byRole, role, NEEDS_REVIEW);
                            case "ESCALATE", "INSUFFICIENT_INFORMATION" -> bump(byRole, role, ESCALATED);
                            case "FAILED" -> bump(byRole, role, FAILED);
                            default -> { /* outcome absent on some audits */ }
                        }
                    }
                }
                case ERROR -> errors++;
                default -> { /* STATE_CHANGE, GATE, etc. don't feed the tally */ }
            }
        }

        long duration = first != null && last != null ? Duration.between(first, last).toMillis() : 0L;
        Map<String, RoleMetrics> roles = new LinkedHashMap<>();
        byRole.forEach((r, c) -> roles.put(r,
                new RoleMetrics(c[RESPONSES], c[COMPLETED], c[NEEDS_REVIEW], c[ESCALATED], c[FAILED], c[REWORK])));
        String finishedAt = (last != null ? last : Instant.now()).toString();
        return new RunMetrics(projectId, state, finishedAt, duration, prompts, responses, errors,
                rework, buildFix, buildsPassed, buildsFailed, clarifications, roles);
    }

    private static void bump(Map<String, int[]> byRole, String role, int index) {
        if (role == null || role.isBlank()) {
            return;
        }
        byRole.computeIfAbsent(role, k -> new int[6])[index]++;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
