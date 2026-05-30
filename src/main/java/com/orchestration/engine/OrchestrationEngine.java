package com.orchestration.engine;

import com.orchestration.task.WorkflowState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The central coordinator — a system-level <b>Mediator</b>.
 *
 * <p>Responsibilities: accept a project request, have the Team Lead build and own the task graph,
 * route ready tasks to agents on virtual threads, drive the task/project state machine, enforce
 * human-in-the-loop gates, account for tokens against the budget, and checkpoint after every step
 * so the run is resumable.
 *
 * <p>Methods are intentionally coarse and asynchronous: {@link #submit} returns a handle
 * immediately and work proceeds in the background, surfacing progress and gates through
 * {@link #status} and the message bus.
 */
public interface OrchestrationEngine {

    /** Submit a new project. Returns immediately; work proceeds asynchronously. */
    ProjectHandle submit(ProjectRequest request);

    /** Resume a project from its latest checkpoint, after a restart or token-budget renewal.
     *  Completed steps are not repeated. */
    ProjectHandle resume(String projectId);

    /** A point-in-time status snapshot for a project. */
    ProjectStatus status(String projectId);

    /** Resolve a pending human-in-the-loop gate (clarification, architecture sign-off, deployment). */
    void decideGate(String gateId, GateDecision decision);

    /** Graceful shutdown: stop dispatching new work and flush a final checkpoint. */
    void shutdown();

    record ProjectRequest(
            String featureRequest,
            Map<String, Object> options,    // e.g. target repo path, budget overrides
            Optional<Long> tokenBudget
    ) {
        public ProjectRequest {
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    record ProjectHandle(String projectId, WorkflowState state) {}

    record ProjectStatus(
            String projectId,
            WorkflowState state,
            int totalTasks,
            int completedTasks,
            long tokensUsed,
            List<PendingGate> pendingGates
    ) {
        public ProjectStatus {
            pendingGates = pendingGates == null ? List.of() : List.copyOf(pendingGates);
        }
    }

    record PendingGate(String gateId, GateType type, String prompt) {}

    enum GateType { CLARIFICATION, ARCHITECTURE_SIGN_OFF, DEPLOYMENT }

    record GateDecision(boolean approved, String responder, Optional<String> notes) {}
}
