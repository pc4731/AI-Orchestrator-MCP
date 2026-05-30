package com.orchestration.budget;

import com.orchestration.llm.TokenUsage;
import com.orchestration.task.TaskId;

/**
 * Enforces per-task and per-project token budgets.
 *
 * <p>Every LLM call's {@link TokenUsage} is recorded here; when a budget is breached the manager
 * reports it so the engine can halt or escalate (configured in {@code config/budgets.yml}). This
 * is the guardrail against runaway cost.
 */
public interface TokenBudgetManager {

    void registerProjectBudget(String projectId, long maxTokens);

    void registerTaskBudget(TaskId taskId, long maxTokens);

    /** Record usage and return the resulting budget decision. */
    BudgetDecision record(String projectId, TaskId taskId, TokenUsage usage);

    long remainingForProject(String projectId);

    long remainingForTask(TaskId taskId);

    enum BudgetDecision {
        WITHIN_BUDGET,
        TASK_EXCEEDED,
        PROJECT_EXCEEDED
    }
}
