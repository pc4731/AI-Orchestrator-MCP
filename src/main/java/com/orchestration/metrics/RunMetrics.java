package com.orchestration.metrics;

import java.util.Map;

/**
 * A run's quality tally, derived from the audit log. The headline numbers — {@code reworkDispatches},
 * {@code buildFixDispatches}, {@code buildsFailed} — are the ones that should trend DOWN over successive
 * projects if the agents are improving; {@code byRole} shows which role is carrying the rework.
 */
public record RunMetrics(
        String projectId,
        String state,
        String finishedAt,
        long durationMillis,
        int prompts,
        int responses,
        int errors,
        int reworkDispatches,
        int buildFixDispatches,
        int buildsPassed,
        int buildsFailed,
        int clarificationRounds,
        Map<String, RoleMetrics> byRole) {
}
