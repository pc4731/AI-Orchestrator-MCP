package com.orchestration.budget;

import com.orchestration.llm.TokenUsage;
import com.orchestration.task.TaskId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultTokenBudgetManagerTest {

    private static TokenUsage usage(long inputTokens) {
        return new TokenUsage(inputTokens, 0, 0, 0);
    }

    @Test
    void withinBudgetTracksRemaining() {
        DefaultTokenBudgetManager manager = new DefaultTokenBudgetManager();
        manager.registerProjectBudget("p", 1000);

        assertEquals(TokenBudgetManager.BudgetDecision.WITHIN_BUDGET, manager.record("p", null, usage(100)));
        assertEquals(900, manager.remainingForProject("p"));
    }

    @Test
    void projectBudgetExceeded() {
        DefaultTokenBudgetManager manager = new DefaultTokenBudgetManager();
        manager.registerProjectBudget("p", 100);
        assertEquals(TokenBudgetManager.BudgetDecision.PROJECT_EXCEEDED, manager.record("p", null, usage(150)));
    }

    @Test
    void taskBudgetExceededWhenProjectStillFine() {
        DefaultTokenBudgetManager manager = new DefaultTokenBudgetManager();
        manager.registerProjectBudget("p", 100_000);
        TaskId task = new TaskId("t1");
        manager.registerTaskBudget(task, 100);

        assertEquals(TokenBudgetManager.BudgetDecision.TASK_EXCEEDED, manager.record("p", task, usage(150)));
    }

    @Test
    void projectBreachOutranksTaskBreach() {
        DefaultTokenBudgetManager manager = new DefaultTokenBudgetManager();
        manager.registerProjectBudget("p", 50);
        TaskId task = new TaskId("t1");
        manager.registerTaskBudget(task, 50);

        assertEquals(TokenBudgetManager.BudgetDecision.PROJECT_EXCEEDED, manager.record("p", task, usage(100)));
    }

    @Test
    void unregisteredScopesAreUnbounded() {
        DefaultTokenBudgetManager manager = new DefaultTokenBudgetManager();
        assertEquals(Long.MAX_VALUE, manager.remainingForProject("nope"));
        assertEquals(TokenBudgetManager.BudgetDecision.WITHIN_BUDGET, manager.record("nope", null, usage(999_999)));
    }
}
