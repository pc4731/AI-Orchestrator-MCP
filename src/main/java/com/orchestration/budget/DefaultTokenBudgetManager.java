package com.orchestration.budget;

import com.orchestration.llm.TokenUsage;
import com.orchestration.task.TaskId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link TokenBudgetManager}: tracks cumulative token usage per project and per task and
 * reports a {@link BudgetDecision} on each record so the engine can halt or escalate.
 *
 * <p>A project-level breach outranks a task-level breach (it is the more severe, harder stop), so it
 * is reported first. Unregistered scopes are treated as unbounded.
 */
public class DefaultTokenBudgetManager implements TokenBudgetManager {

    private final Map<String, Long> projectBudgets = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> projectUsed = new ConcurrentHashMap<>();
    private final Map<TaskId, Long> taskBudgets = new ConcurrentHashMap<>();
    private final Map<TaskId, AtomicLong> taskUsed = new ConcurrentHashMap<>();

    @Override
    public void registerProjectBudget(String projectId, long maxTokens) {
        projectBudgets.put(projectId, maxTokens);
        projectUsed.putIfAbsent(projectId, new AtomicLong());
    }

    @Override
    public void registerTaskBudget(TaskId taskId, long maxTokens) {
        taskBudgets.put(taskId, maxTokens);
        taskUsed.putIfAbsent(taskId, new AtomicLong());
    }

    @Override
    public BudgetDecision record(String projectId, TaskId taskId, TokenUsage usage) {
        long tokens = usage.total();
        long projectTotal = projectUsed.computeIfAbsent(projectId, k -> new AtomicLong()).addAndGet(tokens);
        long taskTotal = taskId == null
                ? 0
                : taskUsed.computeIfAbsent(taskId, k -> new AtomicLong()).addAndGet(tokens);

        Long projectBudget = projectBudgets.get(projectId);
        if (projectBudget != null && projectTotal > projectBudget) {
            return BudgetDecision.PROJECT_EXCEEDED;
        }
        Long taskBudget = taskId == null ? null : taskBudgets.get(taskId);
        if (taskBudget != null && taskTotal > taskBudget) {
            return BudgetDecision.TASK_EXCEEDED;
        }
        return BudgetDecision.WITHIN_BUDGET;
    }

    @Override
    public long remainingForProject(String projectId) {
        Long budget = projectBudgets.get(projectId);
        if (budget == null) {
            return Long.MAX_VALUE;
        }
        long used = projectUsed.getOrDefault(projectId, new AtomicLong()).get();
        return budget - used;
    }

    @Override
    public long remainingForTask(TaskId taskId) {
        Long budget = taskBudgets.get(taskId);
        if (budget == null) {
            return Long.MAX_VALUE;
        }
        long used = taskUsed.getOrDefault(taskId, new AtomicLong()).get();
        return budget - used;
    }
}
