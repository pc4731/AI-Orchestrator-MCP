package com.orchestration.task;

/**
 * Thrown when a {@link WorkflowState} transition that is not permitted by the transition table
 * is attempted. Extends {@link IllegalStateException} so callers that only care that "the
 * transition was illegal" can catch the broader type (matching the {@code TaskGraph} contract).
 */
public class IllegalStateTransitionException extends IllegalStateException {

    private final WorkflowState from;
    private final WorkflowState to;

    public IllegalStateTransitionException(WorkflowState from, WorkflowState to) {
        super("Illegal state transition: " + from + " -> " + to
                + " (allowed: " + from.allowedTransitions() + ")");
        this.from = from;
        this.to = to;
    }

    public WorkflowState from() {
        return from;
    }

    public WorkflowState to() {
        return to;
    }
}
