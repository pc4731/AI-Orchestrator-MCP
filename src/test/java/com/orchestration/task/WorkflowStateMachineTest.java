package com.orchestration.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowStateMachineTest {

    private final WorkflowStateMachine machine = new WorkflowStateMachine();

    @Test
    void allowsDeclaredTransition() {
        assertEquals(WorkflowState.IN_PROGRESS,
                machine.transition(WorkflowState.PENDING, WorkflowState.IN_PROGRESS));
    }

    @Test
    void rejectsUndeclaredTransitionWithDetails() {
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> machine.transition(WorkflowState.PENDING, WorkflowState.DONE));
        assertEquals(WorkflowState.PENDING, ex.from());
        assertEquals(WorkflowState.DONE, ex.to());
    }

    @Test
    void illegalTransitionIsAnIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> machine.transition(WorkflowState.DONE, WorkflowState.IN_PROGRESS));
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertTrue(WorkflowState.DONE.allowedTransitions().isEmpty());
        assertTrue(WorkflowState.FAILED.allowedTransitions().isEmpty());
        assertTrue(WorkflowState.DONE.isTerminal());
        assertTrue(WorkflowState.FAILED.isTerminal());
    }

    @Test
    void supportsBugFeedbackLoopReviewBackToInProgress() {
        assertTrue(machine.canTransition(WorkflowState.IN_REVIEW, WorkflowState.IN_PROGRESS));
    }

    @Test
    void selfTransitionIsRejected() {
        assertFalse(machine.canTransition(WorkflowState.IN_PROGRESS, WorkflowState.IN_PROGRESS));
    }
}
