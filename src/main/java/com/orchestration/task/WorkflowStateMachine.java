package com.orchestration.task;

/**
 * Enforces the legal state transitions declared by {@link WorkflowState} — the enforcement half
 * of the State pattern (the vocabulary/table lives on the enum).
 *
 * <p>Every task state change in the system funnels through here (the {@code TaskGraph} delegates to
 * it), so the rules are applied uniformly and can be tested in one place. The class is stateless
 * and therefore safe to share across threads.
 */
public class WorkflowStateMachine {

    /**
     * Validate a transition and return the target state.
     *
     * @throws IllegalStateTransitionException if {@code target} is not reachable from {@code current}
     */
    public WorkflowState transition(WorkflowState current, WorkflowState target) {
        if (!canTransition(current, target)) {
            throw new IllegalStateTransitionException(current, target);
        }
        return target;
    }

    public boolean canTransition(WorkflowState current, WorkflowState target) {
        return current.canTransitionTo(target);
    }
}
