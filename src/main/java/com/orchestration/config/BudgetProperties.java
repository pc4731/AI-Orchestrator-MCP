package com.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code config/budgets.yml}: per-project and per-task token caps, the bug-loop retry limit,
 * and what to do when a budget is breached.
 */
@ConfigurationProperties("budgets")
public record BudgetProperties(Project project, Task task, BugLoop bugLoop, BreachAction onBreach) {

    public record Project(long maxTokens) {
    }

    public record Task(long defaultMaxTokens) {
    }

    public record BugLoop(int maxRetries) {
    }

    public enum BreachAction {
        ESCALATE,
        HALT
    }
}
