package com.orchestration.metrics;

/**
 * Per-role tally for a single run: how many times the role responded, broken down by outcome, plus how
 * many of its tasks needed rework. Watching {@code needsReview} and {@code rework} fall across runs is
 * the signal that the role is getting better at the kind of work it does.
 */
public record RoleMetrics(
        int responses,
        int completed,
        int needsReview,
        int escalated,
        int failed,
        int rework) {
}
