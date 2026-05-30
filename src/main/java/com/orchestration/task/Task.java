package com.orchestration.task;

import com.orchestration.agent.AgentRole;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single unit of work in the {@link TaskGraph}.
 *
 * <p>Modelled as an immutable value object: a state change produces a new instance via
 * {@link #withState(WorkflowState)} rather than mutating in place. Immutability keeps
 * checkpoints and the audit log clean (each snapshot is a distinct, safely-shareable value)
 * and avoids data races when the engine dispatches tasks across virtual threads.
 *
 * @param assignedRole the kind of agent expected to handle this task; the engine uses it for
 *                     routing, but {@code Agent#canHandle} has the final say.
 * @param dependsOn    ids of prerequisite tasks that must reach {@code DONE} before this task is ready.
 * @param metadata     free-form structured fields (e.g. retry count, per-task budget override).
 */
public record Task(
        TaskId id,
        String title,
        String description,
        AgentRole assignedRole,
        WorkflowState state,
        List<TaskId> dependsOn,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public Task {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        description = description == null ? "" : description;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Return a copy in a new state, stamping {@code updatedAt}. Validation of the transition
     *  itself is the state machine's responsibility, not this value object's. */
    public Task withState(WorkflowState newState) {
        return new Task(id, title, description, assignedRole, newState,
                dependsOn, metadata, createdAt, Instant.now());
    }

    /** Return a copy with a new dependency list, stamping {@code updatedAt}. The {@code TaskGraph}
     *  is the authority on edges and uses this to keep the denormalised {@code dependsOn} in sync. */
    public Task withDependsOn(List<TaskId> newDependsOn) {
        return new Task(id, title, description, assignedRole, state,
                newDependsOn, metadata, createdAt, Instant.now());
    }
}
