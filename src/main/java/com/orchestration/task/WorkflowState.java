package com.orchestration.task;

import java.util.Set;

/**
 * The shared lifecycle vocabulary for both an individual {@link Task} and the overall
 * project (the README specifies the same state set for each).
 *
 * <p>This enum is the vocabulary of the <b>State pattern</b>: it declares the legal
 * transitions, and the concrete state machine enforces them. Centralising the
 * transition table here keeps the rules in one place and makes them testable in
 * isolation (see the README's required unit tests for the state machine).
 */
public enum WorkflowState {

    PENDING,
    IN_PROGRESS,
    BLOCKED,
    NEEDS_CLARIFICATION,
    IN_REVIEW,
    FAILED,
    DONE;

    /**
     * The states this state may legally transition into. Computed via a switch so there
     * are no enum self-reference issues at class-initialisation time.
     *
     * <p>Note {@code IN_REVIEW -> IN_PROGRESS} is what makes the QA bug-feedback loop
     * possible; the max-retry limit that prevents an infinite loop lives in the engine,
     * not in this transition table.
     */
    public Set<WorkflowState> allowedTransitions() {
        return switch (this) {
            case PENDING -> Set.of(IN_PROGRESS, BLOCKED, NEEDS_CLARIFICATION, FAILED);
            case IN_PROGRESS -> Set.of(IN_REVIEW, BLOCKED, NEEDS_CLARIFICATION, FAILED, DONE);
            case BLOCKED -> Set.of(PENDING, IN_PROGRESS, FAILED);
            case NEEDS_CLARIFICATION -> Set.of(PENDING, IN_PROGRESS, FAILED);
            case IN_REVIEW -> Set.of(DONE, IN_PROGRESS, FAILED);
            case FAILED, DONE -> Set.of();
        };
    }

    public boolean canTransitionTo(WorkflowState next) {
        return allowedTransitions().contains(next);
    }

    /** Terminal states accept no further transitions. */
    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }
}
